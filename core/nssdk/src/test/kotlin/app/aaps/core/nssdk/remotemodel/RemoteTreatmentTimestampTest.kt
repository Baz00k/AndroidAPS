package app.aaps.core.nssdk.remotemodel

import com.google.gson.Gson
import app.aaps.core.nssdk.mapper.toNSTreatment
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryBasal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.TimeZone

class RemoteTreatmentTimestampTest {

    private fun timestamp(json: String) = Gson().fromJson(json, RemoteTreatment::class.java).timestamp()

    @Test
    fun `numeric field precedence and units are unchanged even for zero or seconds-sized values`() {
        assertEquals(123L, timestamp("""{"date":123,"mills":456,"timestamp":789,"created_at":"invalid"}"""))
        assertEquals(0L, timestamp("""{"date":0,"mills":456}"""))
        assertEquals(456L, timestamp("""{"mills":456,"timestamp":789,"created_at":"invalid"}"""))
        assertEquals(789L, timestamp("""{"timestamp":789,"created_at":"invalid"}"""))
        assertEquals(1525383610L, timestamp("""{"date":1525383610}""")) // No automatic seconds-to-ms conversion.
        assertEquals(-1L, timestamp("""{"date":-1}"""))
        assertEquals(0L, timestamp("{}"))
        assertEquals(0L, timestamp("""{"created_at":"invalid"}"""))
    }

    @Test
    fun `created at retains offsets DST overlap and invalid gap fallback`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
            val fixtures = mapOf(
                "2026-03-29T01:30:00.123Z" to 1774747800123L,
                "2026-03-29T03:30:00.123+02:00" to 1774747800123L,
                "2026-03-29T03:30:00.123" to 1774747800123L,
                "2026-10-25T02:30:00" to 1792888200000L,
                "2026-10-25T02:30:00+01:00" to 1792891800000L,
                "2026-03-29T02:30:00" to 0L,
                "2025-02-29T00:00Z" to 0L
            )
            fixtures.forEach { (input, expected) -> assertEquals(expected, timestamp("""{"created_at":"$input"}"""), input) }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `fractional offset grammar keeps created at fallback and treatment accounting`() {
        val accepted = mapOf(
            "+010000123" to 1791545399877L,
            "+01:00:00.123" to 1791545399877L,
            "+01:00:00,123" to 1791545399877L,
            "+0100001" to 1791545399900L,
            "+01000012" to 1791545399880L,
            "-010000123" to 1791552600123L
        )
        accepted.forEach { (offset, expected) ->
            val createdAt = "2026-10-09T12:30:00$offset"
            assertEquals(expected, timestamp("""{"created_at":"$createdAt"}"""), createdAt)
            val bolus = """{"identifier":"offset-bolus","created_at":"$createdAt","insulin":0.25,"type":"SMB"}""".toNSTreatment() as NSBolus
            assertEquals(expected, bolus.date, createdAt)
            assertEquals(0.25, bolus.insulin)
            val basal = """{"identifier":"offset-basal","created_at":"$createdAt","eventType":"Temp Basal","absolute":1.2,"duration":30}""".toNSTreatment() as NSTemporaryBasal
            assertEquals(expected, basal.date, createdAt)
            assertEquals(1_800_000L, basal.duration)
            assertEquals(1.2, basal.rate)
        }
        listOf("+010000.123", "+010000,123", "+01:00:00123", "+0100001234", "+01:00:00.1234")
            .forEach { offset ->
                val createdAt = "2026-10-09T12:30:00$offset"
                assertEquals(0L, timestamp("""{"created_at":"$createdAt"}"""), createdAt)
                assertEquals(123L, timestamp("""{"date":123,"created_at":"$createdAt"}"""), createdAt)
                // Preserve the existing mapper's distinct zero-date bolus vs rejected
                // temp-basal behavior; correcting that policy is outside this refactor.
                val bolus = """{"created_at":"$createdAt","insulin":0.25}""".toNSTreatment() as NSBolus
                assertEquals(0L, bolus.date, createdAt)
                assertEquals(null, """{"created_at":"$createdAt","eventType":"Temp Basal","absolute":1.2,"duration":30}""".toNSTreatment(), createdAt)
            }
    }

    @Test
    fun `treatment accounting mapping retains timestamp insulin and duration`() {
        val bolus = """{"identifier":"synthetic-bolus","created_at":"2026-10-25T02:30:00.123+02:00","insulin":0.25,"type":"SMB"}""".toNSTreatment() as NSBolus
        assertEquals(1792888200123L, bolus.date)
        assertEquals(0.25, bolus.insulin)
        val basal = """{"identifier":"synthetic-basal","created_at":"2026-03-29T03:00:00+02:00","eventType":"Temp Basal","absolute":1.2,"duration":30}""".toNSTreatment() as NSTemporaryBasal
        assertEquals(1774746000000L, basal.date)
        assertEquals(1_800_000L, basal.duration)
        assertEquals(1.2, basal.rate)
        assertEquals(null, """{"created_at":"invalid","eventType":"Temp Basal","absolute":1.2,"duration":30}""".toNSTreatment())
    }
}
