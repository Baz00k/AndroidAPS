package app.aaps.plugins.main.profile.ui

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.junit.jupiter.api.Test

class ProfileBlockOpsTest {

    // Nightscout's profile editor stores newly added rows without "timeAsSeconds".
    private fun nsBasal() = JSONArray(
        """[{"time":"00:00","value":0.5,"timeAsSeconds":0},{"time":"11:00","value":0.5,"timeAsSeconds":39600},""" +
            """{"time":"15:00","value":0.6},{"time":"17:00","value":0.5}]"""
    )

    @Test
    fun secondFromMidnightFallsBackToTimeField() {
        val basal = nsBasal()
        assertThat((0 until 4).map { ProfileBlockOps.secondFromMidnight(basal, it) })
            .containsExactly(0, 39600, 54000, 61200).inOrder()
    }

    @Test
    fun editingValueKeepsTimeOfRowWithoutTimeAsSeconds() {
        val basal = nsBasal()
        ProfileBlockOps.editBlock(basal, null, 3, ProfileBlockOps.secondFromMidnight(basal, 3), 0.55, 0.0)
        assertThat(basal.getJSONObject(3).getString("time")).isEqualTo("17:00")
        assertThat(basal.getJSONObject(3).getInt("timeAsSeconds")).isEqualTo(61200)
        assertThat(basal.getJSONObject(3).getDouble("value")).isEqualTo(0.55)
    }

    @Test
    fun normalizeTimesFillsMissingTimeAsSeconds() {
        val basal = nsBasal()
        assertThat(ProfileBlockOps.normalizeTimes(basal)).isTrue()
        assertThat(basal.getJSONObject(2).getInt("timeAsSeconds")).isEqualTo(54000)
        assertThat(basal.getJSONObject(3).getInt("timeAsSeconds")).isEqualTo(61200)
        assertThat(ProfileBlockOps.normalizeTimes(basal)).isFalse()
    }

    @Test
    fun timeAsSecondsOfRejectsGarbage() {
        assertThat(ProfileBlockOps.timeAsSecondsOf(org.json.JSONObject("""{"time":"xx"}"""))).isNull()
        assertThat(ProfileBlockOps.timeAsSecondsOf(org.json.JSONObject("""{"time":"25:00"}"""))).isNull()
        assertThat(ProfileBlockOps.timeAsSecondsOf(org.json.JSONObject("""{"time":"7:30"}"""))).isEqualTo(27000)
    }
}
