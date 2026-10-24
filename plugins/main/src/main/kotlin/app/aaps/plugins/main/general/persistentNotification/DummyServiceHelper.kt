package app.aaps.plugins.main.general.persistentNotification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton

/*
    This code replaces  following
    val alarm = Intent(context, DummyService::class.java)
    alarm.putExtra("soundId", n.soundId)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(alarm) else context.startService(alarm)

    it fails randomly with error
    Context.startForegroundService() did not then call Service.startForeground(): ServiceRecord{e317f7e u0 info.nightscout.nsclient/info.nightscout.androidaps.services.DummyService}

 */
@Singleton
class DummyServiceHelper @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    fun startService(context: Context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                // The binder of the service that returns the instance that is created.
                val binder: DummyService.LocalBinder = service as DummyService.LocalBinder

                val dummyService: DummyService = binder.getService()

                // The bind above only created the service. This is what keeps it running once the
                // connection is released.
                if (!startForegroundService(context)) {
                    dummyService.stopSelf()
                    context.unbindService(this)
                    return
                }

                // This is the key: Without waiting Android Framework to call this method
                // inside Service.onCreate(), immediately call here to post the notification.
                dummyService.startForegroundGuarded()

                // Release the connection to prevent leaks.
                context.unbindService(this)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
            }
        }

        try {
            context.bindService(Intent(context, DummyService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (ignored: RuntimeException) {
            // This is probably a broadcast receiver context even though we are calling getApplicationContext().
            // Just call startForegroundService instead since we cannot bind a service to a
            // broadcast receiver context. The service also have to call startForeground in
            // this case.
            startForegroundService(context)
        }
    }

    /**
     * Starts the service, so it outlives the binder connection above.
     *
     * Android refuses this when the app is in the background - a BOOT_COMPLETED receiver is one of
     * the cases it no longer accepts - and the refusal must stay out of the calling process.
     *
     * @return true if Android accepted the start
     */
    private fun startForegroundService(context: Context): Boolean =
        try {
            context.startForegroundService(Intent(context, DummyService::class.java))
            true
        } catch (e: Exception) {
            aapsLogger.warn(LTag.CORE, "Cannot start DummyService in background: ${e.message}")
            false
        }

    fun stopService(context: Context) {
        context.stopService(Intent(context, DummyService::class.java))
    }
}
