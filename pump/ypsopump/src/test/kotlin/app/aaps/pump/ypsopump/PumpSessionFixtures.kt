package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import java.util.UUID

/** Active session with an authenticated read floor and an unknown write floor, as after a first status read. */
internal fun readBaseline(pump: String, key: ByteArray, reboot: Int, read: Long, base: PumpSession.State = PumpSession.State()): PumpSession.State {
    val record = PumpSession.Record(pump, PumpSession.fingerprint(key), UUID.randomUUID().toString(), reboot, read, null)
    return base.copy(records = base.records + record, activeGeneration = record.generation)
}
