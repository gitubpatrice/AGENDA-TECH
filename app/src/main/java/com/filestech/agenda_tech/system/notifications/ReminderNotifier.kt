package com.filestech.agenda_tech.system.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.filestech.agenda_tech.MainActivity
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.model.Event
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the reminders notification channel and posts a reminder for a specific occurrence. Tapping
 * the notification opens the app. Posting is a no-op if the runtime notification permission
 * (Android 13+) has not been granted.
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.reminder_channel_name))
            .setDescription(context.getString(R.string.reminder_channel_desc))
            .build()
        manager.createNotificationChannel(channel)
    }

    fun postReminder(event: Event, occurrenceStartUtcMillis: Long) {
        if (!hasNotificationPermission()) {
            Timber.w("ReminderNotifier: POST_NOTIFICATIONS not granted — skipping reminder for event %d", event.id)
            return
        }
        ensureChannel()

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId(event.id, occurrenceStartUtcMillis),
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.title)
            .setContentText(contentText(event, occurrenceStartUtcMillis))
            .setWhen(occurrenceStartUtcMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(notificationId(event.id, occurrenceStartUtcMillis), notification)
    }

    private fun contentText(event: Event, occurrenceStartUtcMillis: Long): String {
        val locale = Locale.getDefault()
        val timeText = if (event.allDay) {
            context.getString(R.string.month_all_day)
        } else {
            Instant.ofEpochMilli(occurrenceStartUtcMillis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        }
        return event.location?.takeIf { it.isNotBlank() }?.let { "$timeText · $it" } ?: timeText
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /** Stable per-occurrence id so a re-post updates rather than duplicates. */
    private fun notificationId(eventId: Long, occurrenceStartUtcMillis: Long): Int =
        (eventId * 31 + occurrenceStartUtcMillis).hashCode()

    private companion object {
        const val CHANNEL_ID = "reminders"
    }
}
