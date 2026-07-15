package com.filestech.agenda_tech.system.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.filestech.agenda_tech.MainActivity
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the reminders notification channel (from the user's [AppSettings]) and posts a reminder
 * for a specific occurrence. Tapping opens the app. Posting is a no-op if the runtime notification
 * permission (Android 13+) is not granted.
 *
 * Android caches a channel's sound/vibration/lock-screen config after creation, so changing those
 * settings recreates the channel ([rebuildChannel]) — the user can still override everything from
 * the system notification settings.
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val compatManager = NotificationManagerCompat.from(context)
    private val platformManager = context.getSystemService(NotificationManager::class.java)

    // Audit DATA-1 — serialise channel create/delete so a concurrent rebuild can never leave the
    // channel momentarily absent while a reminder is being posted.
    private val channelMutex = Mutex()

    suspend fun ensureChannel() = channelMutex.withLock {
        createChannel(settingsRepository.current())
    }

    /** Recreate the channel to apply changed sound/vibration/lock-screen preferences. */
    suspend fun rebuildChannel() = channelMutex.withLock {
        platformManager.deleteNotificationChannel(CHANNEL_ID)
        createChannel(settingsRepository.current())
    }

    /**
     * The picked sound, or null if it can no longer be opened. Parsing a URI proves nothing — only
     * actually opening the stream does — so this is what separates a real fallback from a mute channel.
     */
    private fun readableSoundUri(raw: String): Uri? {
        val uri = runCatching { raw.toUri() }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { }
            uri
        }.getOrElse {
            Timber.w("ReminderNotifier: custom reminder sound is no longer readable, using the default")
            null
        }
    }

    private fun createChannel(settings: AppSettings) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminder_channel_desc)
            enableVibration(settings.notifVibrate)
            // Sound off > custom sound (only if still readable) > system default. A picked sound can
            // die later (file deleted, SD card pulled, permission lost): silently keeping a dead URI
            // would make the channel mute and the user would miss reminders without ever knowing.
            if (!settings.notifSound) {
                setSound(null, null)
            } else {
                val picked = settings.notifSoundUri?.let(::readableSoundUri)
                if (picked != null) {
                    val attributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .build()
                    setSound(picked, attributes)
                }
                // else: leave the channel's default sound — an audible fallback beats silence.
            }
            lockscreenVisibility = if (settings.notifLockScreen) {
                Notification.VISIBILITY_PRIVATE
            } else {
                Notification.VISIBILITY_SECRET
            }
        }
        platformManager.createNotificationChannel(channel)
    }

    suspend fun postReminder(event: Event, occurrenceStartUtcMillis: Long) {
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

        // Audit SEC-2 — keep user-entered title/location off a locked screen (PRIVATE + generic public).
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.reminder_channel_name))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.title)
            .setContentText(contentText(event, occurrenceStartUtcMillis))
            .setWhen(occurrenceStartUtcMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(contentIntent)
            .build()

        compatManager.notify(notificationId(event.id, occurrenceStartUtcMillis), notification)
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
