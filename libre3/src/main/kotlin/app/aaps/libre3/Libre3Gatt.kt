package app.aaps.libre3

import java.util.UUID

/**
 * Libre 3 GATT map and security-protocol constants.
 *
 * UUIDs transcribed from Juggluco's `SuperGattCallback` (GPL-3.0) — see
 * `src/main/cpp/VENDOR.md`. Everything else here was derived from the captured handshake
 * trace and from reading `Libre3GattCallback`; see report/libre3-native-plan.md.
 */
object Libre3Gatt {

    // --- services -----------------------------------------------------------
    val DATA_SERVICE: UUID = UUID.fromString("089810cc-ef89-11e9-81b4-2a2ae2dbcce4")
    val SECURITY_SERVICE: UUID = UUID.fromString("0898203a-ef89-11e9-81b4-2a2ae2dbcce4")
    val DEBUG_SERVICE: UUID = UUID.fromString("08982400-ef89-11e9-81b4-2a2ae2dbcce4")

    // --- data service characteristics ---------------------------------------
    val CHAR_PATCH_CONTROL: UUID = UUID.fromString("08981338-ef89-11e9-81b4-2a2ae2dbcce4")
    val CHAR_PATCH_STATUS: UUID = UUID.fromString("08981482-ef89-11e9-81b4-2a2ae2dbcce4")
    val CHAR_EVENT_LOG: UUID = UUID.fromString("08981bee-ef89-11e9-81b4-2a2ae2dbcce4")

    /** 1-minute real-time glucose. The stream the loop actually runs on. */
    val CHAR_GLUCOSE_DATA: UUID = UUID.fromString("0898177a-ef89-11e9-81b4-2a2ae2dbcce4")

    /** 5-minute retained history — the source for gap BACKFILL (Stage 5). */
    val CHAR_HISTORIC_DATA: UUID = UUID.fromString("0898195a-ef89-11e9-81b4-2a2ae2dbcce4")

    val CHAR_CLINICAL_DATA: UUID = UUID.fromString("08981ab8-ef89-11e9-81b4-2a2ae2dbcce4")
    val CHAR_FACTORY_DATA: UUID = UUID.fromString("08981d24-ef89-11e9-81b4-2a2ae2dbcce4")

    // --- security service characteristics -----------------------------------
    val SEC_CHAR_COMMAND_RESPONSE: UUID = UUID.fromString("08982198-ef89-11e9-81b4-2a2ae2dbcce4")
    val SEC_CHAR_CHALLENGE_DATA: UUID = UUID.fromString("089822ce-ef89-11e9-81b4-2a2ae2dbcce4")
    val SEC_CHAR_CERT_DATA: UUID = UUID.fromString("089823fa-ef89-11e9-81b4-2a2ae2dbcce4")

    val CHAR_BLE_LOGIN: UUID = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")

    /** Standard Client Characteristic Configuration descriptor, for enabling notifications. */
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Single-byte opcodes written to [SEC_CHAR_COMMAND_RESPONSE] to drive the security
     * state machine. Values are Abbott's; the names are ours.
     */
    object SecurityCommand {
        const val BEGIN_PAIRING = 1        // fresh pairing: start certificate exchange
        const val REQUEST_CERT = 2         // ask for the patch certificate
        const val CERT_ACCEPTED = 3        // app certificate written and accepted
        const val EPHEMERAL_EXCHANGE = 0x0D // patch cert consumed; request patch ephemeral key
        const val CHALLENGE_SENT = 8       // challenge reply written; expect the 67-byte response
        const val UNKNOWN_9 = 9
        const val RESUME_AUTHORIZED = 14   // post-authorisation, pre-authorised branch
        const val BEGIN_RESUME = 17        // resume with a stored kAuth: skip the certificate dance
    }

    /**
     * Packet-descriptor index for the AES-CCM session cipher — selects the 3 nonce bytes.
     * Complete map, read off Juggluco's `intDecrypt`/`intEncrypt` call sites.
     *
     * ⚠️ [CONTROL_OUT] (0) is for commands we SEND; [PATCH_CONTROL] (1) is the inbound stream on
     * the same characteristic. Using 1 to encrypt a request produces a frame the sensor rejects
     * with GATT status 253 — observed on hardware 2026-09-07.
     */
    object Channel {
        /** Outbound control commands — history/clinical requests. Juggluco: `intEncrypt(ptr, 0, …)`. */
        const val CONTROL_OUT = 0
        const val PATCH_CONTROL = 1
        const val PATCH_STATUS = 2
        const val GLUCOSE = 3          // the 1-minute stream
        const val HISTORIC = 4         // retained 5-minute history — backfill
        const val FAST_DATA = 5
        const val EVENT_LOG = 6
        const val FACTORY_DATA = 7
    }

    // --- challenge framing --------------------------------------------------
    /** Sensor → app challenge: r1(16) ‖ nonce(7). */
    const val CHALLENGE_REQUEST_LEN = 23

    /** Sensor → app response: ciphertext(60) ‖ nonce(7). */
    const val CHALLENGE_RESPONSE_LEN = 67

    const val R_LEN = 16
    const val PIN_LEN = 4
}
