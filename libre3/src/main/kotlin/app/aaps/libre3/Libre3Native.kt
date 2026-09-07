package app.aaps.libre3

/**
 * Raw JNI boundary to the vendored Libre 3 security core.
 *
 * Prefer [Libre3SecuritySession] over calling these directly — it enforces the
 * handshake ordering and turns the sentinel return values into typed results.
 *
 * Names and argument order deliberately mirror Juggluco's `tk.glucodata.Natives`
 * so a captured Juggluco JNI trace can be replayed through this module and
 * compared byte-for-byte (Stage 2 conformance harness).
 *
 * IMPORTANT — return convention: the int-returning handshake calls signal
 * **success as 1**, not 0. Byte-array calls return `null` on failure and never
 * a partially-written array.
 */
object Libre3Native {

    /** Fixed protocol buffer sizes, from the core's `L3_LEN_*` enum. */
    const val LEN_SAVED_AUTHORIZATION = 149
    const val LEN_PATCH_CERTIFICATE = 140
    const val LEN_PATCH_PUBLIC_KEY = 65
    const val LEN_EPHEMERAL_PUBLIC_KEY = 64
    const val LEN_CHALLENGE_NONCE = 7
    const val LEN_CHALLENGE_REPLY_PLAIN = 36
    const val LEN_CHALLENGE_REPLY_CRYPT = 40
    const val LEN_CHALLENGE_RESPONSE_CRYPT = 60
    const val LEN_CHALLENGE_RESPONSE_PLAIN = 56

    const val OK = 1

    @Volatile private var loaded = false

    /** Loads `liblibre3.so`. Safe to call repeatedly. Throws if the ABI is missing. */
    @Synchronized fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("libre3")
        loaded = true
    }

    /**
     * Allocates the per-connection security state, or resets an existing one for
     * a reconnect. Pass 0 on first use, the previous handle on reconnect.
     * @return an opaque handle, or 0 on failure.
     */
    @JvmStatic external fun beginSecurityHandshake(context: Long): Long

    /**
     * No-op by design — see the comment in `libre3_jni.cpp`. The native context
     * is intentionally leaked for process lifetime because queued BLE GATT
     * callbacks can still reach it while teardown unwinds.
     */
    @JvmStatic external fun freeSecurityContext(context: Long)

    /**
     * @param savedAuthorization `null` for a fresh pairing, or exactly
     *   [LEN_SAVED_AUTHORIZATION] bytes to resume a previously authorised
     *   session without re-pairing (the "kAuth" credential).
     * @return [OK] on success, negative on error.
     */
    @JvmStatic external fun selectAppKeyAndSavedAuthorization(
        context: Long, securityVersion: Int, savedAuthorization: ByteArray?
    ): Int

    /** @param patchCertificate exactly [LEN_PATCH_CERTIFICATE] bytes. */
    @JvmStatic external fun acceptPatchCertificate(context: Long, patchCertificate: ByteArray): Int

    /** @return [LEN_EPHEMERAL_PUBLIC_KEY] bytes, or null on failure. */
    @JvmStatic external fun createEphemeralPublicKey(context: Long): ByteArray?

    /** @param patchEphemeralPublicKey exactly [LEN_PATCH_PUBLIC_KEY] bytes. */
    @JvmStatic external fun deriveAuthorizationRoot(context: Long, patchEphemeralPublicKey: ByteArray): Int

    /** @return [LEN_CHALLENGE_REPLY_CRYPT] bytes, or null on failure. */
    @JvmStatic external fun encryptChallengeReply(
        context: Long, nonce: ByteArray, plaintext: ByteArray
    ): ByteArray?

    /** @return [LEN_CHALLENGE_RESPONSE_PLAIN] bytes, or null on failure. */
    @JvmStatic external fun decryptChallengeResponse(
        context: Long, nonce: ByteArray, ciphertext: ByteArray
    ): ByteArray?

    /**
     * Exports the kAuth credential to persist for reconnect.
     * @return [LEN_SAVED_AUTHORIZATION] bytes, or null if no authorisation is held.
     */
    @JvmStatic external fun exportSavedAuthorization(context: Long): ByteArray?

    // --- session cipher (AES-128-CCM) --------------------------------------

    /**
     * @param key 16 bytes (`kEnc`), @param iv 8 bytes (`ivEnc`) — both come out
     *   of [decryptChallengeResponse]'s plaintext.
     * @return an opaque handle, or 0 on failure.
     */
    @JvmStatic external fun initSessionCipher(previous: Long, key: ByteArray, iv: ByteArray): Long

    /** No-op by design, same reasoning as [freeSecurityContext]. */
    @JvmStatic external fun freeSessionCipher(handle: Long)

    /**
     * @param kind packet descriptor index 0..7. Observed: 2 = patch status,
     *   3 = glucose (1-minute), 5 = fast data, 6 = event log.
     * @return plaintext (`frame.size - 6` bytes), or null if authentication fails.
     */
    @JvmStatic external fun sessionDecrypt(handle: Long, kind: Int, encrypted: ByteArray): ByteArray?

    /** @return ciphertext || tag(4) || seq(2), i.e. `plain.size + 6` bytes. */
    @JvmStatic external fun sessionEncrypt(handle: Long, kind: Int, plain: ByteArray): ByteArray?
}
