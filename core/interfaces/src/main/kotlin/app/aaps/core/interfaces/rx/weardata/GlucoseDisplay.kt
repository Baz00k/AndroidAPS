package app.aaps.core.interfaces.rx.weardata

/** Numeric transport placeholders must never become a custom-watchface glucose value. */
fun EventData.SingleBg.glucoseValueOrNull(): Double? =
    sgv.takeIf { timeStamp > 0L && it.isFinite() && it >= 39 && sgvString != "---" && sgvString != "--" }

/** Re-evaluate on the watch as well as the phone: a disconnected watch cannot receive expiry. */
fun EventData.SingleBg.forGlucoseDisplay(now: Long): EventData.SingleBg =
    if (glucoseValueOrNull() != null && timeStamp <= now && timeStamp > now - 9 * 60_000L) this
    else copy(slopeArrow = "", delta = "--", deltaDetailed = "--", avgDelta = "--", avgDeltaDetailed = "--", deltaMgdl = null, avgDeltaMgdl = null)
