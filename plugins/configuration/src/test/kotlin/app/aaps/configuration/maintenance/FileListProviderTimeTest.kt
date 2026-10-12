package app.aaps.configuration.maintenance

import app.aaps.core.interfaces.maintenance.PrefMetadata
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.maintenance.ExportFileName
import app.aaps.plugins.configuration.maintenance.FileListProviderImpl
import app.aaps.plugins.configuration.maintenance.PrefsMetadataKeyImpl
import app.aaps.plugins.configuration.maintenance.data.PrefsStatusImpl
import dagger.Lazy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

class FileListProviderTimeTest {

    private val rh = mock<ResourceHelper>().apply {
        whenever(gs(any())).thenAnswer { "string:${it.arguments[0]}" }
        whenever(gs(any(), any<String>())).thenAnswer { "string:${it.arguments[0]}:${it.arguments[1]}" }
        whenever(gq(any(), any(), any<Int>())).thenAnswer { "quantity:${it.arguments[1]}" }
    }
    private val files = FileListProviderImpl(rh, Lazy { mock() }, mock(), mock(), mock(), Lazy { mock() }, mock(), mock())

    private fun at(instant: String, zone: String = "Europe/Warsaw") {
        files.clockProvider = { Clock.fixed(Instant.parse(instant), ZoneId.of(zone)) }
    }

    private fun metadata(createdAt: String): PrefMetadata {
        val entry = PrefMetadata(createdAt, PrefsStatusImpl.OK)
        files.checkMetadata(mapOf(PrefsMetadataKeyImpl.CREATED_AT to entry))
        return entry
    }

    @Test
    fun `warning uses dates in original offset and current zone with strict 60 day boundary`() {
        // Jan 1 -> March 2 = 60 calendar dates; Warsaw is already March 3 at 23:30Z.
        at("2026-03-02T22:30:00Z")
        assertEquals(PrefsStatusImpl.OK, metadata("2026-01-01T23:59:59Z").status)
        at("2026-03-02T23:30:00Z")
        val old = metadata("2026-01-01T23:59:59Z")
        assertEquals(PrefsStatusImpl.WARN, old.status)
        assertEquals("string:${R.string.metadata_warning_old_export}:61", old.info)
        assertEquals(PrefsStatusImpl.OK, metadata("2026-01-02T00:59:59+01:00").status) // Same instant, different export date.
        assertEquals(PrefsStatusImpl.OK, metadata("2027-01-01T00:00Z").status)
        at("2024-04-29T12:00:00Z", "UTC")
        assertEquals(PrefsStatusImpl.OK, metadata("2024-02-29T12:00Z").status)
        at("2024-04-30T12:00:00Z", "UTC")
        assertEquals(PrefsStatusImpl.WARN, metadata("2024-02-29T12:00Z").status)
    }

    @Test
    fun `invalid metadata warns while invalid age label throws`() {
        at("2026-03-29T01:00:00Z")
        listOf("invalid", "T12:30Z", "2025-02-29T00:00Z", "2026-03-29T02:30").forEach {
            val entry = metadata(it)
            assertEquals(PrefsStatusImpl.WARN, entry.status)
            assertEquals("string:${R.string.metadata_warning_date_format}", entry.info)
            assertThrows(DateTimeException::class.java) { files.formatExportedAgo(it) }
        }
    }

    @Test
    fun `age label preserves elapsed hour truncation and future fallback`() {
        at("2026-01-01T12:00:00Z", "UTC")
        assertEquals("string:${R.string.exported_less_than_hour_ago}", files.formatExportedAgo("2026-01-01T11:00:00.001Z"))
        assertEquals("string:${R.string.exported_ago}:quantity:1", files.formatExportedAgo("2026-01-01T11:00:00Z"))
        assertEquals("string:${R.string.exported_ago}:quantity:23", files.formatExportedAgo("2025-12-31T12:00:00.001Z"))
        assertEquals("string:${R.string.exported_ago}:quantity:1", files.formatExportedAgo("2025-12-31T12:00:00Z"))
        assertEquals("string:${R.string.exported_less_than_hour_ago}", files.formatExportedAgo("2026-01-01T12:59:59Z"))
        assertEquals("string:${R.string.exported_at}:2026-01-01", files.formatExportedAgo("2026-01-01T13:00:00Z"))
        at("2026-03-02T12:00:00Z", "UTC")
        assertEquals("string:${R.string.exported_at}:2026-01-01", files.formatExportedAgo("2026-01-01T12:00Z"))
        assertEquals("string:${R.string.exported_ago}:quantity:59", files.formatExportedAgo("2026-01-01T12:00:00.001Z"))
    }

    @Test
    fun `spring and autumn labels preserve calendar days in source zone versus elapsed hours`() {
        at("2026-03-29T10:00:00Z")
        assertEquals("string:${R.string.exported_ago}:quantity:23", files.formatExportedAgo("2026-03-28T12:00"))
        at("2026-10-25T11:00:00Z")
        assertEquals("string:${R.string.exported_ago}:quantity:1", files.formatExportedAgo("2026-10-24T12:00")) // 25 elapsed hours, one local day.
        assertEquals("string:${R.string.exported_ago}:quantity:1", files.formatExportedAgo("2026-10-24T10:00Z")) // Explicit UTC: one whole day too.
        at("2026-10-25T02:30:00Z")
        assertEquals("string:${R.string.exported_ago}:quantity:2", files.formatExportedAgo("2026-10-25T02:30")) // First overlap occurrence.
        assertEquals("string:${R.string.exported_ago}:quantity:1", files.formatExportedAgo("2026-10-25T02:30+01:00"))
    }

    @Test
    fun `export names retain local seconds format at fixed instants`() {
        mapOf(
            "2026-03-29T00:59:59Z" to "2026-03-29_015959",
            "2026-03-29T01:00:00Z" to "2026-03-29_030000",
            "2026-10-25T00:30:00Z" to "2026-10-25_023000",
            "2026-10-25T01:30:00Z" to "2026-10-25_023000",
            "2024-02-29T22:59:59.999Z" to "2024-02-29_235959"
        ).forEach { (instant, expected) ->
            at(instant)
            assertEquals(expected, ExportFileName.timestamp(files.clockProvider()))
        }
        at("2024-02-29T22:59:59.999Z", "UTC")
        assertEquals("2024-02-29_225959", ExportFileName.timestamp(files.clockProvider()))
    }
}
