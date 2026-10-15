package app.aaps.core.interfaces.rx.weardata

/**
 * Re-evaluate on the watch as well as the phone: a disconnected watch cannot receive expiry.
 * The phone already rejects future-dated readings, so only age is checked here; a watch clock
 * slightly behind the phone's must not hide a current trend.
 */
fun EventData.SingleBg.forGlucoseDisplay(now: Long): EventData.SingleBg =
    if (timeStamp > 0L && now - timeStamp < 9 * 60_000L) this
    else copy(slopeArrow = "", delta = "--", deltaDetailed = "--", avgDelta = "--", avgDeltaDetailed = "--", deltaMgdl = null, avgDeltaMgdl = null)
