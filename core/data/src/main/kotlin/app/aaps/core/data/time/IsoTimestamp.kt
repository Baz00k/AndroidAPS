package app.aaps.core.data.time

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

/**
 * ISO timestamps accepted by APS results, Nightscout treatments and settings metadata.
 *
 * An absent offset means the device zone, not UTC. Gaps are invalid; overlaps use the
 * earlier offset. Fractions are truncated to milliseconds, including fractional hours
 * and minutes. Calendar/ordinal/week dates and reduced precision remain accepted.
 */
object IsoTimestamp {

    private val calendarDate = Regex("([+-]?\\d{1,9})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?")
    private val ordinalDate = Regex("([+-]?\\d{1,9})-(\\d{3})")
    private val weekDate = Regex("([+-]?\\d{1,9})-[Ww](\\d{1,2})(?:-(\\d))?")
    private val time = Regex("(\\d{1,2})(?::(\\d{1,2})(?::(\\d{1,2}))?)?(?:[.,](\\d{1,9}))?")
    private val offset = Regex("([+-])(\\d{2})(?:(:?)(\\d{2})(?:\\3(\\d{2})(?:[.,](\\d{1,3}))?)?)?")

    class Parsed internal constructor(
        val localDateTime: LocalDateTime,
        private val offsetMillis: Int?,
        private val zone: ZoneId
    ) {

        val instant: Instant =
            if (offsetMillis != null) localDateTime.toInstant(ZoneOffset.UTC).minusMillis(offsetMillis.toLong())
            else {
                val offsets = zone.rules.getValidOffsets(localDateTime)
                if (offsets.isEmpty()) throw DateTimeException("Timestamp is in a daylight-saving gap: $localDateTime in $zone")
                ZonedDateTime.ofStrict(localDateTime, offsets.first(), zone).toInstant()
            }

        fun toInstant(): Instant = instant

        /** Whole calendar days in the timestamp's zone, as used by settings' age label. */
        fun daysUntil(instant: Instant): Int {
            val days = if (offsetMillis != null)
                ChronoUnit.DAYS.between(localDateTime, LocalDateTime.ofInstant(instant.plusMillis(offsetMillis.toLong()), ZoneOffset.UTC))
            else
                ChronoUnit.DAYS.between(this.instant.atZone(zone), instant.atZone(zone))
            return Math.toIntExact(days)
        }
    }

    fun parse(value: String, zone: ZoneId = ZoneId.systemDefault(), requireDate: Boolean = false): Parsed {
        val separator = value.indexOfFirst { it == 'T' || it == 't' }
        val dateText = if (separator < 0) value else value.substring(0, separator)
        if (requireDate && dateText.isEmpty()) throw DateTimeException("Missing ISO date: $value")
        val date = if (dateText.isEmpty() && separator == 0) LocalDate.of(1970, 1, 1) else parseDate(dateText)
        var timeText = if (separator < 0) "" else value.substring(separator + 1)
        val offsetMillis = when {
            timeText.endsWith("Z", ignoreCase = true) -> {
                timeText = timeText.dropLast(1)
                0
            }
            else -> {
                val start = timeText.indexOfFirst { it == '+' || it == '-' }
                if (start < 0) null else parseOffset(timeText.substring(start)).also { timeText = timeText.substring(0, start) }
            }
        }
        if (separator == 0 && timeText.isEmpty()) throw DateTimeException("Missing ISO time: $value")
        val localTime = if (timeText.isEmpty()) LocalTime.MIDNIGHT else parseTime(timeText)
        return Parsed(date.atTime(localTime), offsetMillis, zone)
    }

    private fun parseDate(value: String): LocalDate {
        calendarDate.matchEntire(value)?.destructured?.let { (year, month, day) ->
            return LocalDate.of(year.toInt(), month.ifEmpty { "1" }.toInt(), day.ifEmpty { "1" }.toInt())
        }
        ordinalDate.matchEntire(value)?.destructured?.let { (year, day) ->
            return LocalDate.ofYearDay(year.toInt(), day.toInt())
        }
        weekDate.matchEntire(value)?.destructured?.let { (year, week, day) ->
            val weekYear = year.toInt()
            val weekNumber = week.toInt()
            val dayNumber = day.ifEmpty { "1" }.toInt()
            if (weekNumber !in 1..53 || dayNumber !in 1..7) throw DateTimeException("Invalid ISO week date: $value")
            val date = LocalDate.of(weekYear, 1, 4).with(ChronoField.DAY_OF_WEEK, 1)
                .plusWeeks(weekNumber - 1L).plusDays(dayNumber - 1L)
            if (date.get(IsoFields.WEEK_BASED_YEAR) != weekYear) throw DateTimeException("Invalid ISO week date: $value")
            return date
        }
        throw DateTimeException("Invalid ISO date: $value")
    }

    private fun parseTime(value: String): LocalTime {
        val match = time.matchEntire(value) ?: throw DateTimeException("Invalid ISO time: $value")
        val (hour, minute, second, fraction) = match.destructured
        val base = LocalTime.of(hour.toInt(), minute.ifEmpty { "0" }.toInt(), second.ifEmpty { "0" }.toInt())
        val unitMillis = if (minute.isEmpty()) 3_600_000L else if (second.isEmpty()) 60_000L else 1_000L
        // Retain the existing parser's digit-by-digit truncation for fractional hours
        // and minutes too; scaling the whole decimal can differ by one millisecond.
        var scale = unitMillis * 10
        var scaledFraction = 0L
        fraction.forEach { digit ->
            scale /= 10
            scaledFraction += (digit - '0') * scale
        }
        val fractionMillis = scaledFraction / 10
        return base.plusNanos(fractionMillis * 1_000_000L)
    }

    private fun parseOffset(value: String): Int {
        val match = offset.matchEntire(value) ?: throw DateTimeException("Invalid ISO offset: $value")
        val (sign, hour, _, minute, second, fraction) = match.destructured
        val h = hour.toInt()
        val m = minute.ifEmpty { "0" }.toInt()
        val s = second.ifEmpty { "0" }.toInt()
        if (h !in 0..23 || m !in 0..59 || s !in 0..59) throw DateTimeException("Invalid ISO offset: $value")
        val millis = ((h * 60 + m) * 60 + s) * 1_000 + fraction.padEnd(3, '0').toInt()
        return if (sign == "-") -millis else millis
    }
}
