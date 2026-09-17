package app.aaps.pump.ypsopump.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** One acquisition owns its rows; a disconnect/restart must discard this object. */
internal class YpsoProfileReadback(
    private val sessionGeneration: String,
    private val reboot: Int,
    private val connectionId: String,
    private val startedElapsedMs: Long,
    private val activeBefore: YpsoBasalSchedule.Program,
) {
    companion object {
        /** Target date = u16LE year, month, day; time = hour, minute, second. */
        fun decodeClock(date: ByteArray, time: ByteArray): LocalDateTime? {
            if (date.size != 4 || time.size != 3) return null
            val year = (date[0].toInt() and 255) or ((date[1].toInt() and 255) shl 8)
            if (year !in 2000..2099) return null
            return runCatching {
                LocalDateTime.of(year, date[2].toInt() and 255, date[3].toInt() and 255,
                    time[0].toInt() and 255, time[1].toInt() and 255, time[2].toInt() and 255)
            }.getOrNull()
        }
    }

    private val rows = mutableMapOf<Int, ByteArray>()
    private var invalidated = false

    fun invalidate() {
        invalidated = true
        rows.clear()
    }

    fun add(settingId: Int, body: ByteArray): Boolean {
        if (invalidated || settingId !in 14..61 || rows.containsKey(settingId)) {
            invalidate()
            return false
        }
        rows[settingId] = body.copyOf()
        return true
    }

    /**
     * Bracket the acquisition with active-program and clock observations. The caller must invalidate
     * on profile/clock history events, connection changes, or any failed intermediate operation.
     * A matching before/after program alone cannot rule out an A→B→A switch during acquisition.
     */
    fun finish(
        generation: String,
        currentReboot: Int,
        currentConnectionId: String,
        finishedElapsedMs: Long,
        activeAfterBody: ByteArray,
        pumpLocalTime: LocalDateTime,
        observedAt: Instant,
        zone: ZoneId,
        maxAcquisitionMs: Long,
        maxClockSkew: Duration,
        eventCount: Int,
    ): VerifiedReadback? {
        if (invalidated || eventCount < 0) return null
        invalidated = true
        if (generation != sessionGeneration || currentReboot != reboot || currentConnectionId != connectionId) return null
        if (maxAcquisitionMs <= 0 || finishedElapsedMs - startedElapsedMs !in 0..maxAcquisitionMs) return null
        if (maxClockSkew.isNegative || maxClockSkew.isZero) return null
        if (YpsoBasalSchedule.Program.decode(activeAfterBody) != activeBefore || rows.size != 48) return null
        val offsets = zone.rules.getValidOffsets(pumpLocalTime)
        if (offsets.size != 1) return null
        val pumpInstant = pumpLocalTime.toInstant(offsets.single())
        if (Duration.between(pumpInstant, observedAt).abs() > maxClockSkew) return null
        val a = YpsoBasalSchedule.decode((14..37).map { rows[it] ?: return null }) ?: return null
        val b = YpsoBasalSchedule.decode((38..61).map { rows[it] ?: return null }) ?: return null
        return VerifiedReadback(sessionGeneration, reboot, connectionId, activeBefore, a, b, finishedElapsedMs, zone, eventCount)
    }

    class VerifiedReadback internal constructor(
        val generation: String,
        val reboot: Int,
        val connectionId: String,
        val activeProgram: YpsoBasalSchedule.Program,
        val profileA: YpsoBasalSchedule,
        val profileB: YpsoBasalSchedule,
        val acquiredElapsedMs: Long,
        val zone: ZoneId,
        val eventCount: Int,
    ) {
        val activeSchedule: YpsoBasalSchedule
            get() = if (activeProgram == YpsoBasalSchedule.Program.A) profileA else profileB

        fun isCurrent(currentGeneration: String, currentReboot: Int, currentConnection: String,
                      elapsedMs: Long, currentZone: ZoneId, maxAgeMs: Long): Boolean =
            currentGeneration == generation && currentReboot == reboot && currentConnection == connectionId &&
                currentZone == zone && maxAgeMs > 0 && elapsedMs - acquiredElapsedMs in 0 until maxAgeMs
    }
}
