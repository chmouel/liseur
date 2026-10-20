package com.chmouel.liseur.translate

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.chmouel.liseur.LiseurApplication
import com.chmouel.liseur.MainActivity
import com.chmouel.liseur.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WholeBookTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val coordinator get() = (application as LiseurApplication).container.wholeBookTranslations

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.whole_book_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            coordinator.stopActive()
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isActive == true) return START_NOT_STICKY
        val preview = coordinator.takePending()
        if (preview == null) {
            // Even a cancelled start must meet startForegroundService's promotion deadline.
            promote(notification("", 0, 0))
            stopSelf()
            return START_NOT_STICKY
        }
        promote(notification(preview.title, 0, preview.total))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:whole-book-translation",
        ).apply { acquire(6 * 60 * 60 * 1_000L) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var lastNotification = 0L
            try {
                coordinator.execute(preview) { done, total ->
                    val now = SystemClock.elapsedRealtime()
                    val allowed = Build.VERSION.SDK_INT < 33 ||
                        ContextCompat.checkSelfPermission(this@WholeBookTranslationService, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    if (allowed && (done == total || now - lastNotification >= 500)) {
                        getSystemService(NotificationManager::class.java).notify(ID, notification(preview.title, done, total))
                        lastNotification = now
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WholeBookTranslation", "Could not persist job state: ${e.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@WholeBookTranslationService, R.string.whole_book_notification_paused, Toast.LENGTH_LONG).show()
                }
            }
        }
        job.invokeOnCompletion {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            coordinator.release(preview.bookUrl)
        }
        worker = job
        coordinator.attachCancellation(preview.bookUrl) { job.cancel() }
        job.start()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        coordinator.stopActive("timeout")
        releaseWakeLock()
        stopSelf()
    }

    // Android 15 calls this one; only Android 16 knows the two-argument form.
    override fun onTimeout(startId: Int) {
        onTimeout(startId, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onDestroy() {
        coordinator.stopActive("interrupted")
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun promote(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification)
    }

    private fun notification(title: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this, 0, Intent(this, WholeBookTranslationService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifBlank { getString(R.string.whole_book_notification_channel) })
            .setContentText(getString(R.string.whole_book_notification_progress, done, total))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .addAction(0, getString(R.string.whole_book_notification_pause), pause)
            .build()
    }

    private companion object {
        const val CHANNEL = "whole-book-translation"
        const val ID = 8301
        const val PAUSE = "com.chmouel.liseur.translate.PAUSE_WHOLE_BOOK"
    }
}
