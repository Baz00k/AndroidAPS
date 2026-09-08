package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes

/**
 * System status characteristic read, SDK index 30 (not a wire opcode).
 * V05.00.52 captures and physical comparisons: see docs/status-protocol.md.
 * CRC and mandatory counter tail must have been validated and stripped first.
 */
class StatusCommand : YpsoCommand(YpsoCommandCodes.GET_SYSTEM_STATUS) {

    var deliveryMode: Int = 0; private set
    var deliveryModeName: String = ""; private set
    var reservoirUnits: Double = 0.0; private set
    // Battery bars (0–5) as reported on the wire. Internal representation only:
    // presentation and framework layers map to percent.
    var batteryBars: Int = 0; private set
    // The wire reports bars, not a measured percentage. No percentage is published.
    val batteryPercent: Int? get() = null
    var basalRate: Double = 0.0; private set
    var activeTbrPercent: Int = 100; private set
    var tbrRemainingMinutes: Int = 0; private set
    var isSuspended: Boolean = false; private set

    override fun encode(): ByteArray = byteArrayOf(0x00)

    override fun decode(data: ByteArray) {
        success = false
        if (data.size != 18) return
        val mode = data[0].toInt() and 0xFF
        val reservoir = data.getUInt32(1)
        val bars = data[5].toInt() and 0xFF
        val basal = data.getUInt32(6)
        val percent = data.getUInt32(10)
        val remaining = data.getUInt32(14)
        // Only physically observed running and stopped states are trusted. Other states
        // remain diagnostics until independently captured; never guess an enum meaning.
        // Reservoir 0xFFFFFFFF is the observed no-cartridge sentinel (rewound/rebooted pump
        // left without cartridge, and identically for an empty cartridge): it must fail closed,
        // never publish as a measurement.
        if (reservoir == 0xFFFFFFFFL) return
        if (mode !in setOf(3, 10) || reservoir > 16000 || bars !in 0..5 || basal > 4000 || percent > 500 || remaining > 1440) return
        if (mode == 3 && basal != 0L) return
        deliveryMode = mode
        deliveryModeName = if (mode == 3) "Stopped" else "Running"
        reservoirUnits = reservoir / 100.0
        batteryBars = bars
        basalRate = basal / 100.0
        activeTbrPercent = percent.toInt()
        tbrRemainingMinutes = remaining.toInt()
        isSuspended = mode == 3
        success = true
    }
}
