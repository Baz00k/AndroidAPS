package app.aaps.libre3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * NFC activation is the single irreversible operation in the system — a wrong command costs a
 * physical sensor. The CRC is checked against the exact vectors Juggluco asserts at compile time
 * in `dp_activation.hpp`, so a porting slip cannot pass silently.
 */
class Libre3NfcTest {

    @Test
    fun `crc matches Juggluco's compile-time vectors`() {
        assertThat(Libre3Nfc.crc16(ByteArray(8))).isEqualTo(0x313E)
        assertThat(Libre3Nfc.crc16(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))).isEqualTo(0xCCBF)
        assertThat(Libre3Nfc.crc16(byteArrayOf(8, 0, 0, 0, 12, 0, 0, 0))).isEqualTo(0x2063)
    }

    @Test
    fun `activation data is time minus one, account, then crc`() {
        // The -1 on the time is real, not an off-by-one to tidy away: Juggluco does it and the
        // sensor validates the result.
        val data = Libre3Nfc.activationData(startTimeSeconds = 9, accountId = 12)
        assertThat(data.size).isEqualTo(10)
        assertThat(data.copyOfRange(0, 4)).isEqualTo(byteArrayOf(8, 0, 0, 0))    // 9 - 1
        assertThat(data.copyOfRange(4, 8)).isEqualTo(byteArrayOf(12, 0, 0, 0))
        // and the trailing CRC is the 0x2063 vector, little-endian
        assertThat(data[8]).isEqualTo(0x63.toByte())
        assertThat(data[9]).isEqualTo(0x20.toByte())
    }

    @Test
    fun `activation data is little endian`() {
        val data = Libre3Nfc.activationData(startTimeSeconds = 0x00012346, accountId = 0x89ABCDEF)
        assertThat(data.copyOfRange(0, 4)).isEqualTo(byteArrayOf(0x45, 0x23, 0x01, 0x00))
        assertThat(data.copyOfRange(4, 8))
            .isEqualTo(byteArrayOf(0xEF.toByte(), 0xCD.toByte(), 0xAB.toByte(), 0x89.toByte()))
    }

    @Test
    fun `command wraps the payload in the NfcV frame`() {
        val cmd = Libre3Nfc.command(Libre3Nfc.OP_ACTIVATE, 9, 12)
        assertThat(cmd.size).isEqualTo(13)
        assertThat(cmd[0]).isEqualTo(0x02.toByte())
        assertThat(cmd[1]).isEqualTo(0xA0.toByte())
        assertThat(cmd[2]).isEqualTo(0x7A.toByte())
        assertThat(cmd.copyOfRange(3, 13)).isEqualTo(Libre3Nfc.activationData(9, 12))
    }

    @Test
    fun `activate and takeover differ only in the operation byte`() {
        val a = Libre3Nfc.command(Libre3Nfc.OP_ACTIVATE, 100, 200)
        val t = Libre3Nfc.command(Libre3Nfc.OP_TAKEOVER, 100, 200)
        assertThat(a[1]).isEqualTo(0xA0.toByte())
        assertThat(t[1]).isEqualTo(0xA8.toByte())
        assertThat(a.copyOfRange(2, 13)).isEqualTo(t.copyOfRange(2, 13))
    }

    @Test
    fun `an arbitrary operation byte is refused`() {
        // Guard against a caller passing a raw int and silently emitting a command the sensor
        // interprets as something else entirely.
        assertThrows<IllegalArgumentException> { Libre3Nfc.command(0xA4.toByte(), 1, 1) }
        assertThrows<IllegalArgumentException> { Libre3Nfc.command(0, 1, 1) }
    }

    @Test
    fun `patchInfo byte 17 selects activate versus takeover`() {
        val activate = ByteArray(24).also { it[17] = 1 }
        val takeover = ByteArray(24).also { it[17] = 0 }
        assertThat(Libre3Nfc.operationFor(activate)).isEqualTo(Libre3Nfc.OP_ACTIVATE)
        assertThat(Libre3Nfc.operationFor(takeover)).isEqualTo(Libre3Nfc.OP_TAKEOVER)
    }

    @Test
    fun `a short patchInfo yields no operation rather than a guess`() {
        // Guessing here would mean sending ACTIVATE at a running sensor.
        assertThat(Libre3Nfc.operationFor(ByteArray(17))).isNull()
        assertThat(Libre3Nfc.operationFor(ByteArray(0))).isNull()
    }
}
