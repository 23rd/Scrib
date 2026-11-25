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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        manager().createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.model_download_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        val initial = notification(ModelDownloads.state.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, initial)
        }
        scope.launch {
            ModelDownloads.state.collect { state ->
                if (state.active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    manager().notify(ID, notification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        ModelDownloads.cancelAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun notification(state: ModelDownloads.Snapshot): Notification {
        val transfer = state.active.values.singleOrNull()
        val percent = transfer?.percent ?: -1
        val open = PendingIntent.getActivity(
            this, ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.model_download_channel))
            .setContentText(transfer?.model?.displayName)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .build()
    }

    companion object {
        private const val CHANNEL = "model_downloads"
        private const val ID = 2

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ModelDownloadService::class.java))
            } catch (e: IllegalStateException) {
                // The OS can forbid promotion if the app has already lost foreground access.
                Log.w("ScribDownload", "Foreground service start not allowed")
            } catch (e: SecurityException) {
                Log.w("ScribDownload", "Foreground service permission unavailable")
            }
        }
    }
}
