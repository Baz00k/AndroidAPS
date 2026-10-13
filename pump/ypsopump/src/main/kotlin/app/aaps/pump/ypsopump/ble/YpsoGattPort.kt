package app.aaps.pump.ypsopump.ble

import java.util.UUID

/**
 * One GATT connection to the pump. It reports what the platform reports and decides nothing: which
 * connection owns the pump, and whether a callback still counts, is decided by [YpsoPumpLink].
 */
internal interface YpsoGattConnection {
    /** Properties of [uuid] in the service the pump exposes it in, or null when it is absent. */
    fun properties(uuid: UUID): Int?

    /** Custom characteristics per service, for the discovery log. */
    fun customCharacteristics(): Map<UUID, List<UUID>>

    fun discoverServices(): Boolean
    fun read(uuid: UUID): Boolean

    /** Dispatches [value] to [uuid]. Implementations refuse it unless their [YpsoWriteAuthorizer] admits it. */
    fun writeCharacteristic(uuid: UUID, value: ByteArray, purpose: YpsoRemoteWrite): Boolean

    /** Same contract as [writeCharacteristic], for a descriptor of [characteristic]. */
    fun writeDescriptor(characteristic: UUID, descriptor: UUID, value: ByteArray, purpose: YpsoRemoteWrite): Boolean

    /** Enables local delivery of [characteristic] changes; false when it or its [descriptor] is absent. */
    fun enableNotification(characteristic: UUID, descriptor: UUID): Boolean

    fun disconnect()
    fun close()
}

/** Platform callbacks, each tagged with the connection that produced it. */
internal interface YpsoGattEvents {
    fun onConnectionStateChange(connection: YpsoGattConnection, connected: Boolean, status: Int)
    fun onServicesDiscovered(connection: YpsoGattConnection, status: Int)
    fun onCharacteristicWrite(connection: YpsoGattConnection, uuid: UUID, status: Int)
    fun onCharacteristicRead(connection: YpsoGattConnection, uuid: UUID, value: ByteArray?, status: Int)
    fun onDescriptorWrite(connection: YpsoGattConnection, uuid: UUID, status: Int)

    /** [receivedAtNanos] is [System.nanoTime] taken on receipt, before any lock. */
    fun onCharacteristicChanged(connection: YpsoGattConnection, uuid: UUID, value: ByteArray, receivedAtNanos: Long)
}

/** The final check before bytes leave the phone. [descriptor] is null for a characteristic write. */
internal fun interface YpsoWriteAuthorizer {
    fun allows(connection: YpsoGattConnection, purpose: YpsoRemoteWrite, characteristic: UUID?, descriptor: UUID?, value: ByteArray): Boolean
}

/** Opens connections to an already bonded pump. */
internal interface YpsoGattAdapter {
    /**
     * Checks that Bluetooth is on and [mac] is bonded, and, when the bonded name identifies a pump, that
     * it is [configuredSerial]. Throws [YpsoBondException] otherwise.
     */
    fun bondedPump(mac: String, configuredSerial: String?): BondedPump

    interface BondedPump {
        /** [configuredSerial] when the bonded name independently confirmed it, otherwise null. */
        val observedSerial: String?

        /** Starts connecting; null or an exception when the platform refused. */
        fun connect(events: YpsoGattEvents, authorizer: YpsoWriteAuthorizer): YpsoGattConnection?
    }
}

internal class YpsoBondException(val identityMismatch: Boolean, message: String) : IllegalStateException(message)

/** Pump GATT layout used by the driver. Selector and therapy destinations are in [YpsoWritePolicy]. */
internal object YpsoGattLayout {
    val SERVICE_CONTROL: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0000")
    val SERVICE_EXTREAD: UUID = UUID.fromString("fb349b5f-8000-0080-0010-0000feda0002")
    val CHAR_AUTH: UUID = YpsoWritePolicy.AUTH_UUID
    val CHAR_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee48b7bc5")
    val CHAR_EXTREAD: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
    val CHAR_SETTING_VALUE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb4147bc5")
    val CHAR_SYSTEM_DATE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbedc3b7bc5")
    val CHAR_SYSTEM_TIME: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbedd3b7bc5")
    val CHAR_EVENT_COUNT: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecb3b7bc5")
    val CHAR_EVENT_VALUE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecd3b7bc5")
    val CHAR_BOLUS_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee28b7bc5")
    val CHAR_CONTROL_VERSION: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee08b7bc5")

    /** The only service a characteristic is accepted from, or null when any service may carry it. */
    fun observedService(uuid: UUID): UUID? = when (uuid) {
        CHAR_STATUS, CHAR_BOLUS_STATUS, CHAR_CONTROL_VERSION -> SERVICE_CONTROL
        CHAR_EXTREAD -> SERVICE_EXTREAD
        else -> null
    }
}
