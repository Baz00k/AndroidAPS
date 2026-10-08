package app.aaps.pump.ypsopump

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.crypto.SessionJournal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic credentials and a private test journal; never connects to a pump or sends a command. */
@RunWith(AndroidJUnit4::class)
class SessionCryptoJournalNativeTest {

    @Test
    fun nativeCryptoRetainsPersistedReplayFloorAndUncertainWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir, "crypto-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val androidStorage = SessionJournal.AndroidStorage(isolatedContext)
        val existingAnchors = androidStorage.anchors().toSet()
        // AndroidStorage's aliases are app-wide. Exclude other fixtures' anchors, including any
        // left by a native process abort, rather than treating them as this private file's journal.
        val storage = object : SessionJournal.Storage by androidStorage {
            override fun anchors(): List<String> = androidStorage.anchors().filterNot { it in existingAnchors }
        }
        try {
            val key = ByteArray(SessionCrypto.KEY_SIZE) { it.toByte() }
            val record = PumpSession.Record(
                pump = "12:34:56:78:9A:BC", keyId = PumpSession.fingerprint(key), generation = "fixture",
                reboot = 8, read = 2742, write = 41, serial = "10000001",
                keyHex = key.joinToString("") { "%02x".format(it) },
            )
            val state = PumpSession.State(records = listOf(record), activeGeneration = record.generation)
            SessionJournal(storage).commit(state)
            assertEquals(state, SessionJournal(storage).load())

            val crypto = SessionCrypto()
            val vector = hex("873858d502cde7d78f6906561187572ee28bc6380dd1d0e092b58d09876aea28292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
            val owner = PumpSession(SessionJournal(storage))
            val token = owner.open(record.pump, key)
            val transaction = owner.begin(token)
            val before = SessionJournal(storage).load()
            val tampered = vector.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertThrows(SessionCrypto.AuthenticationFailedException::class.java) {
                owner.decrypt(token, transaction, tampered, crypto)
            }
            assertEquals(before, SessionJournal(storage).load())
            assertArrayEquals(hex("aabbcc"), owner.decrypt(token, transaction, vector, crypto))
            owner.finish(token, transaction)
            owner.quiesce()

            val restored = PumpSession(SessionJournal(storage))
            val restoredToken = restored.open(record.pump, key)
            val restoredTransaction = restored.begin(restoredToken)
            assertThrows(SecurityException::class.java) {
                restored.decrypt(restoredToken, restoredTransaction, vector, SessionCrypto())
            }
            restored.finish(restoredToken, restoredTransaction)
            assertEquals(2743L, SessionJournal(storage).load().records.single().read)

            val reservation = restored.reserve(restoredToken, restored.begin(restoredToken))
            val encrypted = restored.encryptReserved(restoredToken, reservation.id, hex("010203"), crypto)
            val decoded = SessionCrypto().decrypt(encrypted, key)
            assertArrayEquals(hex("010203"), decoded.body)
            assertEquals(8, decoded.reboot)
            assertEquals(42L, decoded.counter)
            restored.advance(restoredToken, reservation.id, PumpSession.Phase.POSSIBLY_SENT)
            restored.finish(restoredToken, reservation.id)
            restored.quiesce()

            val uncertain = SessionJournal(storage).load().records.single().reservation!!
            assertEquals(PumpSession.Phase.POSSIBLY_SENT, uncertain.phase)
            assertEquals(42L, uncertain.counter)
            val restarted = PumpSession(SessionJournal(storage))
            val restartedToken = restarted.open(record.pump, key)
            val restartedTransaction = restarted.begin(restartedToken)
            assertThrows(IllegalStateException::class.java) { restarted.reserve(restartedToken, restartedTransaction) }
            assertEquals(uncertain, SessionJournal(storage).load().records.single().reservation)
            restarted.finish(restartedToken, restartedTransaction)
        } finally {
            (storage.anchors().toSet() - existingAnchors).forEach(storage::delete)
            File(directory, "ypso-session.json").delete()
            File(directory, "ypso-session.json.new").delete()
            directory.delete()
        }
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
