package app.aaps.libre3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The two directions use DIFFERENT framing, and getting them confused produces a handshake
 * that fails only after the crypto — where it looks like a key problem rather than a transport
 * one. These tests pin the shapes down.
 *
 * Sizes are the real protocol sizes: 140-byte patch certificate, 65-byte ephemeral key,
 * 40-byte challenge reply, 23/67-byte challenge frames.
 */
class Libre3FramingTest {

    // --- announcements ------------------------------------------------------

    @Test
    fun `single byte value is a bare signal with no transfer`() {
        val a = Libre3Framing.parseAnnouncement(byteArrayOf(Libre3Framing.Signal.CERT_ACCEPTED.toByte()))
        assertThat(a).isNotNull()
        assertThat(a!!.signal).isEqualTo(Libre3Framing.Signal.CERT_ACCEPTED)
        assertThat(a.length).isEqualTo(0)
    }

    @Test
    fun `two byte value announces a transfer length`() {
        val a = Libre3Framing.parseAnnouncement(byteArrayOf(10, 140.toByte()))
        assertThat(a!!.signal).isEqualTo(Libre3Framing.Signal.CERTIFICATE_READY)
        assertThat(a.length).isEqualTo(140)
    }

    @Test
    fun `empty value is not an announcement`() {
        assertThat(Libre3Framing.parseAnnouncement(ByteArray(0))).isNull()
    }

    // --- reassembly (sensor to app) ----------------------------------------

    @Test
    fun `reassembles a multi frame patch certificate`() {
        val cert = ByteArray(140) { (it and 0xFF).toByte() }
        val r = Libre3Framing.Reassembler(cert.size)

        var result: Libre3Framing.Reassembler.Result? = null
        var seq = 0
        var offset = 0
        while (offset < cert.size) {
            val n = minOf(19, cert.size - offset)
            val frame = ByteArray(n + 1)
            frame[0] = seq.toByte()
            cert.copyInto(frame, 1, offset, offset + n)
            result = r.offer(frame)
            offset += n
            seq++
        }

        assertThat(result).isInstanceOf(Libre3Framing.Reassembler.Result.Complete::class.java)
        assertThat((result as Libre3Framing.Reassembler.Result.Complete).data).isEqualTo(cert)
    }

    @Test
    fun `first frame must have sequence zero`() {
        val r = Libre3Framing.Reassembler(10)
        val result = r.offer(byteArrayOf(1, 0, 0, 0))   // starts at 1, not 0
        assertThat(result).isInstanceOf(Libre3Framing.Reassembler.Result.Error::class.java)
    }

    @Test
    fun `a dropped frame is an error, never a silent hole`() {
        val r = Libre3Framing.Reassembler(30)
        assertThat(r.offer(ByteArray(11).also { it[0] = 0 }))
            .isInstanceOf(Libre3Framing.Reassembler.Result.NeedMore::class.java)
        // skip sequence 1
        val result = r.offer(ByteArray(11).also { it[0] = 2 })
        assertThat(result).isInstanceOf(Libre3Framing.Reassembler.Result.Error::class.java)
    }

    @Test
    fun `over long transfer is rejected rather than overflowing the buffer`() {
        val r = Libre3Framing.Reassembler(5)
        val result = r.offer(ByteArray(21).also { it[0] = 0 })   // 20 payload bytes into a 5-byte buffer
        assertThat(result).isInstanceOf(Libre3Framing.Reassembler.Result.Error::class.java)
    }

    @Test
    fun `reports remaining bytes while incomplete`() {
        val r = Libre3Framing.Reassembler(67)
        val result = r.offer(ByteArray(20).also { it[0] = 0 })   // 19 payload bytes
        assertThat(result).isEqualTo(Libre3Framing.Reassembler.Result.NeedMore(67 - 19))
    }

    // --- write splitting (app to sensor) -----------------------------------

    @Test
    fun `write frames are always 20 bytes even when the payload does not fill them`() {
        // 40-byte challenge reply -> 18 + 18 + 4, the last frame zero-padded
        val frames = Libre3Framing.splitForWrite(ByteArray(40) { 0x5A })
        assertThat(frames).hasSize(3)
        frames.forEach { assertThat(it.size).isEqualTo(Libre3Framing.WRITE_FRAME_SIZE) }
        // tail of the short final frame must be zero padding, not stale bytes
        assertThat(frames[2].copyOfRange(2 + 4, 20).all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun `write header is the running byte offset little endian, not a frame index`() {
        val frames = Libre3Framing.splitForWrite(ByteArray(140))
        assertThat(frames[0][0]).isEqualTo(0)
        assertThat(frames[0][1]).isEqualTo(0)
        assertThat(frames[1][0]).isEqualTo(18)      // 18, not 1
        assertThat(frames[1][1]).isEqualTo(0)
        assertThat(frames[2][0]).isEqualTo(36)
        // 140 bytes -> ceil(140/18) = 8 frames; last offset 126 fits in one byte
        assertThat(frames).hasSize(8)
        assertThat(frames[7][0]).isEqualTo(126.toByte())
    }

    @Test
    fun `offsets above 255 use the high byte`() {
        val frames = Libre3Framing.splitForWrite(ByteArray(300))
        val f15 = frames[15]                        // offset 270 = 0x010E
        assertThat(f15[0]).isEqualTo(0x0E.toByte())
        assertThat(f15[1]).isEqualTo(0x01.toByte())
    }

    @Test
    fun `split then concatenate round trips the payload`() {
        val payload = ByteArray(65) { (it * 7 and 0xFF).toByte() }   // ephemeral public key
        val rebuilt = Libre3Framing.splitForWrite(payload)
            .flatMap { it.copyOfRange(2, 20).asIterable() }
            .take(payload.size)
            .toByteArray()
        assertThat(rebuilt).isEqualTo(payload)
    }
}
