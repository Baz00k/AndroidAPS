package app.aaps.pump.ypsopump.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Journal revisions are authenticated by unique, non-exportable Keystore keys. The previous anchor
 * is destroyed BEFORE replacing the file. A crash anywhere in that interval requires recovery;
 * it cannot restore an older replay floor. The file is excluded from Android backup.
 */
class SessionJournal(context: Context) : PumpSession.Store {

    private val file = File(context.noBackupFilesDir, "ypso-session.json")
    private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun load(): PumpSession.State {
        val anchors = anchors()
        if (!file.exists() && anchors.isEmpty()) return PumpSession.State()
        val envelope = JSONObject(file.readText())
        val alias = envelope.getString("anchor")
        check(anchors == listOf(alias)) { "Incomplete or restored session journal" }
        val body = envelope.getString("body")
        check(java.security.MessageDigest.isEqual(mac(alias, body), unhex(envelope.getString("mac")))) { "Corrupt session journal" }
        val json = JSONObject(body)
        check(json.getInt("version") == 1)
        val records = json.getJSONArray("records")
        return PumpSession.State((0 until records.length()).map { index ->
            val r = records.getJSONObject(index)
            val reservation = r.optJSONObject("reservation")?.let {
                PumpSession.Reservation(it.getString("id"), it.getLong("counter"), PumpSession.Phase.valueOf(it.getString("phase")))
            }
            PumpSession.Record(
                r.getString("pump"), r.getString("key"), r.getString("generation"), r.getInt("reboot"),
                r.getLong("read"), if (r.isNull("write")) null else r.getLong("write"), reservation
            )
        })
    }

    override fun commit(state: PumpSession.State) {
        val records = JSONArray()
        state.records.forEach { r ->
            records.put(JSONObject().put("pump", r.pump).put("key", r.keyId).put("generation", r.generation)
                .put("reboot", r.reboot).put("read", r.read).put("write", r.write ?: JSONObject.NULL)
                .put("reservation", r.reservation?.let {
                    JSONObject().put("id", it.id).put("counter", it.counter).put("phase", it.phase.name)
                } ?: JSONObject.NULL))
        }
        val body = JSONObject().put("version", 1).put("records", records).toString()
        val old = anchors()
        val alias = PREFIX + UUID.randomUUID()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKey()
        val envelope = JSONObject().put("anchor", alias).put("body", body).put("mac", hex(mac(alias, body))).toString()
        // Invalidate every previous revision before the commit can become observable.
        old.forEach(keys::deleteEntry)
        FileOutputStream(file).use { out ->
            out.write(envelope.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    private fun anchors(): List<String> = keys.aliases().toList().filter { it.startsWith(PREFIX) }
    private fun mac(alias: String, body: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(keys.getKey(alias, null) as SecretKey)
        doFinal(body.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(value: String): ByteArray {
        require(value.length == 64)
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    companion object {
        private const val PREFIX = "ypso.session.revision."
    }
}
