package app.aaps.plugins.configuration.maintenance

import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object ExportFileName {

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    fun timestamp(clock: Clock = Clock.systemDefaultZone()): String = LocalDateTime.now(clock).format(timestampFormat)
}
