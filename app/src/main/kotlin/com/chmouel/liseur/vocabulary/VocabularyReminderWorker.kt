package com.chmouel.liseur.vocabulary

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chmouel.liseur.MainActivity
import com.chmouel.liseur.R
import com.chmouel.liseur.container
import com.chmouel.liseur.ui.launch.LaunchTarget
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

internal class VocabularyReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val container = context.container
        val settings = container.appSettings.settings.first()
        if (!settings.vocabularyReminders || (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        ) return Result.success()

        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return Result.success()
        val queue = container.vocabulary.reviewQueue(null, settings.vocabularyNewLimit, settings.vocabularyReviewLimit).first()
        if (queue.dueCount == 0) {
            notifications.cancel(NOTIFICATION)
            return Result.success()
        }
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.vocabulary_title), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).setAction(LaunchTarget.VOCABULARY.action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notifications.notify(NOTIFICATION, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.vocabulary_title))
            .setContentText(context.getString(R.string.vocabulary_reminder_message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "vocabulary_review"
        private const val NOTIFICATION = 4253

        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<VocabularyReminderWorker>(1, TimeUnit.DAYS).build()
                work.enqueueUniquePeriodicWork(CHANNEL, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                work.cancelUniqueWork(CHANNEL)
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
            }
        }
    }
}
