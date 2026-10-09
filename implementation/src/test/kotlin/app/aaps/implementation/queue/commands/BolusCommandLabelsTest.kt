package app.aaps.implementation.queue.commands

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandAction
import app.aaps.core.interfaces.resources.ResourceHelper
import com.google.common.truth.Truth.assertThat
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class BolusCommandLabelsTest {
    private val rh = Mockito.mock(ResourceHelper::class.java) { invocation ->
        val template = when (invocation.arguments[0]) {
            app.aaps.core.ui.R.string.bolus_u_min                 -> "BOLUS %1\$s U"
            app.aaps.core.ui.R.string.smb_bolus_u                 -> "SMB BOLUS %1\$s U"
            app.aaps.core.ui.R.string.extended_bolus_u_min        -> "EXTENDED BOLUS %1\$s U %2\$d min"
            app.aaps.core.ui.R.string.format_insulin_units_label -> "%1\$s U"
            else                                                -> error("Unexpected resource")
        }
        template.format(*invocation.arguments.drop(1).toTypedArray())
    }
    private val injector = HasAndroidInjector {
        AndroidInjector<Any> { command ->
            when (command) {
                is CommandBolus -> command.rh = rh
                is CommandSMBBolus -> command.rh = rh
                is CommandExtendedBolus -> command.rh = rh
            }
        }
    }

    @Test fun `manual SMB and extended queue labels retain doses without changing command actions`() {
        val info = DetailedBolusInfo().apply { insulin = 0.025 }
        val manual = CommandBolus(injector, info, null, Command.CommandType.BOLUS, Runnable {})
        val smb = CommandSMBBolus(injector, info, null)
        val extended = CommandExtendedBolus(injector, 0.025, 30, null)
        assertThat(manual.status().replace(',', '.')).isEqualTo("BOLUS 0.025 U")
        assertThat(manual.log().replace(',', '.')).isEqualTo("BOLUS 0.025 U")
        assertThat(smb.status().replace(',', '.')).isEqualTo("SMB BOLUS 0.025 U")
        assertThat(smb.log().replace(',', '.')).isEqualTo("SMB BOLUS 0.025 U")
        assertThat(extended.status().replace(',', '.')).isEqualTo("EXTENDED BOLUS 0.025 U 30 min")
        assertThat(manual.action).isEqualTo(CommandAction.Bolus(0.025))
        assertThat(smb.action).isEqualTo(CommandAction.AutomaticBolus(0.025))
        assertThat(extended.action).isEqualTo(CommandAction.ExtendedBolus(0.025, 30))
        assertThat(info.insulin).isEqualTo(0.025)
    }

    @Test fun `carbs-only command still has no insulin label`() {
        val command = CommandBolus(injector, DetailedBolusInfo(), null, Command.CommandType.BOLUS, Runnable {})
        assertThat(command.status()).isEmpty()
        assertThat(command.log()).isEmpty()
    }
}
