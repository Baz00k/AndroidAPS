package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * START_STOP_BOLUS (index 27) — deliver or cancel a bolus. Written to CHAR_BOLUS_START_STOP
 * (…fcbee18b…); the caller appends CRC16 before encryption.
 *
 * Request payload — 13 bytes LE (validated against mylife / firmware V05.02.03 references and
 * confirmed on target firmware V05.00.52 with a 0.1-U standard bolus):
 *   total(u32, units×100) || duration(u32, minutes) || comboImmediate(u32, units×100) || type(u8)
 *   type: 1 = standard (duration==0, comboImmediate==0), 2 = extended/combo (duration>0)
 *   total must be exactly representable in 0.1-U steps and lie in 0.1..30.0 U.
 *   Invalid requests are rejected rather than rounded or clamped.
 *
 * To CANCEL, write an all-zero payload with only [12]=type (1=immediate, 2=extended): see [cancelPayload].
 *
 * [decode] parses the CHAR_BOLUS_STATUS read (CRC already stripped): the "fast" (immediate) block:
 *   fastStatus(u8) | fastSeq(u32) | fastInjected(u32 /100) | fastTotal(u32 /100).
 */
class BolusCommand(
    private val totalUnits: Double,
    private val durationMinutes: Int = 0,
    private val immediateUnits: Double = 0.0
) : YpsoCommand(YpsoCommandCodes.START_STOP_BOLUS) {

    // Immediate ("fast") block.
    var fastSequence: Long = 0; private set
    var deliveredUnits: Double = 0.0; private set
    var totalProgrammedUnits: Double = 0.0; private set
    var bolusStatusCode: Int = 0; private set
    // Extended ("slow") block — populated for extended/combo boluses.
    var extendedSequence: Long = 0; private set
    var extendedStatusCode: Int = 0; private set
    var extendedDeliveredUnits: Double = 0.0; private set
    var extendedTotalUnits: Double = 0.0; private set
    /** Combination immediate sub-block injected/total (@26/@30); zero outside a combination bolus. */
    var comboImmediateDeliveredUnits: Double = 0.0; private set
    var comboImmediateTotalUnits: Double = 0.0; private set
    var extendedMinutesElapsed: Int = 0; private set
    var extendedMinutesTotal: Int = 0; private set

    val isImmediate: Boolean get() = durationMinutes == 0

    // Target-observed bolus activity: 0 = idle, 1 = immediate delivering, 3 = mixed/extended
    // delivering (observed active on the bench pump). Idle alone does not distinguish
    // completed from cancelled; codes 4 ("completed") and distinct cancelled/completed enums
    // are not established by target captures and must not be claimed.
    val isDelivering: Boolean get() = bolusStatusCode == STATUS_DELIVERING || extendedStatusCode in setOf(STATUS_DELIVERING, STATUS_MIXED_DELIVERING)
    // Terminal codes are not established by target captures; idle alone proves neither outcome.
    val isCompleted: Boolean get() = false
    val isCancelled: Boolean get() = false

    override fun encode(): ByteArray {
        val totalScaled = exactCentiUnits(totalUnits, "totalUnits", allowZero = false)
        val immediateScaled = exactCentiUnits(immediateUnits, "immediateUnits", allowZero = true)
        require(durationMinutes in 0..MAX_DURATION_MINUTES) { "durationMinutes is outside 0..$MAX_DURATION_MINUTES" }
        require(immediateScaled <= totalScaled) { "immediateUnits exceeds totalUnits" }
        if (isImmediate) require(immediateScaled == 0L) { "standard bolus must not encode a combination immediate amount" }
        else require(durationMinutes > 0) { "extended bolus requires a positive duration" }
        val type: Byte = if (isImmediate) TYPE_IMMEDIATE else TYPE_EXTENDED
        return ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(totalScaled.toInt()).putInt(durationMinutes).putInt(immediateScaled.toInt()).put(type)
            .array()
    }

    override fun decode(data: ByteArray) {
        success = false
        if (data.size == 42) {
            if ((data[0].toInt() and 0xFF) !in setOf(STATUS_IDLE, STATUS_DELIVERING) ||
                (data[13].toInt() and 0xFF) !in setOf(STATUS_IDLE, STATUS_DELIVERING, STATUS_MIXED_DELIVERING)) return
            for ((injected, total) in listOf(5 to 9, 18 to 22, 26 to 30)) {
                if (data.getUInt32(total) > MAX_BOLUS_X100 || data.getUInt32(injected) > data.getUInt32(total)) return
            }
            if (data.getUInt32(38) > 1440 || data.getUInt32(34) > data.getUInt32(38)) return
            // Immediate ("fast") block: status u8 @0 | seq u32 @1 | injected u32/100 @5 | total u32/100 @9
            bolusStatusCode = data[0].toInt() and 0xFF
            fastSequence = data.getUInt32(1)
            deliveredUnits = data.getUInt32(5) / 100.0
            totalProgrammedUnits = data.getUInt32(9) / 100.0
            // Extended ("slow") block (validated vs a real 2h/13U extended bolus):
            // status u8 @13 | seq u32 @14 | injected u32/100 @18 | total u32/100 @22 |
            // (fast-within-combo inj @26 / tot @30) | minutesElapsed u32 @34 | minutesTotal u32 @38
            if (data.size >= 42) {
                extendedStatusCode = data[13].toInt() and 0xFF
                extendedSequence = data.getUInt32(14)
                extendedDeliveredUnits = data.getUInt32(18) / 100.0
                extendedTotalUnits = data.getUInt32(22) / 100.0
                comboImmediateDeliveredUnits = data.getUInt32(26) / 100.0
                comboImmediateTotalUnits = data.getUInt32(30) / 100.0
                extendedMinutesElapsed = data.getUInt32(34).toInt()
                extendedMinutesTotal = data.getUInt32(38).toInt()
            }
            success = true
        } else {
            errorCode = if (data.isNotEmpty()) data[0].toInt() and 0xFF else -1
            success = false
        }
    }

    companion object {
        const val MIN_BOLUS_X100 = 10
        const val BOLUS_STEP_X100 = 10
        const val MAX_BOLUS_X100 = 3000
        const val MAX_DURATION_MINUTES = 1440
        const val TYPE_IMMEDIATE: Byte = 1
        const val TYPE_EXTENDED: Byte = 2

        // Bolus status-byte codes observed on V05.00.52 bench pump (CHAR_BOLUS_STATUS @0/@13).
        const val STATUS_IDLE = 0
        const val STATUS_DELIVERING = 1
        const val STATUS_MIXED_DELIVERING = 3

        /** All-zero 13-byte payload with the type byte set — cancels the running fast/extended bolus. */
        fun cancelPayload(extended: Boolean): ByteArray =
            ByteArray(13).also { it[12] = if (extended) TYPE_EXTENDED else TYPE_IMMEDIATE }

        private fun exactCentiUnits(units: Double, name: String, allowZero: Boolean): Long {
            require(units.isFinite()) { "$name must be finite" }
            val scaled = units * 100.0
            val rounded = scaled.roundToLong()
            require(kotlin.math.abs(scaled - rounded) <= REPRESENTATION_EPSILON) {
                "$name is not exactly representable in centi-units"
            }
            val minimum = if (allowZero) 0L else MIN_BOLUS_X100.toLong()
            require(rounded in minimum..MAX_BOLUS_X100.toLong()) { "$name is outside the supported range" }
            require(rounded == 0L || rounded % BOLUS_STEP_X100 == 0L) {
                "$name is not exactly representable in 0.1 U increments"
            }
            return rounded
        }

        private const val REPRESENTATION_EPSILON = 1e-7
    }
}
