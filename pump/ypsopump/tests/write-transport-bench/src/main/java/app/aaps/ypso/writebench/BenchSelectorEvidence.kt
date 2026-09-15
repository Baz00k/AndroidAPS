package app.aaps.ypso.writebench

import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoGlb

internal data class BenchSelectorEvidence(
    val glb: Int?,
    val crcValid: Boolean,
    val embeddedHistoryIndex: Int?,
    val semanticMatch: Boolean?,
)

internal object BenchSelectorEvidenceDecoder {

    fun history(body: ByteArray, selectedIndex: Int? = null): BenchSelectorEvidence {
        val payload = YpsoCrc.validatedPayload(body)
        val embedded = payload?.takeIf { it.size >= 17 }?.let {
            (it[15].toInt() and 0xff) or ((it[16].toInt() and 0xff) shl 8)
        }
        return BenchSelectorEvidence(
            glb = YpsoGlb.find(body),
            crcValid = payload != null,
            embeddedHistoryIndex = embedded,
            semanticMatch = selectedIndex?.let { payload != null && embedded == it },
        )
    }

    /**
     * Setting values are recorded observationally. There is no qualified mapping from a setting ID
     * to its value layout, so the decoder never claims a semantic match.
     */
    fun setting(body: ByteArray): BenchSelectorEvidence {
        val exact = YpsoGlb.decodeExact(body)
        val crcPayload = if (exact == null) YpsoCrc.validatedPayload(body) else null
        return BenchSelectorEvidence(
            glb = exact ?: crcPayload?.let(YpsoGlb::find),
            crcValid = crcPayload != null,
            embeddedHistoryIndex = null,
            semanticMatch = null,
        )
    }
}
