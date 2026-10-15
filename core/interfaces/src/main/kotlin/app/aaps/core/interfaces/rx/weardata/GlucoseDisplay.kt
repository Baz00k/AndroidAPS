package app.aaps.core.interfaces.rx.weardata

/** Re-evaluate on the watch as well as the phone: a disconnected watch cannot receive expiry. */
fun EventData.SingleBg.forGlucoseDisplay(now: Long): EventData.SingleBg =
    if (timeStamp > 0L && timeStamp <= now && timeStamp > now - 9 * 60_000L) this
    else copy(slopeArrow = "", delta = "--", deltaDetailed = "--", avgDelta = "--", avgDeltaDetailed = "--", deltaMgdl = null, avgDeltaMgdl = null)
