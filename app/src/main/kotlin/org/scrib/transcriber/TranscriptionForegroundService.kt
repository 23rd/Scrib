package org.scrib.transcriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Exists for as long as a run does, and for nothing else: a foreground service is what tells the
// system this work is the user's and must not be reclaimed while they are in another app. The
// notification is the only place the progress shows once the screen is gone.
class TranscriptionForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground(notification(TranscriptionRun.state.value))
        scope.launch {
            TranscriptionRun.state.collect { state ->
                if (state == null || !state.running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    manager().notify(NOTIFICATION_ID, notification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // The type is only part of the call from Android 10 on, and mandatory from 14.
    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(state: TranscribeUi?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val percent = state?.percent ?: -1
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(state?.fileName ?: getString(R.string.notif_transcribing))
            .setContentText(progressLine(state))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // Nothing to measure until whisper starts reporting — decoding comes first.
        builder.setProgress(100, percent.coerceIn(0, 100), percent < 0)
        return builder.build()
    }

    private fun progressLine(state: TranscribeUi?): String {
        val percent = state?.percent ?: -1
        if (percent < 0) {
            return getString(R.string.decoding_transcribing)
        }
        val eta = state?.etaMs
        return if (eta == null) {
            getString(R.string.transcribing_pct, percent)
        } else {
            getString(R.string.transcribing_pct_eta, percent, remaining(this, eta))
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_transcription), NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        manager().createNotificationChannel(channel)
    }

    private fun manager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "transcription"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, TranscriptionForegroundService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (ignore: Throwable) {
                // Losing the notification is survivable; the run itself must not go down with it.
            }
        }
    }
}

// Rounded up, and never below a minute once there is more than one to go: a countdown that ticks
// every second reads as precision the estimate does not have.
fun remaining(context: Context, ms: Long): String {
    val seconds = (ms + 999) / 1000
    return if (seconds < 60) {
        context.getString(R.string.eta_seconds, seconds.toInt())
    } else {
        context.getString(R.string.eta_minutes, ((seconds + 59) / 60).toInt())
    }
}
