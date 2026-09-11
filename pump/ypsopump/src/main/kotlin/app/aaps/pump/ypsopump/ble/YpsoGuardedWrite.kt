package app.aaps.pump.ypsopump.ble

/**
 * Marks the only methods permitted to reach `BluetoothGatt.writeCharacteristic`/`writeDescriptor`.
 * The bytecode guard `VerifyGattWriteOwnership` allows such calls only inside annotated methods of
 * [YpsoBleManager], so a similarly named helper cannot impersonate the dispatch boundary.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class YpsoGuardedWrite
