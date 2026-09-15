package app.aaps.ypso.writebench

import android.content.Context
import app.aaps.pump.ypsopump.ble.YpsoWriteBehavior
import app.aaps.pump.ypsopump.ble.YpsoWriteBehaviorRecorder
import app.aaps.pump.ypsopump.ble.YpsoWriteFailure
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Append-only, fsynced evidence. It stores no key, ciphertext, or decrypted pump-response body. */
internal class BenchEvidenceRecorder(
    context: Context,
) : YpsoWriteBehaviorRecorder {
    private val file = File(context.filesDir, "write-evidence.jsonl")

    override fun record(event: YpsoWriteBehavior) =
        append(
            JSONObject().put("event", event.javaClass.simpleName).put("write_id", event.writeId).apply {
                when (event) {
                    is YpsoWriteBehavior.Started ->
                        put("connection", event.connectionId)
                            .put("generation", event.generation)
                            .put("category", event.category.name)
                            .put("characteristic", event.characteristic)
                            .put("counter", event.counter)
                            .put("frame_count", event.frameCount)
                    is YpsoWriteBehavior.FrameDispatch -> put("frame", event.frame).put("accepted_locally", event.acceptedLocally)
                    is YpsoWriteBehavior.Callback ->
                        put("characteristic", event.characteristic)
                            .put("status", event.status)
                            .put("frame_expected", event.frameExpected)
                    is YpsoWriteBehavior.IgnoredCallback ->
                        put("reason", event.reason)
                            .put("characteristic", event.characteristic)
                            .put("status", event.status)
                    is YpsoWriteBehavior.Outcome -> put("outcome", outcome(event.value))
                    is YpsoWriteBehavior.Reconciled -> put("semantic", event.evidence.name).put("detail", event.detail)
                }
            },
        )

    fun fact(
        name: String,
        values: JSONObject = JSONObject(),
    ) = append(values.put("event", name))

    private fun outcome(value: YpsoWriteOutcome) =
        JSONObject()
            .put("type", value.javaClass.simpleName)
            .put("counter", value.counter ?: JSONObject.NULL)
            .apply {
                when (value) {
                    is YpsoWriteOutcome.NotSent -> put("failure", failure(value.failure))
                    is YpsoWriteOutcome.ProvenRejected -> put("failure", failure(value.failure))
                    is YpsoWriteOutcome.PossiblyApplied -> put("failure", failure(value.failure))
                    is YpsoWriteOutcome.AcceptedUnverified -> Unit
                    is YpsoWriteOutcome.Verified -> put("evidence", value.evidence)
                }
            }

    private fun failure(value: YpsoWriteFailure) =
        JSONObject()
            .put("layer", value.layer.name)
            .put("characteristic", value.characteristic)
            .put("firmware", value.firmware ?: JSONObject.NULL)
            .put("code", value.code ?: JSONObject.NULL)
            .put("frame", value.frame ?: JSONObject.NULL)
            .put("detail", value.detail)

    @Synchronized
    private fun append(value: JSONObject) {
        value.put("wall_time_ms", System.currentTimeMillis()).put("elapsed_ms", android.os.SystemClock.elapsedRealtime())
        FileOutputStream(file, true).use { out ->
            out.write((value.toString() + "\n").toByteArray())
            out.fd.sync()
        }
    }
}
