package app.aaps.ui.dialogs.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CarbRemindersTest {

    private fun plan(
        carbs: Int = 30,
        offset: Int = 15,
        eat: Boolean = true,
        bolus: Boolean = false,
        bolusOffered: Boolean = false
    ) = CarbReminders.plan(carbs, offset, eat, bolus, bolusOffered)

    @Test
    fun `eat reminder is scheduled in seconds for a future meal`() {
        assertThat(plan(offset = 15).eatReminderSeconds).isEqualTo(900)
        assertThat(plan(offset = 1).eatReminderSeconds).isEqualTo(60)
    }

    @Test
    fun `eat reminder is not scheduled for a meal now or in the past`() {
        assertThat(plan(offset = 0).eatReminderSeconds).isNull()
        assertThat(plan(offset = -10).eatReminderSeconds).isNull()
    }

    @Test
    fun `eat reminder needs carbs that will actually be entered`() {
        assertThat(plan(carbs = 0).eatReminderSeconds).isNull()
        assertThat(plan(carbs = -10).eatReminderSeconds).isNull()
        assertThat(plan(carbs = 1).eatReminderSeconds).isEqualTo(900)
    }

    @Test
    fun `eat reminder is not scheduled unless requested`() {
        assertThat(plan(eat = false).eatReminderSeconds).isNull()
    }

    @Test
    fun `bolus reminder is scheduled only when requested and the toggle was offered`() {
        assertThat(plan(bolus = true, bolusOffered = true).bolusReminder).isTrue()
        assertThat(plan(bolus = true, bolusOffered = false).bolusReminder).isFalse()
        assertThat(plan(bolus = false, bolusOffered = true).bolusReminder).isFalse()
    }

    @Test
    fun `reminders are independent of each other`() {
        val bolusOnly = plan(offset = 0, eat = true, bolus = true, bolusOffered = true)
        assertThat(bolusOnly.eatReminderSeconds).isNull()
        assertThat(bolusOnly.bolusReminder).isTrue()

        val eatOnly = plan(offset = 20, eat = true, bolus = true, bolusOffered = false)
        assertThat(eatOnly.eatReminderSeconds).isEqualTo(1200)
        assertThat(eatOnly.bolusReminder).isFalse()
    }
}
