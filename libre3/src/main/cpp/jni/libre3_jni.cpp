/*
 * JNI shim for the vendored Libre 3 security core.
 *
 * This file is OURS (not vendored) — see ../VENDOR.md. It is deliberately thin:
 * every function here is argument marshalling over the C API declared in
 * sensor_security_context.h, plus the AES-CCM session cipher.
 *
 * Signatures and return conventions intentionally mirror Juggluco's
 * tk.glucodata.Natives so a captured Juggluco JNI trace can be replayed through
 * this module and compared byte-for-byte (Stage 2 conformance harness).
 *
 * NOTE ON RETURN CONVENTION: the core's int-returning entry points signal
 * SUCCESS AS 1, not 0. L3_SECURITY_OK (0) is a status enum used internally.
 * Callers must test == 1.
 */

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <cerrno>
#include <cstdlib>

#include <sys/random.h>   // getrandom(2) — API 28+; this module requires minSdk 31
#include <unistd.h>
#include <fcntl.h>

extern "C" {
#include "sensor_security_context.h"
#include "libre3_app_core.h"
}
#include <tinycrypt/ccm_mode.h>

#define JNIFN(name) Java_app_aaps_libre3_Libre3Native_##name

namespace {

// ---------------------------------------------------------------------------
// Entropy
// ---------------------------------------------------------------------------
// The vendored core is compiled with L3_EXTERNAL_ENTROPY_ONLY=1, which removes
// its libcrypto (RAND_bytes) dependency entirely. Entropy MUST therefore be
// supplied through l3_security_engine_config.entropy, or ephemeral key
// generation returns L3_SECURITY_ERR_UNSUPPORTED.
//
// We use getrandom(2) rather than a JNI upcall into java.security.SecureRandom:
// the callback fires deep inside the core, on whichever BLE callback thread is
// driving the handshake, so an upcall would need a cached JavaVM plus
// thread-attach handling for no benefit. getrandom() is the same kernel CSPRNG
// SecureRandom draws from.
//
// Contract: return 1 on success, 0 on failure (matches the core's convention).
int entropy_from_kernel(void * /*user*/, uint8_t *out, size_t out_len) {
    if (out == nullptr || out_len == 0) return 0;

    size_t filled = 0;
    while (filled < out_len) {
        ssize_t n = ::getrandom(out + filled, out_len - filled, 0);
        if (n > 0) { filled += static_cast<size_t>(n); continue; }
        if (n < 0 && errno == EINTR) continue;
        break;
    }
    if (filled == out_len) return 1;

    // Fallback for the (not expected on API 31+) case where getrandom is
    // unavailable or interrupted past recovery.
    int fd = ::open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    while (filled < out_len) {
        ssize_t n = ::read(fd, out + filled, out_len - filled);
        if (n > 0) { filled += static_cast<size_t>(n); continue; }
        if (n < 0 && errno == EINTR) continue;
        break;
    }
    ::close(fd);
    return filled == out_len ? 1 : 0;
}

l3_security_engine_config make_config() {
    l3_security_engine_config cfg;
    std::memset(&cfg, 0, sizeof(cfg));
    cfg.entropy = &entropy_from_kernel;
    cfg.entropy_user = nullptr;
    return cfg;
}

inline l3_sensor_security_context *ctx_of(jlong handle) {
    return reinterpret_cast<l3_sensor_security_context *>(static_cast<intptr_t>(handle));
}
inline jlong handle_of(l3_sensor_security_context *c) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(c));
}

// Borrowed view over a jbyteArray. Enforces an exact expected length, which is
// how every buffer in this protocol is specified (see the L3_LEN_* enum).
class ByteView {
  public:
    ByteView(JNIEnv *env, jbyteArray arr, jsize expected, bool nullable = false)
        : env_(env), arr_(arr) {
        if (arr == nullptr) { valid_ = nullable; return; }
        if (env->GetArrayLength(arr) != expected) return;
        ptr_ = env->GetByteArrayElements(arr, nullptr);
        if (ptr_ == nullptr) return;
        len_ = static_cast<size_t>(expected);
        valid_ = true;
    }
    ~ByteView() {
        if (ptr_ != nullptr) env_->ReleaseByteArrayElements(arr_, ptr_, JNI_ABORT);
    }
    ByteView(const ByteView &) = delete;
    ByteView &operator=(const ByteView &) = delete;

    bool valid() const { return valid_; }
    const uint8_t *data() const { return reinterpret_cast<const uint8_t *>(ptr_); }
    size_t size() const { return len_; }

  private:
    JNIEnv *env_;
    jbyteArray arr_;
    jbyte *ptr_ = nullptr;
    size_t len_ = 0;
    bool valid_ = false;
};

// Runs `fill` into a stack buffer of N bytes; returns a fresh jbyteArray on
// success (fill returns 1), nullptr otherwise. Never returns a partially
// written array — a failed handshake step must be indistinguishable from "no
// result" at the Kotlin boundary.
template <size_t N, typename F>
jbyteArray fixed_array(JNIEnv *env, F fill) {
    uint8_t buf[N];
    std::memset(buf, 0, N);
    if (fill(buf) != 1) return nullptr;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(N));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(N),
                            reinterpret_cast<const jbyte *>(buf));
    return out;
}

// ---------------------------------------------------------------------------
// Session cipher (AES-128-CCM)
// ---------------------------------------------------------------------------
// Reimplemented here rather than vendoring Juggluco's bcrypt.cpp, so no
// vendored file needs editing (VENDOR.md rule 1). The scheme, byte for byte:
//
//   nonce[13] = seq_lo | seq_hi | packetDescriptor[kind][0..2] | iv_enc[0..7]
//   tag       = 4 bytes, appended by CCM
//   on the wire, the trailing 2 bytes of a frame carry the sequence number
//
// `kind` selects the packet descriptor. Observed mapping: 2 = patch status,
// 3 = glucose (1-minute), 5 = fast data, 6 = event log. 0/1/4/7 unidentified.
constexpr uint8_t kPacketDescriptor[8][3] = {
    {0x00, 0x00, 0x00}, {0x00, 0x00, 0x0F}, {0x00, 0x00, 0xF0}, {0x00, 0x0F, 0x00},
    {0x00, 0xF0, 0x00}, {0x0F, 0x00, 0x00}, {0xF0, 0x00, 0x00}, {0x44, 0x00, 0x00},
};
constexpr int kNonceLen = 13;
constexpr int kTagLen = 4;
constexpr int kIvLen = 8;

struct SessionCipher {
    uint8_t key[16];
    uint8_t iv_enc[kIvLen];
    int outSequence;   // Juggluco starts this at 1 and post-increments
};

void build_nonce(const SessionCipher *sc, int kind, uint8_t seq_lo, uint8_t seq_hi,
                 uint8_t out[kNonceLen]) {
    out[0] = seq_lo;
    out[1] = seq_hi;
    std::memcpy(out + 2, kPacketDescriptor[kind], 3);
    std::memcpy(out + 5, sc->iv_enc, kIvLen);
}

}  // namespace

extern "C" {

// ===========================================================================
// Handshake — mirrors tk.glucodata.Natives.libre3*
// ===========================================================================

/* Allocate on first use; on reconnect reset and reuse. Returns 0 on failure. */
JNIEXPORT jlong JNICALL JNIFN(beginSecurityHandshake)(JNIEnv *, jclass, jlong context) {
    l3_sensor_security_context *sec = ctx_of(context);
    if (sec == nullptr) {
        l3_security_engine_config cfg = make_config();
        sec = l3_sensor_security_context_create(&cfg);
        if (sec == nullptr) return 0;
    }
    if (l3_sensor_security_begin_handshake(sec) != 1) {
        l3_sensor_security_context_destroy(sec);
        return 0;
    }
    return handle_of(sec);
}

/*
 * DELIBERATE NO-OP — do not "fix" this.
 *
 * Inherited from Juggluco, which observed a SIGBUS at sensor termination with
 * the faulting address equal to this context pointer: Android can still deliver
 * queued BLE GATT callbacks while free() is unwinding, so destroying the native
 * object here opens a use-after-free window. The context is a small fixed-size
 * allocation; leaking it for process lifetime is the cheaper trade.
 */
JNIEXPORT void JNICALL JNIFN(freeSecurityContext)(JNIEnv *, jclass, jlong context) {
    (void)context;
}

JNIEXPORT jint JNICALL JNIFN(selectAppKeyAndSavedAuthorization)(
    JNIEnv *env, jclass, jlong context, jint securityVersion, jbyteArray savedAuthorization) {
    l3_sensor_security_context *sec = ctx_of(context);
    if (sec == nullptr || securityVersion < 0) return L3_SECURITY_ERR_ARGUMENT;

    // savedAuthorization is optional: null = fresh pairing, 149 bytes = resume.
    ByteView saved(env, savedAuthorization, L3_LEN_SAVED_AUTHORIZATION, /*nullable=*/true);
    if (!saved.valid()) return L3_SECURITY_ERR_ARGUMENT;

    return l3_sensor_security_select_app_key_and_saved_authorization(
        sec, static_cast<unsigned>(securityVersion), saved.data(), saved.size());
}

JNIEXPORT jint JNICALL JNIFN(acceptPatchCertificate)(
    JNIEnv *env, jclass, jlong context, jbyteArray patchCertificate) {
    l3_sensor_security_context *sec = ctx_of(context);
    if (sec == nullptr) return L3_SECURITY_ERR_ARGUMENT;

    ByteView cert(env, patchCertificate, L3_LEN_PATCH_CERTIFICATE);
    if (!cert.valid()) return L3_SECURITY_ERR_ARGUMENT;

    return l3_sensor_security_set_patch_certificate(sec, cert.data(), cert.size());
}

JNIEXPORT jbyteArray JNICALL JNIFN(createEphemeralPublicKey)(
    JNIEnv *env, jclass, jlong context) {
    l3_sensor_security_context *sec = ctx_of(context);
    if (sec == nullptr) return nullptr;
    return fixed_array<L3_LEN_EPHEMERAL_PUBLIC_KEY>(env, [sec](uint8_t *out) {
        return l3_sensor_security_create_ephemeral_public_key_into(sec, out);
    });
}

JNIEXPORT jint JNICALL JNIFN(deriveAuthorizationRoot)(
    JNIEnv *env, jclass, jlong context, jbyteArray patchEphemeralPublicKey) {
    l3_sensor_security_context *sec = ctx_of(context);
    if (sec == nullptr) return L3_SECURITY_ERR_ARGUMENT;

    ByteView pub(env, patchEphemeralPublicKey, L3_LEN_PATCH_PUBLIC_KEY);
    if (!pub.valid()) return L3_SECURITY_ERR_ARGUMENT;

    return l3_sensor_security_derive_authorization_root(sec, pub.data(), pub.size());
}

JNIEXPORT jbyteArray JNICALL JNIFN(encryptChallengeReply)(
    JNIEnv *env, jclass, jlong context, jbyteArray nonce, jbyteArray plaintext) {
    l3_sensor_security_context *sec = ctx_of(context);
    if (sec == nullptr) return nullptr;

    ByteView n(env, nonce, L3_LEN_CHALLENGE_NONCE);
    ByteView p(env, plaintext, L3_LEN_CHALLENGE_REPLY_PLAIN);
    if (!n.valid() || !p.valid()) return nullptr;

    return fixed_array<L3_LEN_CHALLENGE_REPLY_CRYPT>(env, [&](uint8_t *out) {
        return l3_sensor_security_encrypt_challenge_reply_into(
            sec, n.data(), n.size(), p.data(), p.size(), out);
    });
}

JNIEXPORT jbyteArray JNICALL JNIFN(decryptChallengeResponse)(
    JNIEnv *env, jclass, jlong context, jbyteArray nonce, jbyteArray ciphertext) {
    l3_sensor_security_context *sec = ctx_of(context);
    if (sec == nullptr) return nullptr;

    ByteView n(env, nonce, L3_LEN_CHALLENGE_NONCE);
    ByteView c(env, ciphertext, L3_LEN_CHALLENGE_RESPONSE_CRYPT);
    if (!n.valid() || !c.valid()) return nullptr;

    return fixed_array<L3_LEN_CHALLENGE_RESPONSE_PLAIN>(env, [&](uint8_t *out) {
        return l3_sensor_security_decrypt_challenge_response_into(
            sec, n.data(), n.size(), c.data(), c.size(), out);
    });
}

JNIEXPORT jbyteArray JNICALL JNIFN(exportSavedAuthorization)(
    JNIEnv *env, jclass, jlong context) {
    l3_sensor_security_context *sec = ctx_of(context);
    if (sec == nullptr) return nullptr;
    return fixed_array<L3_LEN_SAVED_AUTHORIZATION>(env, [sec](uint8_t *out) {
        return l3_sensor_security_export_saved_authorization_into(sec, out);
    });
}

// ===========================================================================
// Session cipher
// ===========================================================================

JNIEXPORT jlong JNICALL JNIFN(initSessionCipher)(
    JNIEnv *env, jclass, jlong previous, jbyteArray jkey, jbyteArray jiv) {
    ByteView key(env, jkey, 16);
    ByteView iv(env, jiv, kIvLen);
    if (!key.valid() || !iv.valid()) return 0;

    SessionCipher *sc = reinterpret_cast<SessionCipher *>(static_cast<intptr_t>(previous));
    if (sc == nullptr) {
        sc = static_cast<SessionCipher *>(std::calloc(1, sizeof(SessionCipher)));
        if (sc == nullptr) return 0;
    }
    std::memcpy(sc->key, key.data(), 16);
    std::memcpy(sc->iv_enc, iv.data(), kIvLen);
    sc->outSequence = 1;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(sc));
}

JNIEXPORT void JNICALL JNIFN(freeSessionCipher)(JNIEnv *, jclass, jlong handle) {
    // Same reasoning as freeSecurityContext: queued GATT callbacks can still
    // reach the cipher while teardown unwinds. Leak the small allocation.
    (void)handle;
}

/* Returns the decrypted payload (inputLen - 6 bytes), or null on auth failure. */
JNIEXPORT jbyteArray JNICALL JNIFN(sessionDecrypt)(
    JNIEnv *env, jclass, jlong handle, jint kind, jbyteArray jencrypted) {
    SessionCipher *sc = reinterpret_cast<SessionCipher *>(static_cast<intptr_t>(handle));
    if (sc == nullptr || jencrypted == nullptr) return nullptr;
    if (kind < 0 || kind >= 8) return nullptr;

    const jsize inLen = env->GetArrayLength(jencrypted);
    // wire frame = ciphertext || tag(4) || seq(2)
    if (inLen < kTagLen + 2 + 1) return nullptr;

    jbyte *raw = env->GetByteArrayElements(jencrypted, nullptr);
    if (raw == nullptr) return nullptr;
    const uint8_t *in = reinterpret_cast<const uint8_t *>(raw);

    const int cipherLen = static_cast<int>(inLen) - 2;      // strip trailing seq
    const int plainLen = cipherLen - kTagLen;

    uint8_t nonce[kNonceLen];
    build_nonce(sc, kind, in[cipherLen], in[cipherLen + 1], nonce);

    uint8_t *plain = static_cast<uint8_t *>(std::malloc(static_cast<size_t>(plainLen)));
    if (plain == nullptr) {
        env->ReleaseByteArrayElements(jencrypted, raw, JNI_ABORT);
        return nullptr;
    }

    struct tc_ccm_mode_struct c;
    struct tc_aes_key_sched_struct sched;
    jbyteArray out = nullptr;

    if (tc_aes128_set_encrypt_key(&sched, sc->key) != 0 &&
        tc_ccm_config(&c, &sched, nonce, kNonceLen, kTagLen) != 0 &&
        tc_ccm_decryption_verification(plain, static_cast<unsigned>(plainLen), nullptr, 0,
                                       in, static_cast<unsigned>(cipherLen), &c) != 0) {
        out = env->NewByteArray(plainLen);
        if (out != nullptr) {
            env->SetByteArrayRegion(out, 0, plainLen, reinterpret_cast<const jbyte *>(plain));
        }
    }

    std::free(plain);
    env->ReleaseByteArrayElements(jencrypted, raw, JNI_ABORT);
    return out;
}

/* Returns ciphertext || tag(4) || seq(2), i.e. inputLen + 6 bytes. */
JNIEXPORT jbyteArray JNICALL JNIFN(sessionEncrypt)(
    JNIEnv *env, jclass, jlong handle, jint kind, jbyteArray jplain) {
    SessionCipher *sc = reinterpret_cast<SessionCipher *>(static_cast<intptr_t>(handle));
    if (sc == nullptr || jplain == nullptr) return nullptr;
    if (kind < 0 || kind >= 8) return nullptr;

    const jsize inLen = env->GetArrayLength(jplain);
    if (inLen <= 0) return nullptr;

    jbyte *raw = env->GetByteArrayElements(jplain, nullptr);
    if (raw == nullptr) return nullptr;

    const int seq = sc->outSequence;
    const uint8_t seq_lo = static_cast<uint8_t>(seq & 0xFF);
    const uint8_t seq_hi = static_cast<uint8_t>((seq >> 8) & 0xFF);

    uint8_t nonce[kNonceLen];
    build_nonce(sc, kind, seq_lo, seq_hi, nonce);

    const int cipherLen = static_cast<int>(inLen) + kTagLen;
    const int frameLen = cipherLen + 2;
    uint8_t *frame = static_cast<uint8_t *>(std::malloc(static_cast<size_t>(frameLen)));
    if (frame == nullptr) {
        env->ReleaseByteArrayElements(jplain, raw, JNI_ABORT);
        return nullptr;
    }

    struct tc_ccm_mode_struct c;
    struct tc_aes_key_sched_struct sched;
    jbyteArray out = nullptr;

    if (tc_aes128_set_encrypt_key(&sched, sc->key) != 0 &&
        tc_ccm_config(&c, &sched, nonce, kNonceLen, kTagLen) != 0 &&
        tc_ccm_generation_encryption(frame, static_cast<unsigned>(cipherLen), nullptr, 0,
                                     reinterpret_cast<const uint8_t *>(raw),
                                     static_cast<unsigned>(inLen), &c) != 0) {
        frame[cipherLen] = seq_lo;
        frame[cipherLen + 1] = seq_hi;
        sc->outSequence++;
        out = env->NewByteArray(frameLen);
        if (out != nullptr) {
            env->SetByteArrayRegion(out, 0, frameLen, reinterpret_cast<const jbyte *>(frame));
        }
    }

    std::free(frame);
    env->ReleaseByteArrayElements(jplain, raw, JNI_ABORT);
    return out;
}

}  // extern "C"
