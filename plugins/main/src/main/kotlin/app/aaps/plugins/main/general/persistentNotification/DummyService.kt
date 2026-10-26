package app.aaps.plugins.main.general.persistentNotification

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import dagger.android.DaggerService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

/**
 * Keeps AndroidAPS in foreground state, so it won't be terminated by Android nor get restricted by the background execution limits
 */
class DummyService : DaggerService() {

    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var notificationHolder: NotificationHolder

    private val disposable = CompositeDisposable()

    inner class LocalBinder : Binder() {

        fun getService(): DummyService = this@DummyService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundGuarded()
        disposable.add(
            rxBus
                .toObservable(EventAppExit::class.java)
                .observeOn(aapsSchedulers.io)
                .subscribe({
                               aapsLogger.debug(LTag.CORE, "EventAppExit received")
                               stopSelf()
                           }, fabricPrivacy::logException)
        )
    }

    override fun onDestroy() {
        aapsLogger.debug(LTag.CORE, "onDestroy")
        disposable.clear()
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A sticky restart of a foreground service is exempt from the background start restriction,
        // a refused start must not be retried.
        return if (startForegroundGuarded()) START_STICKY else START_NOT_STICKY
    }

    /**
     * Enters the foreground with the ongoing notification, or stops the service when Android
     * refuses the start - an escaped exception would kill the process.
     */
    internal fun startForegroundGuarded(): Boolean {
        try {
            aapsLogger.debug("Starting DummyService with ID ${notificationHolder.notificationID} notification ${notificationHolder.notification}")
            startForeground(notificationHolder.notificationID, notificationHolder.notification)
            return true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            aapsLogger.warn(LTag.CORE, "Foreground start not allowed in background, stopping: ${e.message}")
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Cannot enter foreground, stopping", e)
        }
        stopSelf()
        return false
    }
}
