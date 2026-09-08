#!/usr/bin/env python3
"""
Libre 3 conformance harness — Stage 2 of report/libre3-native-plan.md.

Replays a Juggluco JNI trace (captured by dynamic/l3-capture-jni.js) through OUR build
of the vendored security core and compares outputs byte-for-byte. The point is to prove
the crypto port is correct WITHOUT touching a sensor: if this is green, the handshake our
AAPS module performs is bit-identical to the one Juggluco performs.

Runs against a HOST build (the core has only libc dependencies, so it compiles for x86-64
as readily as for arm64):

    clang -shared -fPIC -O2 -DL3_EXTERNAL_ENTROPY_ONLY=1 -I<cpp>/libre3/process \\
        $(ls <cpp>/libre3/process/*.c | grep -v openssl_symbols) -o libl3host.so

    python3 l3_replay.py --lib libl3host.so --trace realdata/l3-trace.jsonl

WHAT IS AND IS NOT COMPARABLE
-----------------------------
Every C entry point takes its random material as an ARGUMENT (nonce, plaintext), so
replaying with captured arguments is deterministic — with one exception:

  createEphemeralPublicKey  draws 32 bytes of entropy INSIDE the core.

So:
  * RESUME path (savedAuthorization non-null, no ephemeral generated) — fully byte-comparable.
    This is the path Stage 3's credential lift uses, so it is the one that matters most.
  * FRESH PAIRING — the ephemeral key differs every run, so that call and everything
    derived from it are reported as INFO, not as failures. Making those byte-exact would
    need the capture to also record the entropy Juggluco consumed.

A call is only ever reported PASS when its output actually matched.
"""
import argparse
import ctypes
import json
import sys

OK = 1  # the core signals success as 1, NOT 0 (L3_SECURITY_OK==0 is an internal status enum)

LEN_SAVED_AUTHORIZATION = 149
LEN_PATCH_CERTIFICATE = 140
LEN_PATCH_PUBLIC_KEY = 65
LEN_EPHEMERAL_PUBLIC_KEY = 64
LEN_CHALLENGE_NONCE = 7
LEN_CHALLENGE_REPLY_PLAIN = 36
LEN_CHALLENGE_REPLY_CRYPT = 40
LEN_CHALLENGE_RESPONSE_CRYPT = 60
LEN_CHALLENGE_RESPONSE_PLAIN = 56

ENTROPY_CB = ctypes.CFUNCTYPE(
    ctypes.c_int, ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint8), ctypes.c_size_t
)


class EngineConfig(ctypes.Structure):
    _fields_ = [
        ("master_secret", ctypes.c_void_p),
        ("master_secret_len", ctypes.c_size_t),
        ("entropy", ENTROPY_CB),
        ("entropy_user", ctypes.c_void_p),
        ("export_format", ctypes.c_uint32),
        ("challenge_key_mode", ctypes.c_uint32),
    ]


class Core:
    def __init__(self, path, entropy_pool=None):
        self.lib = ctypes.CDLL(path)
        self.entropy_pool = list(entropy_pool or [])

        # Deterministic-by-default entropy: replaying the same trace twice gives the same
        # result, so a diff is a real difference and not RNG noise. Recorded entropy, when
        # the capture provides it, is consumed first.
        self._counter = 0

        def _entropy(_user, out, n):
            for i in range(n):
                if self.entropy_pool:
                    out[i] = self.entropy_pool.pop(0)
                else:
                    out[i] = (self._counter * 31 + i * 17) & 0xFF
            self._counter += 1
            return 1

        self._entropy_ref = ENTROPY_CB(_entropy)  # keep alive: ctypes will not

        L = self.lib
        L.l3_sensor_security_context_create.restype = ctypes.c_void_p
        L.l3_sensor_security_context_create.argtypes = [ctypes.POINTER(EngineConfig)]
        for name, args in [
            ("begin_handshake", []),
            ("select_app_key_and_saved_authorization",
             [ctypes.c_uint, ctypes.c_void_p, ctypes.c_size_t]),
            ("set_patch_certificate", [ctypes.c_void_p, ctypes.c_size_t]),
            ("create_ephemeral_public_key_into", [ctypes.c_void_p]),
            ("derive_authorization_root", [ctypes.c_void_p, ctypes.c_size_t]),
            ("encrypt_challenge_reply_into",
             [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p]),
            ("decrypt_challenge_response_into",
             [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p]),
            ("export_saved_authorization_into", [ctypes.c_void_p]),
        ]:
            fn = getattr(L, "l3_sensor_security_" + name)
            fn.restype = ctypes.c_int
            fn.argtypes = [ctypes.c_void_p] + args

        cfg = EngineConfig()
        ctypes.memset(ctypes.byref(cfg), 0, ctypes.sizeof(cfg))
        cfg.entropy = self._entropy_ref
        self.ctx = L.l3_sensor_security_context_create(ctypes.byref(cfg))
        if not self.ctx:
            raise RuntimeError("l3_sensor_security_context_create returned NULL")

    def _buf(self, n):
        return (ctypes.c_uint8 * n)()

    def begin(self):
        return self.lib.l3_sensor_security_begin_handshake(self.ctx)

    def select_app_key(self, version, saved_hex):
        if saved_hex is None:
            return self.lib.l3_sensor_security_select_app_key_and_saved_authorization(
                self.ctx, version, None, 0)
        raw = bytes.fromhex(saved_hex)
        return self.lib.l3_sensor_security_select_app_key_and_saved_authorization(
            self.ctx, version, raw, len(raw))

    def set_patch_cert(self, cert_hex):
        raw = bytes.fromhex(cert_hex)
        return self.lib.l3_sensor_security_set_patch_certificate(self.ctx, raw, len(raw))

    def create_ephemeral(self):
        out = self._buf(LEN_EPHEMERAL_PUBLIC_KEY)
        rc = self.lib.l3_sensor_security_create_ephemeral_public_key_into(self.ctx, out)
        return rc, bytes(out).hex() if rc == OK else None

    def derive_root(self, pub_hex):
        raw = bytes.fromhex(pub_hex)
        return self.lib.l3_sensor_security_derive_authorization_root(self.ctx, raw, len(raw))

    def encrypt_reply(self, nonce_hex, plain_hex):
        n, p = bytes.fromhex(nonce_hex), bytes.fromhex(plain_hex)
        out = self._buf(LEN_CHALLENGE_REPLY_CRYPT)
        rc = self.lib.l3_sensor_security_encrypt_challenge_reply_into(
            self.ctx, n, len(n), p, len(p), out)
        return rc, bytes(out).hex() if rc == OK else None

    def decrypt_response(self, nonce_hex, cipher_hex):
        n, c = bytes.fromhex(nonce_hex), bytes.fromhex(cipher_hex)
        out = self._buf(LEN_CHALLENGE_RESPONSE_PLAIN)
        rc = self.lib.l3_sensor_security_decrypt_challenge_response_into(
            self.ctx, n, len(n), c, len(c), out)
        return rc, bytes(out).hex() if rc == OK else None

    def export_saved(self):
        out = self._buf(LEN_SAVED_AUTHORIZATION)
        rc = self.lib.l3_sensor_security_export_saved_authorization_into(self.ctx, out)
        return rc, bytes(out).hex() if rc == OK else None


# Calls whose output cannot be byte-compared because entropy is drawn inside the core.
NONDETERMINISTIC = {"libre3CreateEphemeralPublicKey"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lib", required=True)
    ap.add_argument("--trace", required=True)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    calls = []
    with open(args.trace) as f:
        for line in f:
            line = line.strip()
            if line:
                calls.append(json.loads(line))
    if not calls:
        sys.exit("[!] trace is empty — nothing to replay")

    print(f"[*] {len(calls)} captured calls from {args.trace}")

    core = None
    passed = failed = info = skipped = 0
    fresh_pairing_tainted = False

    for i, c in enumerate(calls, 1):
        name, inp, want = c["call"], c.get("in", []), c.get("out")

        if name == "libre3BeginSecurityHandshake":
            core = Core(args.lib)
            rc = core.begin()
            status = "PASS" if rc == OK else "FAIL"
            print(f"[{i:3d}] {name:42s} rc={rc} {status}")
            passed += rc == OK
            failed += rc != OK
            continue

        if name == "libre3FreeSecurityContext":
            # No-op by design in our shim too (queued GATT callbacks / use-after-free).
            print(f"[{i:3d}] {name:42s} SKIP (no-op by design)")
            skipped += 1
            continue

        if core is None:
            print(f"[{i:3d}] {name:42s} SKIP (no handshake started yet in trace)")
            skipped += 1
            continue

        got = None
        rc = None
        if name == "libre3SelectAppKeyAndSavedAuthorization":
            rc = core.select_app_key(inp[1], inp[2])
            got = rc
            if inp[2] is None:
                fresh_pairing_tainted = True   # no saved auth -> ephemeral will be generated
        elif name == "libre3AcceptPatchCertificate":
            rc = got = core.set_patch_cert(inp[1])
        elif name == "libre3CreateEphemeralPublicKey":
            rc, got = core.create_ephemeral()
        elif name == "libre3DeriveAuthorizationRoot":
            rc = got = core.derive_root(inp[1])
        elif name == "libre3EncryptChallengeReply":
            rc, got = core.encrypt_reply(inp[1], inp[2])
        elif name == "libre3DecryptChallengeResponse":
            rc, got = core.decrypt_response(inp[1], inp[2])
        elif name == "libre3ExportSavedAuthorization":
            rc, got = core.export_saved()
        else:
            print(f"[{i:3d}] {name:42s} SKIP (unknown call)")
            skipped += 1
            continue

        nondet = name in NONDETERMINISTIC or (fresh_pairing_tainted and isinstance(want, str))
        if nondet:
            shape = "len=%d" % (len(got) // 2) if isinstance(got, str) else f"rc={got}"
            print(f"[{i:3d}] {name:42s} INFO ({shape}; not byte-comparable — fresh ephemeral)")
            info += 1
            continue

        if got == want:
            print(f"[{i:3d}] {name:42s} PASS")
            passed += 1
        else:
            print(f"[{i:3d}] {name:42s} FAIL")
            print(f"      expected: {want}")
            print(f"      got     : {got}")
            failed += 1

    print()
    print(f"[*] pass={passed} fail={failed} info={info} skip={skipped}")
    if fresh_pairing_tainted:
        print("[*] trace contains a FRESH PAIRING (savedAuthorization=null): outputs downstream")
        print("    of the internally-generated ephemeral key are reported INFO, not PASS.")
        print("    Capture a RESUME handshake (kAuth present) for a fully byte-exact run.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
