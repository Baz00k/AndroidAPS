package app.aaps.pump.ypsopump.provisioning

import app.aaps.pump.ypsopump.crypto.PumpSession
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

data class YpsoSessionDocument(
    val serial: String,
    val mac: String,
    val sharedKey: ByteArray,
    val createdAt: Instant,
    val capturedAt: Instant,
    val rebootCounter: Int?,
    val source: Map<String, String>
) {
    val fingerprint: String get() = PumpSession.fingerprint(sharedKey).take(16)
}

/** Strict parser for the canonical ypso-keys schema-v1 document. */
object YpsoSessionDocumentParser {
    const val MAX_DOCUMENT_BYTES = 64 * 1024
    private val canonicalTopLevel = setOf("schema_version", "pump", "shared_key", "created_at", "captured_at", "reboot_counter", "source")
    private val canonicalPump = setOf("mac", "serial")
    private val currentSource = setOf("profile", "package", "app_version", "identity")
    private val legacySource = currentSource + "donor"
    private val canonicalSerial = Regex("[A-Za-z0-9_-]{1,64}")
    private val sourceValue = Regex("[A-Za-z0-9_.:() -]{1,200}")

    fun parse(data: ByteArray, now: Instant = Instant.now()): YpsoSessionDocument {
        require(data.size <= MAX_DOCUMENT_BYTES) { "Session file is larger than 64 KiB" }
        val root = StrictJson(data.toString(Charsets.UTF_8)).parseObject()
        require(root.keys == canonicalTopLevel) { "Session file does not match schema version 1" }
        require(root.int("schema_version") == 1L) { "Unsupported session schema version" }
        val pump = root.obj("pump")
        require(pump.keys == canonicalPump) { "Pump identity fields do not match schema version 1" }
        val mac = PumpIdentity.normalizeMac(pump.string("mac"))
        val serial = pump.string("serial")
        require(canonicalSerial.matches(serial)) { "Pump serial does not match the canonical schema" }
        val keyHex = root.string("shared_key")
        require(keyHex.matches(Regex("[0-9A-Fa-f]{64}"))) { "Session key must contain 64 hexadecimal characters" }
        val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return try {
            require(key.any { it.toInt() != 0 }) { "Session key must not be all zero" }
            val created = timestamp(root.string("created_at"))
            val captured = timestamp(root.string("captured_at"))
            require(!created.isAfter(captured.plusSeconds(5 * 60))) { "Key creation time is after capture time" }
            require(!captured.isAfter(now.plusSeconds(5 * 60))) { "Capture time is in the future" }
            require(!created.isAfter(now)) { "Key creation time is in the future" }
            val reboot = when (val value = root["reboot_counter"]) {
                null -> null
                is JsonNumber -> value.value.also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
                else -> throw IllegalArgumentException("Reboot counter must be an integer or null")
            }
            val sourceObject = root.obj("source")
            require(sourceObject.keys == currentSource || sourceObject.keys == legacySource) { "Session source fields do not match schema version 1" }
            val source = sourceObject.entries.associate { (name, value) ->
                val text = (value as? String) ?: throw IllegalArgumentException("Session source values must be strings")
                require(sourceValue.matches(text)) { "Session source value is malformed" }
                name to text
            }.filterKeys { it != "donor" }
            YpsoSessionDocument(serial, mac, key, created, captured, reboot, source)
        } catch (e: Exception) {
            key.fill(0)
            throw e
        }
    }

    private fun timestamp(value: String): Instant = try {
        OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).also {
            require(it.year in 2020..2100)
        }.toInstant()
    } catch (e: DateTimeException) {
        throw IllegalArgumentException("Session timestamps must be timezone-aware ISO 8601 dates", e)
    }

    private sealed interface JsonValue
    private data class JsonNumber(val value: Long) : JsonValue
    private data class JsonObject(val values: LinkedHashMap<String, Any?>) : JsonValue {
        val keys: Set<String> get() = values.keys
        operator fun get(name: String): Any? = values[name]
        val entries get() = values.entries
        fun string(name: String): String = values[name] as? String ?: throw IllegalArgumentException("$name must be a string")
        fun int(name: String): Long = (values[name] as? JsonNumber)?.value ?: throw IllegalArgumentException("$name must be an integer")
        fun obj(name: String): JsonObject = values[name] as? JsonObject ?: throw IllegalArgumentException("$name must be an object")
    }

    /** Minimal JSON reader: exact types, duplicate rejection, no arrays/floats/booleans. */
    private class StrictJson(private val input: String) {
        private var index = 0

        fun parseObject(): JsonObject {
            whitespace()
            val value = objectValue()
            whitespace()
            require(index == input.length) { "Trailing data in session file" }
            return value
        }

        private fun objectValue(): JsonObject {
            expect('{')
            whitespace()
            val values = linkedMapOf<String, Any?>()
            if (take('}')) return JsonObject(values)
            while (true) {
                whitespace()
                val name = stringValue()
                require(name !in values) { "Duplicate field: $name" }
                whitespace(); expect(':'); whitespace()
                values[name] = value()
                whitespace()
                if (take('}')) return JsonObject(values)
                expect(',')
            }
        }

        private fun value(): Any? = when (peek()) {
            '"' -> stringValue()
            '{' -> objectValue()
            'n' -> { literal("null"); null }
            '-', in '0'..'9' -> numberValue()
            else -> throw IllegalArgumentException("Unsupported JSON value at byte $index")
        }

        private fun numberValue(): JsonNumber {
            val start = index
            if (take('-')) require(peek() in '0'..'9')
            if (take('0')) require(peek() !in '0'..'9') { "Leading zero in integer" }
            else {
                require(peek() in '1'..'9') { "Malformed integer" }
                while (peek() in '0'..'9') index++
            }
            require(peek() != '.' && peek() != 'e' && peek() != 'E') { "Only integer numbers are supported" }
            return JsonNumber(input.substring(start, index).toLongOrNull() ?: throw IllegalArgumentException("Integer outside supported range"))
        }

        private fun stringValue(): String {
            expect('"')
            val out = StringBuilder()
            while (index < input.length) {
                val char = input[index++]
                when (char) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < input.length) { "Incomplete JSON escape" }
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= input.length) { "Incomplete Unicode escape" }
                                val code = input.substring(index, index + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Malformed Unicode escape")
                                out.append(code.toChar()); index += 4
                            }
                            else -> throw IllegalArgumentException("Unsupported JSON escape")
                        }
                    }
                    else -> {
                        require(char.code >= 0x20) { "Control character in JSON string" }
                        out.append(char)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun literal(value: String) {
            require(input.regionMatches(index, value, 0, value.length)) { "Malformed JSON literal" }
            index += value.length
        }
        private fun whitespace() { while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++ }
        private fun peek(): Char = input.getOrNull(index) ?: '\u0000'
        private fun take(char: Char): Boolean = if (peek() == char) { index++; true } else false
        private fun expect(char: Char) { require(take(char)) { "Expected '$char' at byte $index" } }
    }
}

object PumpIdentity {
    private val macPattern = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val supportedSerial = Regex("10[0-9]{6}")
    private val supportedDeviceNames = listOf(
        Regex("(?i)^mylife\\s+YpsoPump(?:[ _])?(10[0-9]{6}|[0-9]{6})$"),
        Regex("(?i)^YpsoPump_(10[0-9]{6}|[0-9]{6})$")
    )

    fun normalizeMac(value: String): String {
        val normalized = value.trim().uppercase(Locale.ROOT)
        require(macPattern.matches(normalized)) { "Pump MAC must have six colon-separated hexadecimal octets" }
        return normalized
    }

    fun normalizeSerial(value: String): String {
        val normalized = value.trim()
        require(supportedSerial.matches(normalized)) { "Supported YpsoPump serials contain eight digits and start with 10" }
        return normalized
    }

    fun validatePair(serial: String, mac: String) {
        val normalizedSerial = normalizeSerial(serial)
        val normalizedMac = normalizeMac(mac)
        val suffix = normalizedSerial.toLong() - 10_000_000L
        require(suffix in 0..0xFFFFFF) { "Pump serial is outside the supported identity range" }
        val expected = "EC:2A:F0:%02X:%02X:%02X".format(
            Locale.ROOT,
            (suffix shr 16) and 0xff,
            (suffix shr 8) and 0xff,
            suffix and 0xff
        )
        require(normalizedMac == expected) { "Pump serial and BLE MAC identify different pumps" }
    }

    fun deviceNameMatches(serial: String, deviceName: String?): Boolean {
        val normalized = normalizeSerial(serial)
        return serialFromDeviceName(deviceName) == normalized
    }

    fun serialFromDeviceName(deviceName: String?): String? {
        val identity = supportedDeviceNames.firstNotNullOfOrNull { it.matchEntire(deviceName.orEmpty().trim()) }
            ?.groupValues?.get(1)
            ?: return null
        return runCatching { normalizeSerial(if (identity.length == 6) "10$identity" else identity) }.getOrNull()
    }

    fun isSupportedDeviceName(deviceName: String?): Boolean = serialFromDeviceName(deviceName) != null
}
