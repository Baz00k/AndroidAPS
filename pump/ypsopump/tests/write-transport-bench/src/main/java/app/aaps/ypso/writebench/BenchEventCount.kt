package app.aaps.ypso.writebench

import app.aaps.pump.ypsopump.comm.YpsoGlb

/** Event-count reads are exact GLB safe variables; unlike status/history values, they carry no CRC. */
internal object BenchEventCount {
    fun decode(body: ByteArray): Int? = YpsoGlb.decodeExact(body)
}
