package com.filestech.agenda_tech.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.model.CalendarColor
import com.filestech.agenda_tech.ui.lock.MIN_PIN_LENGTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.filestech.agenda_tech.domain.settings.AppSettings
import com.filestech.agenda_tech.domain.settings.ThemeMode
import com.filestech.agenda_tech.domain.settings.WeekStart

private val DURATION_OPTIONS = listOf(15, 30, 45, 60, 90, 120, 240)
private val REMINDER_OPTIONS = listOf(AppSettings.NO_DEFAULT_REMINDER, 0, 5, 10, 15, 30, 60, 24 * 60)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenCalendars: () -> Unit,
    onOpenDeviceImport: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lockState by viewModel.lockState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val biometricAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    // Replacing the sound: hand back the grant we no longer need, so picking files repeatedly doesn't
    // pile up persisted permissions against the per-app cap.
    val replaceSound = { newUri: String? ->
        releasePersistedGrant(context, settings.notifSoundUri, keeping = newUri)
        viewModel.setNotifSoundUri(newUri)
    }

    // System ringtone picker: returns the picked URI (null = "Silent"/none → keep the system default).
    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = IntentCompat.getParcelableExtra(
                result.data ?: Intent(),
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                Uri::class.java,
            )
            replaceSound(uri?.toString())
        }
    }

    // Any audio file (MP3…) from the device storage. Unlike a system ringtone, a document URI is only
    // ours for this process unless we take a persistable grant — without it the sound would go silent
    // after a reboot.
    val audioFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val kept = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            if (kept) {
                replaceSound(uri.toString())
            } else {
                // No lasting access = a reminder that would fall silent later. Refuse rather than lie.
                Toast.makeText(context, R.string.settings_notif_sound_file_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    var showPinDialog by remember { mutableStateOf(false) }
    // LOCK-6 — when a lock already exists, disabling it or changing the PIN first re-auths the user.
    var verifyPurpose by remember { mutableStateOf<VerifyPurpose?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_section_appearance))
            ChoiceRow(
                title = stringResource(R.string.settings_theme),
                currentLabel = stringResource(themeLabelRes(settings.themeMode)),
                options = ThemeMode.entries.map { it to stringResource(themeLabelRes(it)) },
                onSelect = viewModel::setThemeMode,
            )
            ChoiceRow(
                title = stringResource(R.string.settings_week_start),
                currentLabel = stringResource(weekStartLabelRes(settings.weekStart)),
                options = WeekStart.entries.map { it to stringResource(weekStartLabelRes(it)) },
                onSelect = viewModel::setWeekStart,
            )
            SwitchRow(
                title = stringResource(R.string.settings_show_week_numbers),
                checked = settings.showWeekNumbers,
                onCheckedChange = viewModel::setShowWeekNumbers,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_events))
            ClickRow(title = stringResource(R.string.settings_calendars), onClick = onOpenCalendars)
            ClickRow(
                title = stringResource(R.string.settings_device_import),
                subtitle = stringResource(R.string.settings_device_import_sub),
                onClick = onOpenDeviceImport,
            )
            ColorRow(
                title = stringResource(R.string.settings_default_color),
                selected = settings.defaultEventColor,
                onSelect = viewModel::setDefaultColor,
            )
            ChoiceRow(
                title = stringResource(R.string.settings_default_duration),
                currentLabel = durationLabel(context, settings.defaultDurationMinutes),
                options = DURATION_OPTIONS.map { it to durationLabel(context, it) },
                onSelect = viewModel::setDefaultDuration,
            )
            ChoiceRow(
                title = stringResource(R.string.settings_default_reminder),
                currentLabel = reminderLabel(context, settings.defaultReminderMinutes),
                options = REMINDER_OPTIONS.map { it to reminderLabel(context, it) },
                onSelect = viewModel::setDefaultReminder,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_notifications))
            SwitchRow(
                title = stringResource(R.string.settings_notif_sound),
                checked = settings.notifSound,
                onCheckedChange = viewModel::setNotifSound,
            )
            if (settings.notifSound) {
                ClickRow(
                    title = stringResource(R.string.settings_notif_ringtone),
                    subtitle = ringtoneTitle(settings.notifSoundUri),
                    onClick = { ringtonePicker.launch(ringtonePickerIntent(context, settings.notifSoundUri)) },
                )
                ClickRow(
                    title = stringResource(R.string.settings_notif_sound_file),
                    subtitle = stringResource(R.string.settings_notif_sound_file_sub),
                    onClick = { audioFilePicker.launch(AUDIO_MIME_TYPES) },
                )
                if (settings.notifSoundUri != null) {
                    ClickRow(
                        title = stringResource(R.string.settings_notif_sound_reset),
                        onClick = { replaceSound(null) },
                    )
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_notif_vibrate),
                checked = settings.notifVibrate,
                onCheckedChange = viewModel::setNotifVibrate,
            )
            SwitchRow(
                title = stringResource(R.string.settings_notif_lockscreen),
                checked = settings.notifLockScreen,
                onCheckedChange = viewModel::setNotifLockScreen,
            )
            ClickRow(
                title = stringResource(R.string.settings_notif_system),
                onClick = { openAppNotificationSettings(context) },
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_privacy))
            ClickRow(
                title = stringResource(R.string.settings_backup),
                subtitle = stringResource(R.string.settings_backup_sub),
                onClick = onOpenBackup,
            )
            SwitchRow(
                title = stringResource(R.string.settings_flag_secure),
                subtitle = stringResource(R.string.settings_flag_secure_sub),
                checked = settings.flagSecure,
                onCheckedChange = viewModel::setFlagSecure,
            )
            SwitchRow(
                title = stringResource(R.string.settings_widget_hide_titles),
                subtitle = stringResource(R.string.settings_widget_hide_titles_sub),
                checked = settings.widgetHideTitles,
                onCheckedChange = viewModel::setWidgetHideTitles,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_security))
            SwitchRow(
                title = stringResource(R.string.settings_lock_enabled),
                subtitle = stringResource(R.string.settings_lock_enabled_sub),
                checked = lockState.lockEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) showPinDialog = true else verifyPurpose = VerifyPurpose.DISABLE
                },
            )
            if (lockState.lockEnabled) {
                ClickRow(
                    title = stringResource(R.string.settings_lock_change_pin),
                    onClick = { verifyPurpose = VerifyPurpose.CHANGE },
                )
                if (biometricAvailable) {
                    SwitchRow(
                        title = stringResource(R.string.settings_lock_biometric),
                        checked = lockState.biometricEnabled,
                        onCheckedChange = viewModel::setBiometricEnabled,
                    )
                }
            }

            HorizontalDivider()
            ClickRow(title = stringResource(R.string.settings_about), onClick = onOpenAbout)
        }
    }

    if (showPinDialog) {
        SetPinDialog(
            onConfirm = { pin ->
                viewModel.setPin(pin)
                showPinDialog = false
            },
            onDismiss = { showPinDialog = false },
        )
    }

    verifyPurpose?.let { purpose ->
        val throttleSeconds by viewModel.throttleSeconds.collectAsStateWithLifecycle()
        VerifyPinDialog(
            verify = { viewModel.verifyPin(it) },
            throttleSeconds = throttleSeconds,
            onSuccess = {
                verifyPurpose = null
                when (purpose) {
                    VerifyPurpose.DISABLE -> viewModel.disableLock()
                    VerifyPurpose.CHANGE -> showPinDialog = true
                }
            },
            onDismiss = { verifyPurpose = null },
        )
    }
}

private enum class VerifyPurpose { DISABLE, CHANGE }

@Composable
private fun SetPinDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length >= MIN_PIN_LENGTH && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_lock_set_pin)) },
        text = {
            Column {
                PinField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = stringResource(R.string.lock_pin),
                )
                PinField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(R.string.settings_lock_confirm_pin),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = valid) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** LOCK-6 — confirms the current PIN before a sensitive change (disable lock / change PIN). */
@Composable
private fun VerifyPinDialog(
    verify: suspend (String) -> Boolean,
    throttleSeconds: Int,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val throttled = throttleSeconds > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_lock_confirm_current)) },
        text = {
            Column {
                PinField(
                    value = pin,
                    onValueChange = {
                        pin = it
                        wrong = false
                    },
                    label = stringResource(R.string.lock_pin),
                    isError = wrong,
                    supportingText = if (wrong) stringResource(R.string.lock_wrong_pin) else null,
                )
                if (throttled) {
                    Text(
                        text = stringResource(R.string.lock_throttled, throttleSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length >= MIN_PIN_LENGTH && !checking && !throttled,
                onClick = {
                    checking = true
                    scope.launch {
                        if (verify(pin)) onSuccess() else wrong = true
                        checking = false
                    }
                },
            ) { Text(stringResource(R.string.action_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= MAX_PIN_LENGTH) onValueChange(it) },
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

private const val MAX_PIN_LENGTH = 12

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ClickRow(title: String, onClick: () -> Unit, subtitle: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    currentLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ColorRow(
    title: String,
    selected: CalendarColor,
    onSelect: (CalendarColor) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CalendarColor.entries.forEach { color ->
                val ringColor = MaterialTheme.colorScheme.onSurface
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(color.argb))
                        .then(
                            if (color == selected) {
                                Modifier.border(2.dp, ringColor, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(color) },
                )
            }
        }
    }
}

// --- label helpers ----------------------------------------------------------

private fun themeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private fun weekStartLabelRes(weekStart: WeekStart): Int = when (weekStart) {
    WeekStart.SYSTEM -> R.string.week_start_system
    WeekStart.MONDAY -> R.string.week_start_monday
    WeekStart.SATURDAY -> R.string.week_start_saturday
    WeekStart.SUNDAY -> R.string.week_start_sunday
}

private fun durationLabel(context: Context, minutes: Int): String = when {
    minutes % 60 == 0 -> context.getString(R.string.duration_hours, minutes / 60)
    else -> context.getString(R.string.duration_minutes, minutes)
}

private fun reminderLabel(context: Context, minutes: Int): String = when {
    minutes < 0 -> context.getString(R.string.reminder_off)
    minutes == 0 -> context.getString(R.string.reminder_at_time)
    minutes % (24 * 60) == 0 -> context.getString(R.string.reminder_days, minutes / (24 * 60))
    minutes % 60 == 0 -> context.getString(R.string.reminder_hours, minutes / 60)
    else -> context.getString(R.string.reminder_minutes, minutes)
}

/**
 * Releases the persistable read grant we hold on [previousUri], unless it is the one we are [keeping].
 * Only document URIs we actually persisted are affected — system ringtones never took a grant.
 */
private fun releasePersistedGrant(context: Context, previousUri: String?, keeping: String?) {
    if (previousUri == null || previousUri == keeping) return
    val uri = runCatching { previousUri.toUri() }.getOrNull() ?: return
    val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
    if (!held) return
    runCatching {
        context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

// Audio files offerable as a reminder sound; the audio wildcard covers MP3/OGG/WAV/M4A alike.
// (Kept as a line comment: a block comment containing the wildcard would nest and swallow the file.)
private val AUDIO_MIME_TYPES = arrayOf("audio/*")

/** Intent for the system ringtone picker, pre-selecting the currently chosen sound. */
private fun ringtonePickerIntent(context: Context, currentUri: String?): Intent {
    val existing = currentUri?.let { runCatching { it.toUri() }.getOrNull() }
        ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
    return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.settings_notif_ringtone))
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(
            RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION),
        )
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
    }
}

/**
 * Human-readable name of the chosen ringtone; falls back to "default" when unset/unreadable.
 * Resolving a title queries the media provider over IPC, so it runs off the composition thread.
 */
@Composable
private fun ringtoneTitle(uri: String?): String {
    val context = LocalContext.current
    val default = stringResource(R.string.settings_notif_ringtone_default)
    return produceState(initialValue = default, uri, default) {
        value = uri?.let { raw ->
            withContext(Dispatchers.IO) {
                runCatching { RingtoneManager.getRingtone(context, raw.toUri())?.getTitle(context) }.getOrNull()
            }
        } ?: default
    }.value
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
