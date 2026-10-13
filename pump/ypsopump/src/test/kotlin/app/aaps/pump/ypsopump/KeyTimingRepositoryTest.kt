package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.KeyTiming
import app.aaps.pump.ypsopump.provisioning.KeyTimingRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KeyTimingRepositoryTest {
    @TempDir lateinit var directory: File

    @Test fun `real file publication failure is reported and cannot update cache or prior durable choices`() {
        val file = File(directory, "dates.json")
        val original = KeyTiming(1234, 5678, true, true, 9999)
        KeyTimingRepository(KeyTimingRepository.FileStore(file)).save("identity", original)
        val repository = KeyTimingRepository(KeyTimingRepository.FileStore(file) {
            // Force a real rename failure after writing and synchronizing the temporary file.
            val temporary = File(file.path + ".new")
            check(temporary.delete())
        })
        assertEquals(original, repository.get("identity"))
        assertThrows(IllegalStateException::class.java) { repository.save("identity", KeyTiming(4321)) }
        assertEquals(original, repository.cached("identity"))
        assertEquals(original, KeyTimingRepository(KeyTimingRepository.FileStore(file)).get("identity"))
        val restarted = KeyTimingRepository(KeyTimingRepository.FileStore(file))
        assertEquals(KeyTiming(), restarted.get("another-pump/identity"))
        assertEquals(KeyTiming(), restarted.get("replacement-key"))
        restarted.save("identity", KeyTiming(4321))
        assertEquals(KeyTiming(4321), KeyTimingRepository(KeyTimingRepository.FileStore(file)).get("identity"))
    }

    @Test fun `lazy advisory loading can recover without publishing defaults`() {
        var reads = 0
        var unavailable = true
        val original = KeyTiming(1234, 5678, true, true, 9999)
        val repository = KeyTimingRepository(object : KeyTimingRepository.Store {
            override fun load(): Map<String, KeyTiming> { reads++; check(!unavailable); return mapOf("identity" to original) }
            override fun save(values: Map<String, KeyTiming>) { error("Not reached") }
        })
        assertEquals(0, reads)
        assertEquals(KeyTiming(), repository.cached("identity"))
        assertEquals(0, reads)
        assertThrows(IllegalStateException::class.java) { repository.get("identity") }
        assertEquals(1, reads)
        unavailable = false
        assertEquals(original, repository.get("identity"))
        assertEquals(original, repository.cached("identity"))
    }
}
