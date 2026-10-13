package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import java.util.UUID

/** Active session with an authenticated read floor and an unknown write floor, as after a first status read. */
internal fun readBaseline(pump: String, key: ByteArray, reboot: Int, read: Long, base: PumpSession.State = PumpSession.State()): PumpSession.State {
    val record = PumpSession.Record(pump, PumpSession.fingerprint(key), UUID.randomUUID().toString(), reboot, read, null)
    return base.copy(records = base.records + record, activeGeneration = record.generation)
}

private val connectionWriters = java.util.WeakHashMap<app.aaps.pump.ypsopump.data.YpsoPumpState, (app.aaps.pump.ypsopump.ble.ConnectionState) -> Unit>()

/** Drives the link-owned connection state of a state object no link was created for. */
internal var app.aaps.pump.ypsopump.data.YpsoPumpState.testConnectionState: app.aaps.pump.ypsopump.ble.ConnectionState
    get() = connectionState
    set(value) = synchronized(connectionWriters) { connectionWriters.getOrPut(this) { claimConnectionWriter() } }(value)
