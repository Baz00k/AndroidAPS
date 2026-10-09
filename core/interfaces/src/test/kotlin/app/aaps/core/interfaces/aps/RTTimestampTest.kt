package app.aaps.core.interfaces.aps

import app.aaps.core.data.time.IsoTimestamp
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class RTTimestampTest {

    private fun inZone(zone: String, block: () -> Unit) {
        val previous = TimeZone.getDefault()
        val previousJoda = DateTimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            DateTimeZone.setDefault(DateTimeZone.forID(zone))
            block()
        } finally {
            TimeZone.setDefault(previous)
            DateTimeZone.setDefault(previousJoda)
        }
    }

    @Test
    fun `UTC offsets and offsetless inputs retain millisecond instants`() = inZone("Europe/Warsaw") {
        val fixtures = mapOf(
            "2024-02-29T12:34:56.123Z" to "2024-02-29T12:34:56.123Z",
            "2024-02-29T14:34:56.123+02:00" to "2024-02-29T12:34:56.123Z",
            "2024-02-29T07:34:56.123-0500" to "2024-02-29T12:34:56.123Z",
            "2024-02-29T13:34:56.123" to "2024-02-29T12:34:56.123Z",
            "2026-03-29T01:59:59.999" to "2026-03-29T00:59:59.999Z",
            "2026-03-29T03:00:00" to "2026-03-29T01:00:00Z",
            "2026-10-25T02:30:00" to "2026-10-25T00:30:00Z", // First occurrence, UTC+2.
            "2026-10-25T02:30:00+01:00" to "2026-10-25T01:30:00Z",
            "2026-03-29T02:30:00+01:00" to "2026-03-29T01:30:00Z", // An explicit offset is authoritative.
            "2026-01-01T00:00:00.123456789Z" to "2026-01-01T00:00:00.123Z",
            "1969-12-31T23:59:59.9999Z" to "1969-12-31T23:59:59.999Z"
        )
        fixtures.forEach { (input, expected) ->
            assertEquals(Instant.parse(expected).toEpochMilli(), RT.TimestampToIsoSerializer.fromISODateString(input), input)
        }
    }

    @Test
    fun `fixed UTC zone does not apply Warsaw offsets`() = inZone("UTC") {
        assertEquals(1774751400000L, RT.TimestampToIsoSerializer.fromISODateString("2026-03-29T02:30:00"))
        assertEquals(1792895400000L, RT.TimestampToIsoSerializer.fromISODateString("2026-10-25T02:30:00"))
    }

    @Test
    fun `invalid dates and offsetless Warsaw gap still throw`() = inZone("Europe/Warsaw") {
        listOf("", "not a date", "2025-02-29T12:00Z", "2026-03-29T02:30", "2026-01-01T24:00Z", "2026-01-01T00:00Zjunk", "2026-01-01Z", "2026-W54-1", "2025-W53-1", "2026-366", "2026-01-01T00:00+24:00", "2026-01-01T00:00+01:60", "2026-01-01T00:00:00.1234567890Z")
            .forEach { input -> assertThrows(DateTimeException::class.java, { RT.TimestampToIsoSerializer.fromISODateString(input) }, input) }
        assertThrows(DateTimeException::class.java) {
            RT.deserialize("""{"runningDynamicIsf":false,"deliverAt":"invalid"}""")
        }
    }

    @Test
    fun `persisted APS fixture retains delivery time decisions and UTC output`() = inZone("Europe/Warsaw") {
        val fixture = """{"algorithm":"SMB","runningDynamicIsf":false,"timestamp":"2026-10-25T02:30:00.123+02:00","deliverAt":"2026-10-25T02:30:30.456+02:00","units":0.25,"rate":1.2,"duration":30,"IOB":0.75,"reason":"fixture","unknownFutureField":true}"""
        val result = RT.deserialize(fixture)
        assertEquals(1792888200123L, result.timestamp)
        assertEquals(1792888230456L, result.deliverAt)
        assertEquals(0.25, result.units)
        assertEquals(1.2, result.rate)
        assertEquals(30, result.duration)
        assertEquals(0.75, result.IOB)
        val encoded = result.serialize()
        org.junit.jupiter.api.Assertions.assertTrue(encoded.contains("\"timestamp\":\"2026-10-25T00:30:00.123Z\""))
        org.junit.jupiter.api.Assertions.assertTrue(encoded.contains("\"deliverAt\":\"2026-10-25T00:30:30.456Z\""))
        val restored = RT.deserialize(encoded)
        assertEquals(result.timestamp, restored.timestamp)
        assertEquals(result.deliverAt, restored.deliverAt)
        assertEquals(result.units, restored.units)
        assertEquals(result.reason.toString(), restored.reason.toString())
        assertEquals("1970-01-01T00:00:00.000Z", RT.TimestampToIsoSerializer.toISOString(0))
        assertEquals(null, RT.deserialize("""{"runningDynamicIsf":false,"timestamp":null,"deliverAt":null}""").deliverAt)
    }

    @Test
    fun `reduced ISO forms remain accepted without a production Joda dependency`() = inZone("Europe/Warsaw") {
        val fixtures = mapOf(
            "2026" to "2025-12-31T23:00:00Z",
            "2026-03" to "2026-02-28T23:00:00Z",
            "2026-088" to "2026-03-28T23:00:00Z",
            "2026-W13-1" to "2026-03-22T23:00:00Z",
            "2026-W13" to "2026-03-22T23:00:00Z",
            "2026-01-01" to "2025-12-31T23:00:00Z",
            "2026-01-01T" to "2025-12-31T23:00:00Z",
            "2026-3-9t1:2:3,12345" to "2026-03-09T00:02:03.123Z",
            "2026-01-01T12.5Z" to "2026-01-01T12:30:00Z",
            "2026-01-01T12.123456789Z" to "2026-01-01T12:07:24.443Z",
            "2026-01-01T12:30.5Z" to "2026-01-01T12:30:30Z",
            "2026-01-01TZ" to "2026-01-01T00:00:00Z",
            "T12:30Z" to "1970-01-01T12:30:00Z",
            "2026-01-01T00:00+01:02:03.456" to "2025-12-31T22:57:56.544Z",
            "2026-01-01T00:00+010203" to "2025-12-31T22:57:57Z",
            "2026-01-01T00:00+19:00" to "2025-12-31T05:00:00Z"
        )
        fixtures.forEach { (input, expected) ->
            val millis = Instant.parse(expected).toEpochMilli()
            assertEquals(millis, RT.TimestampToIsoSerializer.fromISODateString(input), input)
            assertEquals(millis, DateTime.parse(input, ISODateTimeFormat.dateTimeParser()).millis, input)
        }
        assertEquals(1, IsoTimestamp.parse("2026-03-28T12:00", ZoneId.of("Europe/Warsaw")).daysUntil(Instant.parse("2026-03-29T10:00:00Z")))
    }

    @Test
    fun `fractional offsets preserve compact and separated grammars through APS fields`() {
        // 12:30 minus 01:00:00.123 = 11:29:59.877 UTC. Short fractions
        // are decimal fractions of a second, and a negative offset is added.
        val accepted = mapOf(
            "+010000123" to "2026-10-09T11:29:59.877Z",
            "+01:00:00.123" to "2026-10-09T11:29:59.877Z",
            "+01:00:00,123" to "2026-10-09T11:29:59.877Z",
            "+0100001" to "2026-10-09T11:29:59.900Z",
            "+01000012" to "2026-10-09T11:29:59.880Z",
            "-010000123" to "2026-10-09T13:30:00.123Z",
            "+000000001" to "2026-10-09T12:29:59.999Z"
        )
        accepted.forEach { (offset, expected) ->
            val input = "2026-10-09T12:30:00$offset"
            val millis = Instant.parse(expected).toEpochMilli()
            val result = RT.deserialize("""{"runningDynamicIsf":false,"timestamp":"$input","deliverAt":"$input"}""")
            assertEquals(millis, result.timestamp, input)
            assertEquals(millis, result.deliverAt, input)
            assertEquals(millis, DateTime.parse(input, ISODateTimeFormat.dateTimeParser()).millis, input)
        }
        listOf("+010000.123", "+010000,123", "+01:00:00123", "+0100001234", "+01:00:00.1234", "+0100:00.123", "+01:0000.123")
            .forEach { offset ->
                val input = "2026-10-09T12:30:00$offset"
                assertThrows(DateTimeException::class.java, { RT.TimestampToIsoSerializer.fromISODateString(input) }, input)
                assertThrows(IllegalArgumentException::class.java, { DateTime.parse(input, ISODateTimeFormat.dateTimeParser()) }, input)
            }
    }

    @Test
    fun `calendar age calculations match original at DST and offset boundaries`() = inZone("Europe/Warsaw") {
        listOf(
            Triple("2026-03-28T12:00", "2026-03-29T10:00:00Z", 1), // 23 hours, one local day.
            Triple("2026-03-28T12:00Z", "2026-03-29T10:00:00Z", 0), // 22 hours in a fixed zone.
            Triple("2026-10-24T12:00", "2026-10-25T11:00:00Z", 1), // 25 hours, one local day.
            Triple("2026-10-25T02:30", "2026-10-26T01:30:00Z", 1),
            Triple("2026-03-29T03:00", "2026-03-28T02:00:00Z", -1),
            Triple("2024-02-29T12:00Z", "2024-03-01T12:00:00Z", 1)
        ).forEach { (input, end, expected) ->
            val instant = Instant.parse(end)
            assertEquals(expected, IsoTimestamp.parse(input).daysUntil(instant), "$input to $end")
            assertEquals(expected, org.joda.time.Days.daysBetween(DateTime.parse(input), DateTime(instant.toEpochMilli())).days, "$input to $end")
        }
    }
}
