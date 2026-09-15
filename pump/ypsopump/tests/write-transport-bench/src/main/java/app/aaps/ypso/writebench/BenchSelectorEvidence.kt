package app.aaps.ypso.writebench

import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry

internal data class BenchSelectorEvidence(
    val glb: Int?,
    val crcValid: Boolean,
    val embeddedHistoryIndex: Int?,
    val semanticMatch: Boolean?,
)

internal object BenchSelectorEvidenceDecoder {

    fun history(body: ByteArray, selectedIndex: Int? = null): BenchSelectorEvidence {
        val entry = YpsoHistoryEntry.decodeWire(body)
        return BenchSelectorEvidence(
            glb = null,
            crcValid = entry != null,
            embeddedHistoryIndex = entry?.index,
            semanticMatch = selectedIndex?.let { entry?.index == it },
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
