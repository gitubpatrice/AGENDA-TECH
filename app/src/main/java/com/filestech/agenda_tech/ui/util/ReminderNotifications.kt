package com.filestech.agenda_tech.ui.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.filestech.agenda_tech.R
import timber.log.Timber

/**
 * POST_NOTIFICATIONS, asked where reminders are actually set up rather than at launch.
 *
 * Until 1.0.3 `MainActivity.onCreate` fired the request on the very first frame of a fresh install —
 * before the user had created anything to be reminded of — and again on the next launch. The F-Droid
 * inclusion checklist asks the opposite: at startup an app should request only what its *core*
 * function needs, and everything else at the moment the matching feature is used. READ_CALENDAR
 * already worked that way (it is asked from the device-import screen); this brings reminders in line.
 *
 * The predicate is [NotificationManagerCompat.areNotificationsEnabled], not `checkSelfPermission`.
 * The question the user cares about is "will this reminder reach me", and below API 33 — where the
 * permission does not exist at all — the app-level notification switch is the only thing that can
 * answer it. `ReminderNotifier` keeps the narrower permission check: it guards a `notify()` call,
 * which is a different question with a different answer below API 33.
 */
@Immutable
class ReminderNotifications(
    /** True when a reminder posted right now would actually reach the user. */
    val enabled: Boolean,
    /**
     * Ask for the permission, or open the system notification settings once the system dialog can no
     * longer appear. Safe to call when [enabled] is already true — it then does nothing.
     */
    val request: () -> Unit,
)

/**
 * Tracks whether reminders can be delivered and hands back the one action that fixes it.
 *
 * The state is re-read on every resume rather than sampled once: the user leaves the app to flip the
 * switch in the system settings, and a value sampled at first composition would never be retried —
 * the notice would sit there claiming reminders are blocked after they had been unblocked.
 */
@Composable
fun rememberReminderNotifications(): ReminderNotifications {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var enabled by remember { mutableStateOf(notificationsEnabled(context)) }

    // Two refusals and Android stops showing the dialog for good; `launch` then returns denied
    // without any UI, which would look like a dead button. Detected from the result rather than
    // persisted: `shouldShowRequestPermissionRationale` is false BOTH before the first ask and after
    // the last one, so it can only be read after a denial, never before.
    //
    // Known limit, accepted rather than papered over: this flag lives with the screen, so a user who
    // refused twice, left, and came back spends ONE tap on a launch that returns instantly before the
    // next tap reaches the settings. Closing that would mean persisting an "already asked" flag in
    // AppSettings — a DataStore field wired in three places to save a single tap in a path the user
    // has already told us twice they do not want.
    var dialogExhausted by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = notificationsEnabled(context)
        if (!granted) {
            dialogExhausted = activity == null ||
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
        }
    }

    LifecycleResumeEffect(Unit) {
        enabled = notificationsEnabled(context)
        onPauseOrDispose { /* nothing to release */ }
    }

    return remember(enabled, dialogExhausted, launcher, context) {
        ReminderNotifications(enabled = enabled) {
            when {
                notificationsEnabled(context) -> Unit
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !dialogExhausted ->
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> openNotificationSettings(context)
            }
        }
    }
}

/**
 * Shown next to a reminder the user has just set that could not be delivered — the "explain, then
 * offer the way out" half of moving the request out of startup. Deliberately a notice and not a
 * dialog: the reminder is still saved and still re-armed after a reboot, so nothing is blocked.
 */
@Composable
fun ReminderNotificationsBlockedNotice(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsOff,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.reminder_notifications_blocked_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text = stringResource(R.string.reminder_notifications_blocked_body),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onAllow, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.reminder_notifications_blocked_action))
            }
        }
    }
}

private fun notificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * The app's own notification page, which is where a user sent back by a permanently-denied dialog
 * has to land. Falls back to the app details page on the rare OEM build that does not answer the
 * first intent — better than a button that silently does nothing.
 */
private fun openNotificationSettings(context: Context) {
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val appDetails = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    runCatching { context.startActivity(notificationSettings) }
        .recoverCatching { context.startActivity(appDetails) }
        .onFailure { Timber.w(it, "ReminderNotifications: no settings activity answered") }
}
