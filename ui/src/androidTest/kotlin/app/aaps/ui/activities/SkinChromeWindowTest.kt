package app.aaps.ui.activities

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.core.ui.activities.followSkinChrome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranslucentChromeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        followSkinChrome()
    }
}

class OpaqueChromeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        followSkinChrome()
    }
}

class SkinChromeWindowTest {

    @Test fun aTranslucentWindowStaysSeeThroughSoTheSheetOnItShowsTheScreenBehind() {
        ActivityScenario.launch(TranslucentChromeActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertThat(Color.alpha(windowColor(it))).isEqualTo(0) }
        }
    }

    private fun windowColor(activity: Activity) = (activity.window.decorView.background as? ColorDrawable)?.color ?: Color.TRANSPARENT
}
