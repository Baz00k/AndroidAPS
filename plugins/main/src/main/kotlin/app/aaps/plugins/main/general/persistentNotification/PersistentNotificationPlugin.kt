package app.aaps.plugins.main.general.persistentNotification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventProfileAvailabilityChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.ui.TherapyDisplay
import app.aaps.core.objects.ui.BasalDisplay
import app.aaps.core.objects.ui.SensorGlucose
import app.aaps.core.objects.ui.SensorGlucoseSnapshot
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.plugins.main.R
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("PrivatePropertyName", "DEPRECATION")
@Singleton
class PersistentNotificationPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val aapsSchedulers: AapsSchedulers,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val activePlugins: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val rxBus: RxBus,
    private val context: Context,
    private val notificationHolder: NotificationHolder,
    private val dummyServiceHelper: DummyServiceHelper,
    private val iconsProvider: IconsProvider,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val config: Config,
    private val decimalFormatter: DecimalFormatter,
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .neverVisible(true)
        .pluginName(R.string.ongoingnotificaction)
        .enableByDefault(true)
        .alwaysEnabled(true)
        .showInList { false }
        .description(R.string.description_persistent_notification),
    aapsLogger, rh
) {

    // For Android Auto
    // Intents are not declared in manifest and not consumed, this is intentionally because actually we can't do anything with
    private val PACKAGE = "info.nightscout"
    private val READ_ACTION = "info.nightscout.androidaps.ACTION_MESSAGE_READ"
    private val REPLY_ACTION = "info.nightscout.androidaps.ACTION_MESSAGE_REPLY"
    private val CONVERSATION_ID = "conversation_id"
    private val EXTRA_VOICE_REPLY = "extra_voice_reply"
    // End Android auto

    private val disposable = CompositeDisposable()

    /** Resolves its palette from the device configuration, so it is built once and not per update. */
    private val chart by lazy { BgNotificationChart(context) }

    override fun onStart() {
        super.onStart()
        notificationHolder.createNotificationChannel()
        // Ages the reading and its stale styling; the foreground service is already running.
        disposable += Observable.interval(1, 1, TimeUnit.MINUTES, aapsSchedulers.io)
            .subscribe({ updateNotification() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewBG::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventInitializationChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAutosensCalculationFinished::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventProfileAvailabilityChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
    }

    override fun onStop() {
        disposable.clear()
        dummyServiceHelper.stopService(context)
        super.onStop()
    }

    internal fun triggerNotificationUpdate() {
        updateNotification()
        dummyServiceHelper.startService(context)
    }

    @Synchronized internal fun updateNotification() {
        if (!config.appInitialized) return
        val now = dateUtil.now()
        val snapshot = try {
            SensorGlucoseSnapshot.load(persistenceLayer, now - CHART_WINDOW, now)
        } catch (e: Exception) {
            fabricPrivacy.logException(e)
            SensorGlucoseSnapshot(emptyList(), SensorGlucose())
        }
        val glucose = snapshot.glucose
        val pump = activePlugins.activePump
        var line1: String?
        var line2: String? = null
        var line3: String? = null
        var tempBasalText: String? = null
        var avgDeltaText: String? = null
        var unreadConversationBuilder: NotificationCompat.CarExtender.UnreadConversation.Builder? = null
        val therapy = TherapyDisplay.load(profileFunction, iobCobCalculator, rh, decimalFormatter, "PersistentNotificationPlugin", compact = true) {
            BasalDisplay(rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, pump.baseBasalRate))
        }
        val profileValid = therapy.iob.isAvailable
        if (profileValid) {
            var line1aa: String
            val lastBG = glucose.reading
            if (lastBG != null) {
                line1aa = profileUtil.fromMgdlToStringInUnits(lastBG.value)
                line1 = line1aa
                if (glucose.isFresh(now)) {
                    // Android bundles this notification with AAPS's alarms, and a bundled child is
                    // rendered as ONE ellipsized line built from title + text - the custom views
                    // below are not drawn in that state. So the title carries only what has to
                    // survive truncation: value, trend, delta. The rest moved to line2.
                    line1 += "  " + glucose.trendSymbol(now) +
                        "  Δ" + (glucose.deltaMgdl?.let { profileUtil.fromMgdlToSignedStringInUnits(it) }
                            ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short))
                    line1aa += "  " + glucose.trendSymbol(now)
                    glucoseStatusProvider.glucoseStatusData
                        ?.let { avgDeltaText = "avgΔ" + profileUtil.fromMgdlToSignedStringInUnits(it.shortAvgDelta) }
                } else {
                    line1 += " " +
                        rh.gs(R.string.old_data) +
                        " "
                    line1aa += "$line1."
                }
            } else {
                line1aa = rh.gs(app.aaps.core.ui.R.string.missed_bg_readings)
                line1 = line1aa
            }
            val activeTemp = processedTbrEbData.getTempBasalIncludingConvertedExtended(System.currentTimeMillis())
            if (activeTemp != null) {
                tempBasalText = activeTemp.toStringShort(rh)
                line1aa += "  " + tempBasalText + "."
            }
            //IOB
            val iob = therapy.iob
            val cob = therapy.cob
            line2 =
                rh.gs(app.aaps.core.ui.R.string.treatments_iob_label_string) + " " + iob.total + " " + rh.gs(
                    app.aaps.core.ui.R
                        .string.cob
                ) + ": " + cob.value
            // Displaced from the title so it stays short enough to read when bundled.
            listOfNotNull(tempBasalText, avgDeltaText).forEach { line2 += "  " + it }
            if (cob.unavailableReason.isNotBlank()) line2 += " · " + cob.unavailableReason
            val line2aa =
                rh.gs(app.aaps.core.ui.R.string.treatments_iob_label_string) + " " + iob.total + ". " + rh.gs(
                    app.aaps.core.ui.R
                        .string.cob
                ) + ": " + cob.value + "."
            line3 = therapy.basal.rate
            var line3aa = therapy.basal.rate + "."
            line3 += " - " + profileFunction.getProfileName()
            line3aa += " - " + profileFunction.getProfileName() + "."
            /// For Android Auto
            val msgReadIntent = Intent()
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .setAction(READ_ACTION)
                .putExtra(CONVERSATION_ID, notificationHolder.notificationID)
                .setPackage(PACKAGE)
            val msgReadPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationHolder.notificationID,
                msgReadIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val msgReplyIntent = Intent()
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .setAction(REPLY_ACTION)
                .putExtra(CONVERSATION_ID, notificationHolder.notificationID)
                .setPackage(PACKAGE)
            val msgReplyPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationHolder.notificationID,
                msgReplyIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // Build a RemoteInput for receiving voice input from devices
            val remoteInput = RemoteInput.Builder(EXTRA_VOICE_REPLY).build()
            // Create the UnreadConversation
            unreadConversationBuilder = NotificationCompat.CarExtender.UnreadConversation.Builder(line1aa + "\n" + line2aa)
                .setLatestTimestamp(System.currentTimeMillis())
                .setReadPendingIntent(msgReadPendingIntent)
                .setReplyAction(msgReplyPendingIntent, remoteInput)
            /// Add dot to produce a "more natural sounding result"
            unreadConversationBuilder.addMessage(line3aa)
            /// End Android Auto
        } else {
            line1 = therapy.iob.unavailableReason
            val unavailable = rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)
            line2 = rh.gs(app.aaps.core.ui.R.string.treatments_iob_label_string) + " " + unavailable + " · " +
                rh.gs(app.aaps.core.ui.R.string.cob) + ": " + unavailable
            line3 = rh.gs(app.aaps.core.ui.R.string.basal) + ": " + unavailable
        }
        val builder = NotificationCompat.Builder(context, notificationHolder.channelID)
        builder.setOngoing(true)
        builder.setOnlyAlertOnce(true)
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
        builder.setSmallIcon(iconsProvider.getNotificationIcon())
        builder.setContentTitle(line1)
        if (line2 != null) builder.setContentText(line2)
        if (line3 != null) builder.setSubText(line3)
        // The text above is still the notification's content: Android Auto, wearables and TalkBack
        // read it, and SystemUI falls back to it if it declines to inflate the custom views.
        val decorated = profileValid && applyGlucoseViews(builder, listOfNotNull(line2, line3).joinToString("  \u00b7  "), snapshot, now)
        // The app icon is redundant beside the chart, and its bitmap competes for the same 1 MB
        // Binder budget the custom views spend.
        if (!decorated) builder.setLargeIcon(rh.decodeResource(iconsProvider.getIcon()))
        /// Android Auto
        if (unreadConversationBuilder != null) {
            builder.extend(
                NotificationCompat.CarExtender()
                    .setUnreadConversation(unreadConversationBuilder.build())
            )
        }
        /// End Android Auto
        builder.setContentIntent(notificationHolder.openAppIntent(context))
        val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = builder.build()
        mNotificationManager.notify(notificationHolder.notificationID, notification)
        notificationHolder.notification = notification
    }

    /**
     * Attaches the lock-screen readout: the glucose value at a size that survives being glanced at,
     * a trend arrow, and the last 3 h of glucose as a chart.
     *
     * This exists because the plain-text notification put BG, delta, average delta and the temp
     * basal into one contentTitle, which the lock screen then ellipsized mid-string. Those numbers
     * are not lost - they moved to the expanded view's status row, where there is room for them.
     *
     * @return true if the custom views were attached, false if there is nothing to draw.
     */
    internal fun applyGlucoseViews(builder: NotificationCompat.Builder, statusText: String, snapshot: SensorGlucoseSnapshot, now: Long): Boolean {
        val glucose = snapshot.glucose
        val lastBg = glucose.reading ?: return false
        val lowMark = preferences.get(UnitDoubleKey.OverviewLowMark)
        val highMark = preferences.get(UnitDoubleKey.OverviewHighMark)
        // One authority for the colour: the number and the trace are tinted by the same call, so
        // they cannot disagree about whether the reading is in range.
        val color = chart.colorFor(profileUtil.fromMgdlToUnits(lastBg.value), lowMark, highMark)
        val arrow = glucose.trend.takeIf { glucose.isFresh(now) }?.notificationIcon()
        val delta = glucose.deltaMgdl.takeIf { glucose.isFresh(now) }
            ?.let { "\u0394 " + profileUtil.fromMgdlToSignedStringInUnits(it) }
            ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

        val collapsed = RemoteViews(context.packageName, R.layout.notification_bg_collapsed)
        val expanded = RemoteViews(context.packageName, R.layout.notification_bg_expanded)

        for (views in listOf(collapsed, expanded)) {
            views.setTextViewText(R.id.notification_bg, profileUtil.fromMgdlToStringInUnits(lastBg.value))
            views.setTextColor(R.id.notification_bg, color)
            // Struck through once the reading is stale, as the widget and Overview both do.
            views.setInt(
                R.id.notification_bg, "setPaintFlags",
                if (glucose.isFresh(now)) Paint.ANTI_ALIAS_FLAG
                else Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
            )
            arrow?.let { views.setImageViewResource(R.id.notification_arrow, it) }
            views.setViewVisibility(R.id.notification_arrow, if (arrow != null) View.VISIBLE else View.GONE)
            views.setInt(R.id.notification_arrow, "setColorFilter", color)
            views.setTextViewText(R.id.notification_delta, delta)
            views.setTextViewText(R.id.notification_age, dateUtil.minOrSecAgo(rh, lastBg.timestamp))
        }
        expanded.setTextViewText(R.id.notification_status, statusText)

        val from = now - CHART_WINDOW
        val readings = snapshot.readings
            // xDrip broadcasts each reading more than once, so the table holds near-duplicates.
            // One point per 5-minute bucket keeps the trace from doubling back on itself.
            .associateBy { it.timestamp / T.mins(5).msecs() }
            .values
            .map { ChartReading(it.timestamp, profileUtil.fromMgdlToUnits(it.value)) }
        chart.render(BgNotificationChart.COLLAPSED_W, BgNotificationChart.COLLAPSED_H, readings, from, now, lowMark, highMark, withMarks = false)
            ?.let { collapsed.setImageViewBitmap(R.id.notification_chart, it) }
        chart.render(BgNotificationChart.EXPANDED_W, BgNotificationChart.EXPANDED_H, readings, from, now, lowMark, highMark, withMarks = true)
            ?.let { expanded.setImageViewBitmap(R.id.notification_chart, it) }

        builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        builder.setCustomContentView(collapsed)
        builder.setCustomBigContentView(expanded)
        return true
    }

    companion object {

        /** How much history the chart shows. Long enough to read a meal, short enough to stay legible. */
        private val CHART_WINDOW = T.hours(3).msecs()
    }
}

/**
 * Trend arrows that survive being drawn in a notification.
 *
 * core:objects' arrows fill with `?attr/defaultTextColor`. The home-screen widget supplies that
 * attribute via android:theme on its root, but a notification is inflated in SystemUI's theme where
 * it does not exist, so the vector draws transparent - and setColorFilter cannot tint transparent
 * pixels away. These are the same paths with a solid fill, so the tint takes.
 */
private fun TrendArrow.notificationIcon(): Int? = when (this) {
    TrendArrow.TRIPLE_DOWN     -> R.drawable.notification_arrow_invalid
    TrendArrow.DOUBLE_DOWN     -> R.drawable.notification_arrow_doubledown
    TrendArrow.SINGLE_DOWN     -> R.drawable.notification_arrow_singledown
    TrendArrow.FORTY_FIVE_DOWN -> R.drawable.notification_arrow_fortyfivedown
    TrendArrow.FLAT            -> R.drawable.notification_arrow_flat
    TrendArrow.FORTY_FIVE_UP   -> R.drawable.notification_arrow_fortyfiveup
    TrendArrow.SINGLE_UP       -> R.drawable.notification_arrow_singleup
    TrendArrow.DOUBLE_UP       -> R.drawable.notification_arrow_doubleup
    TrendArrow.TRIPLE_UP       -> R.drawable.notification_arrow_invalid
    // No trend to show is better than a question mark taking the arrow's slot.
    TrendArrow.NONE            -> null
}
