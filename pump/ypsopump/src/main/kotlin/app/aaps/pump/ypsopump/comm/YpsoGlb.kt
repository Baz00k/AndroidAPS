package app.aaps.pump.ypsopump.comm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exact GLB safe variable: value(u32 LE) followed by its bitwise complement(u32 LE). */
internal object YpsoGlb {
    const val SIZE = 8

    fun encode(value: Int): ByteArray =
        ByteBuffer
            .allocate(SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .putInt(value.inv())
            .array()

    fun decodeExact(data: ByteArray): Int? {
        if (data.size != SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val value = buffer.int
        return value.takeIf { buffer.int == value.inv() }
    }

    fun find(data: ByteArray): Int? {
        if (data.size < SIZE) return null
        for (start in 0..data.size - SIZE) {
            decodeExact(data.copyOfRange(start, start + SIZE))?.let { return it }
        }
        return null
    }
}
