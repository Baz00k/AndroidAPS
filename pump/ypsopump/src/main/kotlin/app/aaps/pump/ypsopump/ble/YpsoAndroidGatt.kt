package app.aaps.pump.ypsopump.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import app.aaps.pump.ypsopump.provisioning.PumpIdentity
import java.util.UUID

/** [YpsoGattAdapter] over Android's Bluetooth stack. */
internal class YpsoAndroidGattAdapter(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : YpsoGattAdapter {

    @SuppressLint("MissingPermission")
    override fun bondedPump(mac: String, configuredSerial: String?): YpsoGattAdapter.BondedPump {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) throw YpsoBondException(false, "Bluetooth unavailable")
        val device = adapter.getRemoteDevice(mac)
        if (device.bondState != BluetoothDevice.BOND_BONDED) throw YpsoBondException(false, "existing Bluetooth bond required")
        var observed: String? = null
        if (configuredSerial != null && PumpIdentity.isSupportedDeviceName(device.name)) {
            if (!PumpIdentity.deviceNameMatches(configuredSerial, device.name)) {
                throw YpsoBondException(true, "Bonded pump name does not match configured serial")
            }
            observed = configuredSerial
        }
        return object : YpsoGattAdapter.BondedPump {
            override val observedSerial: String? = observed

            override fun connect(events: YpsoGattEvents, authorizer: YpsoWriteAuthorizer): YpsoGattConnection? {
                val connection = YpsoAndroidGattConnection(events, authorizer, sdkInt)
                // The API 37 replacement changes callback dispatch. Retain the verified transport
                // on supported Android versions until that path has been exercised with a test pump.
                @Suppress("DEPRECATION")
                val gatt = device.connectGatt(context, false, connection.callback, BluetoothDevice.TRANSPORT_LE) ?: return null
                connection.attach(gatt)
                return connection
            }
        }
    }
}

/**
 * One Android GATT client. Its callback object is created for this connection alone, so every event
 * it forwards names this connection; the link decides whether that is still the pump's owner.
 * The guarded write methods are the only code allowed to reach Android's GATT write calls.
 */
@SuppressLint("MissingPermission")
internal class YpsoAndroidGattConnection(
    private val events: YpsoGattEvents,
    private val authorizer: YpsoWriteAuthorizer,
    private val sdkInt: Int,
) : YpsoGattConnection {
    @Volatile private var gatt: BluetoothGatt? = null

    internal fun attach(gatt: BluetoothGatt) {
        this.gatt = gatt
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        YpsoGattLayout.observedService(uuid)?.let { return g.getService(it)?.getCharacteristic(uuid) }
        for (service in g.services) service.getCharacteristic(uuid)?.let { return it }
        return null
    }

    override fun properties(uuid: UUID): Int? = characteristic(uuid)?.properties

    override fun customCharacteristics(): Map<UUID, List<UUID>> =
        gatt?.services.orEmpty().mapNotNull { service ->
            service.characteristics
                .map { it.uuid }
                .filter { it.toString().startsWith("669a0c20", ignoreCase = true) }
                .takeIf { it.isNotEmpty() }
                ?.let { service.uuid to it }
        }.toMap()

    override fun discoverServices(): Boolean = gatt?.discoverServices() == true

    override fun read(uuid: UUID): Boolean {
        val target = characteristic(uuid) ?: return false
        return gatt?.readCharacteristic(target) == true
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi") // sdkInt is the injectable Build.VERSION.SDK_INT adapter seam.
    @YpsoGuardedWrite
    override fun writeCharacteristic(uuid: UUID, value: ByteArray, purpose: YpsoRemoteWrite): Boolean {
        // Authorize and send one private copy, so the caller cannot change the bytes in between.
        val bytes = value.copyOf()
        val g = gatt ?: return false
        val target = characteristic(uuid) ?: return false
        if (!authorizer.allows(this, purpose, target.uuid, null, bytes)) return false
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(target, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            target.value = bytes
            g.writeCharacteristic(target)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi") // Same runtime SDK guard as characteristic dispatch.
    @YpsoGuardedWrite
    override fun writeDescriptor(characteristic: UUID, descriptor: UUID, value: ByteArray, purpose: YpsoRemoteWrite): Boolean {
        val bytes = value.copyOf()
        val g = gatt ?: return false
        val target = characteristic(characteristic)?.getDescriptor(descriptor) ?: return false
        if (!authorizer.allows(this, purpose, target.characteristic?.uuid, target.uuid, bytes)) return false
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(target, bytes) == BluetoothStatusCodes.SUCCESS
        } else {
            target.value = bytes
            g.writeDescriptor(target)
        }
    }

    override fun enableNotification(characteristic: UUID, descriptor: UUID): Boolean {
        val g = gatt ?: return false
        val target = characteristic(characteristic) ?: return false
        if (target.getDescriptor(descriptor) == null) return false
        return g.setCharacteristicNotification(target, true)
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun close() {
        gatt?.close()
    }

    internal val callback = object : BluetoothGattCallback() {
        private val connection get() = this@YpsoAndroidGattConnection

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> events.onConnectionStateChange(connection, true, status)
                BluetoothProfile.STATE_DISCONNECTED -> events.onConnectionStateChange(connection, false, status)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = events.onServicesDiscovered(connection, status)

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            events.onCharacteristicWrite(connection, ch.uuid, status)

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            // Receipt order, taken before any lock, is compared with the moment a dispatch was armed.
            val receivedAt = System.nanoTime()
            events.onCharacteristicChanged(connection, ch.uuid, value, receivedAt)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            events.onCharacteristicRead(connection, ch.uuid, value.copyOf(), status)

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            events.onCharacteristicRead(connection, ch.uuid, ch.value?.copyOf(), status)

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            events.onDescriptorWrite(connection, descriptor.uuid, status)
    }
}
