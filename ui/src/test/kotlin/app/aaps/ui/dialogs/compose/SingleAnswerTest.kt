package app.aaps.ui.dialogs.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SingleAnswerTest {

    private var delivered = 0
    private var cancelled = 0
    private var dismissals = 0
    private val answer = SingleAnswer(ok = { delivered++ }, cancel = { cancelled++ })
    private val dismiss: () -> Unit = { dismissals++ }

    @Test
    fun `a confirm delivered twice starts one delivery`() {
        answer.confirm(dismiss)
        answer.confirm(dismiss)
        assertThat(delivered).isEqualTo(1)
        assertThat(dismissals).isEqualTo(1)
    }

    @Test
    fun `a confirm after cancel delivers nothing`() {
        answer.cancel(dismiss)
        answer.confirm(dismiss)
        assertThat(delivered).isEqualTo(0)
        assertThat(cancelled).isEqualTo(1)
    }

    @Test
    fun `a confirm after the dialog was dismissed by back delivers nothing`() {
        answer.dismissed()
        answer.confirm(dismiss)
        assertThat(delivered).isEqualTo(0)
        assertThat(cancelled).isEqualTo(0)
        assertThat(dismissals).isEqualTo(0)
    }
}
