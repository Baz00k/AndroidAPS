package app.aaps.pump.ypsopump

import java.util.UUID

/**
 * YpsoPump BLE constants: UUIDs, command indices, error codes.
 * Derived from reverse engineering of CamAPS FX v1.4(190).111.
 */
object YpsoPumpConst {

    // Standard BLE CCCD for notifications
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // -- Crypto Constants --
    const val KEY_SIZE = 32           // Curve25519 / XChaCha20 key size
    const val NONCE_SIZE = 24         // XChaCha20 extended nonce
    const val TAG_SIZE = 16           // Poly1305 auth tag
    const val COUNTER_DATA_SIZE = 12  // rebootCounter(4) + writeCounter(8)
}
