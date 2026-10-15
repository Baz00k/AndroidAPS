package app.aaps.plugins.main.general.persistentNotification

import android.graphics.Paint
import android.view.View
import android.widget.TextView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.ui.SensorGlucose
import app.aaps.core.objects.ui.SensorGlucoseSnapshot
import app.aaps.plugins.main.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Real RemoteViews inflation, isolated from app configuration, calculators and pump access. */
class SensorNotificationTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_700_000_000_000L

    @Suppress("DEPRECATION")
    @Test fun freshAndStaleReadoutsUseTheSameSensorSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profileUtil: ProfileUtil = mock()
        whenever(profileUtil.units).thenReturn(GlucoseUnit.MGDL)
        whenever(profileUtil.fromMgdlToUnits(any(), any())).thenAnswer { it.arguments[0] as Double }
        whenever(profileUtil.fromMgdlToStringInUnits(151.0, GlucoseUnit.MGDL)).thenReturn("151")
        whenever(profileUtil.fromMgdlToSignedStringInUnits(-15.0, GlucoseUnit.MGDL)).thenReturn("-15")
        val rh: ResourceHelper = mock()
        whenever(rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)).thenReturn("--")
        val dateUtil: DateUtil = mock()
        whenever(dateUtil.minOrSecAgo(rh, now)).thenReturn("0s ago")
        val plugin = PersistentNotificationPlugin(
            aapsLogger = mock(), rh = rh, aapsSchedulers = mock(), profileFunction = mock(),
            profileUtil = profileUtil, fabricPrivacy = mock(), activePlugins = mock(),
            iobCobCalculator = mock(), processedTbrEbData = mock(), rxBus = mock(), context = context,
            notificationHolder = mock(), dummyServiceHelper = mock(), iconsProvider = mock(), config = mock(),
            decimalFormatter = mock(), preferences = mock(), persistenceLayer = mock(), dateUtil = dateUtil
        )
        fun reading(time: Long, value: Double, trend: TrendArrow) = GV(
            timestamp = time, value = value, raw = value, noise = null,
            trendArrow = trend, sourceSensor = SourceSensor.LIBRE_3
        )
        val readings = listOf(reading(now - 300_000, 166.0, TrendArrow.SINGLE_DOWN), reading(now, 151.0, TrendArrow.FLAT))
        val snapshot = SensorGlucoseSnapshot(readings, SensorGlucose.from(readings, now))
        val builder = NotificationCompat.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
        assertTrue(plugin.applyGlucoseViews(builder, "Isolated sensor display", snapshot, now))
        val notification = builder.build()
        lateinit var view: View
        compose.setContent {
            AndroidView(factory = { notification.contentView.apply(it, null).also { inflated -> view = inflated } })
        }
        compose.runOnIdle {
            assertEquals("151", view.findViewById<TextView>(R.id.notification_bg).text.toString())
            assertEquals("Δ -15", view.findViewById<TextView>(R.id.notification_delta).text.toString())
            assertEquals("0s ago", view.findViewById<TextView>(R.id.notification_age).text.toString())
            assertEquals(View.VISIBLE, view.findViewById<View>(R.id.notification_arrow).visibility)
            assertFalse(view.findViewById<TextView>(R.id.notification_bg).paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0)
            val expanded = notification.bigContentView.apply(context, null)
            assertEquals("151", expanded.findViewById<TextView>(R.id.notification_bg).text.toString())
            assertEquals("Δ -15", expanded.findViewById<TextView>(R.id.notification_delta).text.toString())
            assertEquals("Isolated sensor display", expanded.findViewById<TextView>(R.id.notification_status).text.toString())
        }
        val stale = NotificationCompat.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
        assertTrue(plugin.applyGlucoseViews(stale, "Stale sensor display", snapshot, now + 540_000))
        compose.runOnIdle {
            stale.build().contentView.reapply(context, view)
            assertEquals("--", view.findViewById<TextView>(R.id.notification_delta).text.toString())
            assertEquals(View.GONE, view.findViewById<View>(R.id.notification_arrow).visibility)
            assertTrue(view.findViewById<TextView>(R.id.notification_bg).paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0)
        }
    }
}
