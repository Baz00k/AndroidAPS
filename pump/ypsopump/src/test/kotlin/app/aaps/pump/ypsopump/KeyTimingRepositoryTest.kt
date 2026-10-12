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
    }
    @Test fun `separate advisory store roundtrips dates due latches and clock and binds full identity`() {
        val store = KeyTimingRepository.MemoryStore()
        val repository = KeyTimingRepository(store)
        val timing = KeyTiming(123456, 234567, true, true, 345678)
        repository.save("pump/full-key-fingerprint", timing)
        val restarted = KeyTimingRepository(store)
        assertEquals(timing, restarted.get("pump/full-key-fingerprint"))
        assertEquals(KeyTiming(), restarted.get("another-pump/full-key-fingerprint"))
        assertEquals(KeyTiming(), restarted.get("pump/replacement-key"))
    }

    @Test fun `failed advisory save does not publish manual choices or poison repository`() {
        val memory = KeyTimingRepository.MemoryStore()
        var fail = true
        val store = object : KeyTimingRepository.Store {
            override fun load() = memory.load()
            override fun save(values: Map<String, KeyTiming>) { check(!fail); memory.save(values) }
        }
        val repository = KeyTimingRepository(store)
        assertThrows(IllegalStateException::class.java) { repository.save("identity", KeyTiming(1234)) }
        assertEquals(KeyTiming(), repository.cached("identity"))
        fail = false
        repository.save("identity", KeyTiming(1234))
        assertEquals(KeyTiming(1234), KeyTimingRepository(store).get("identity"))
    }

    @Test fun `construction and presentation do not load slow or corrupt advisory storage`() {
        var reads = 0
        val repository = KeyTimingRepository(object : KeyTimingRepository.Store {
            override fun load(): Map<String, KeyTiming> { reads++; error("Corrupt advisory data") }
            override fun save(values: Map<String, KeyTiming>) { error("Not reached") }
        })
        assertEquals(0, reads)
        assertEquals(KeyTiming(), repository.cached("identity"))
        assertEquals(0, reads)
        assertThrows(IllegalStateException::class.java) { repository.get("identity") }
        assertEquals(1, reads)
    }

    @Test fun `transient load failure can recover without restart and without publishing defaults`() {
        var unavailable = true
        val original = KeyTiming(1234, 5678, true, true, 9999)
        val repository = KeyTimingRepository(object : KeyTimingRepository.Store {
            override fun load(): Map<String, KeyTiming> { check(!unavailable); return mapOf("identity" to original) }
            override fun save(values: Map<String, KeyTiming>) { error("Not needed") }
        })
        assertThrows(IllegalStateException::class.java) { repository.get("identity") }
        unavailable = false
        assertEquals(original, repository.get("identity"))
        assertEquals(original, repository.cached("identity"))
    }
}
