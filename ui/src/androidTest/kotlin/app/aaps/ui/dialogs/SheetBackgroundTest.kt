package app.aaps.ui.dialogs

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.core.ui.dialogs.clearSheetBackground
import app.aaps.ui.activities.OpaqueChromeActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SheetBackgroundTest {

    @Test fun theSheetContainerStaysTransparentAfterTheBehaviourHasLaidItOut() {
        ActivityScenario.launch(OpaqueChromeActivity::class.java).use { scenario ->
            lateinit var dialog: BottomSheetDialog
            scenario.onActivity { activity ->
                dialog = BottomSheetDialog(activity).apply {
                    setContentView(View(activity))
                    show()
                    // As the fragment does: after the dialog is shown, before its first layout.
                    clearSheetBackground()
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)!!
                assertThat(sheet.background).isInstanceOf(ColorDrawable::class.java)
                assertThat((sheet.background as ColorDrawable).color).isEqualTo(Color.TRANSPARENT)
                dialog.dismiss()
            }
        }
    }
}
