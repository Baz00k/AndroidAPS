package app.aaps.pump.ypsopump.data

import android.content.SharedPreferences
import app.aaps.pump.ypsopump.comm.YpsoGlb
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Last-read configuration, never replay accounting. Stored separately from the protected journals. */
internal class YpsoProfileConfigurationStore(private val preferences: SharedPreferences) {
    fun save(value: YpsoProfileReadback.VerifiedReadback) {
        val json = JSONObject()
            .put("version", 1).put("generation", value.generation).put("reboot", value.reboot)
            .put("program", value.activeProgram.name).put("zone", value.zone.id)
            .put("observedAt", value.observedAt.toEpochMilli())
        for ((name, schedule) in listOf("a" to value.profileA, "b" to value.profileB)) {
            json.put(name, JSONArray((0..23).map { Math.round(schedule.rateAt(it * 3600) * 100).toInt() }))
        }
        check(preferences.edit().putString("configuration", json.toString()).commit()) { "Cannot persist pump profile configuration" }
    }

    fun load(generation: String): YpsoProfileReadback.VerifiedReadback? = runCatching {
        val json = JSONObject(preferences.getString("configuration", null) ?: return null)
        require(json.getInt("version") == 1 && json.getString("generation") == generation)
        fun schedule(name: String): YpsoBasalSchedule {
            val rows = json.getJSONArray(name)
            require(rows.length() == 24)
            return checkNotNull(YpsoBasalSchedule.decode((0..23).map { YpsoGlb.encode(rows.getInt(it)) }))
        }
        YpsoProfileReadback.VerifiedReadback(
            generation, json.getInt("reboot"), "persisted-configuration",
            YpsoBasalSchedule.Program.valueOf(json.getString("program")), schedule("a"), schedule("b"),
            0, ZoneId.of(json.getString("zone")), 0, Instant.ofEpochMilli(json.getLong("observedAt")),
        )
    }.getOrNull()
}
