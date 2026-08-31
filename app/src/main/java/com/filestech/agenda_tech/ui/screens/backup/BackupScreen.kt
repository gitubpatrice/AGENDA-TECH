package com.filestech.agenda_tech.ui.screens.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.core.crypto.BackupEnvelope
import com.filestech.agenda_tech.domain.usecase.ExportBackupUseCase
import com.filestech.agenda_tech.domain.backup.AutoBackupOutcome
import com.filestech.agenda_tech.ui.theme.BrandDanger
import com.filestech.agenda_tech.ui.util.rememberAppLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Encrypted backup: export the whole agenda to a password-protected `.atbak` file, or restore one.
 *
 * The password is asked for **before** the file picker on export (so a cancelled password doesn't
 * leave an empty file behind) and **after** it on restore (so a wrong pick is rejected on its magic
 * bytes without the user typing anything).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Export asks for the password first, so it has to be parked until the picker returns a target.
    // Both halves park in the ViewModel now: the picker is another activity, and this one can be
    // recreated behind it. See BackupViewModel.pendingExportPassword.
    var showExportPasswordDialog by rememberSaveable { mutableStateOf(false) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportBackupUseCase.MIME_TYPE),
    ) { uri -> viewModel.onExportTargetPicked(uri) }

    // The system folder picker. Its result is handed straight to the ViewModel, which persists the
    // grant before storing the URI — see onAutoBackupFolderPicked.
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onAutoBackupFolderPicked(uri) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Hands the file straight to the ViewModel, which rejects a wrong pick on its magic bytes
        // before the password dialog is ever shown.
        if (uri != null) viewModel.onRestoreFilePicked(uri)
    }

    val appLocale = rememberAppLocale()
    val messages = backupMessages(state.message)
    LaunchedEffect(state.message) {
        messages?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.editor_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NoticeCard(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.backup_intro_title),
                    body = stringResource(R.string.backup_intro_body),
                    tint = MaterialTheme.colorScheme.primary,
                )
                NoticeCard(
                    icon = Icons.Outlined.Warning,
                    title = stringResource(R.string.backup_warn_title),
                    body = stringResource(R.string.backup_warn_body),
                    tint = BrandDanger,
                )

                ActionRow(
                    title = stringResource(R.string.backup_export),
                    subtitle = stringResource(R.string.backup_export_sub),
                    enabled = state.busy == null,
                    onClick = { showExportPasswordDialog = true },
                )
                ActionRow(
                    title = stringResource(R.string.backup_restore),
                    subtitle = stringResource(R.string.backup_restore_sub),
                    enabled = state.busy == null,
                    onClick = { openFile.launch(arrayOf("*/*")) },
                )

                HorizontalDivider()

                AutoBackupSection(
                    state = state,
                    locale = appLocale,
                    onToggle = viewModel::onAutoBackupToggle,
                    onChangeFolder = { pickFolder.launch(null) },
                    onRunNow = viewModel::runAutoBackupNow,
                )
            }

            state.busy?.let { BusyOverlay(it) }
        }
    }

    if (showExportPasswordDialog) {
        PasswordDialog(
            title = stringResource(R.string.backup_export_dialog_title),
            requireConfirmation = true,
            onDismiss = { showExportPasswordDialog = false },
            onConfirm = { password ->
                showExportPasswordDialog = false
                viewModel.onExportPasswordEntered(password)
                createFile.launch(viewModel.suggestedFileName())
            },
        )
    }

    // Launching an activity is a side effect, never done during composition. Keyed on the step so a
    // recomposition for any other reason cannot open a second picker on top of the first.
    LaunchedEffect(state.autoBackupStep) {
        if (state.autoBackupStep == AutoBackupStep.PICK_FOLDER) pickFolder.launch(null)
    }

    if (state.autoBackupStep == AutoBackupStep.ASK_PASSWORD) {
        PasswordDialog(
            title = stringResource(R.string.backup_auto_password_title),
            // Typed twice: nobody will retype this one for a year, and it is what stands between the
            // user and their own file the day they need it.
            requireConfirmation = true,
            destructiveWarning = stringResource(R.string.backup_auto_password_note),
            onDismiss = viewModel::dismissAutoBackupStep,
            onConfirm = viewModel::onAutoBackupPasswordEntered,
        )
    }

    if (state.awaitingRestorePassword) {
        PasswordDialog(
            title = stringResource(R.string.backup_restore_dialog_title),
            requireConfirmation = false,
            // Replacing the agenda is irreversible, so the confirmation is part of this dialog's
            // own action rather than a second dialog the user would click through on autopilot.
            destructiveWarning = stringResource(R.string.backup_restore_confirm_body),
            confirmLabel = stringResource(R.string.backup_restore_confirm_action),
            onDismiss = viewModel::cancelRestore,
            onConfirm = viewModel::restore,
        )
    }
}

@Composable
private fun backupMessages(message: BackupMessage?): String? = when (message) {
    is BackupMessage.Exported -> stringResource(R.string.backup_exported, message.events)
    is BackupMessage.Restored ->
        stringResource(R.string.backup_restored, message.calendars, message.events, message.reminders)
    BackupMessage.BadPasswordOrFile -> stringResource(R.string.backup_bad_password)
    BackupMessage.NotABackup -> stringResource(R.string.backup_not_a_backup)
    BackupMessage.BackupTooNew -> stringResource(R.string.backup_too_new)
    BackupMessage.BackupDamaged -> stringResource(R.string.backup_damaged)
    BackupMessage.PasswordTooShort -> stringResource(R.string.backup_password_too_short)
    BackupMessage.Failed -> stringResource(R.string.backup_failed)
    null -> null
}


/**
 * The automatic-backup block: the switch, which folder was chosen, and what the last run actually
 * did.
 *
 * The status line is the point of the whole section. An automatic backup nobody watches is only
 * worth having if its failures are visible, so this states the outcome plainly — including "nothing
 * was written" — rather than showing a reassuring date that says only when it last *tried*.
 */
@Composable
private fun AutoBackupSection(
    state: BackupUiState,
    locale: Locale,
    onToggle: (Boolean) -> Unit,
    onChangeFolder: () -> Unit,
    onRunNow: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backup_auto_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.backup_auto_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.autoBackupEnabled,
                onCheckedChange = onToggle,
                enabled = state.busy == null,
            )
        }

        if (state.autoBackupEnabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    state.autoBackupFolderLost -> stringResource(R.string.backup_auto_folder_lost)
                    state.autoBackupFolderName != null ->
                        stringResource(R.string.backup_auto_folder, state.autoBackupFolderName)
                    else -> stringResource(R.string.backup_auto_folder_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.autoBackupFolderLost) BrandDanger else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            Text(
                text = autoBackupStatus(state, locale),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.autoBackupLastOutcome.isFailure) {
                    BrandDanger
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRunNow, enabled = state.busy == null) {
                    Text(stringResource(R.string.backup_auto_run_now))
                }
                TextButton(onClick = onChangeFolder, enabled = state.busy == null) {
                    Text(stringResource(R.string.backup_auto_change_folder))
                }
            }
        }
    }
}

/** What the last automatic run did, in one line. */
@Composable
private fun autoBackupStatus(state: BackupUiState, locale: Locale): String = when (state.autoBackupLastOutcome) {
    AutoBackupOutcome.NEVER_RUN -> stringResource(R.string.backup_auto_never)
    AutoBackupOutcome.OK -> stringResource(
        R.string.backup_auto_last_ok,
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(state.autoBackupLastRunAtUtcMillis).atZone(ZoneId.systemDefault())),
    )
    AutoBackupOutcome.NO_FOLDER -> stringResource(R.string.backup_auto_err_no_folder)
    AutoBackupOutcome.NO_PASSWORD -> stringResource(R.string.backup_auto_err_no_password)
    AutoBackupOutcome.FOLDER_UNAVAILABLE -> stringResource(R.string.backup_auto_err_folder)
    AutoBackupOutcome.EXPORT_FAILED -> stringResource(R.string.backup_auto_err_export)
    AutoBackupOutcome.WRITE_FAILED -> stringResource(R.string.backup_auto_err_write)
}

@Composable
private fun NoticeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    tint: androidx.compose.ui.graphics.Color,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Blocks input while the KDF runs — several seconds during which a second tap must not start a
 * second run.
 *
 * That sentence was here before anything enforced it: this was a plain `Box`, which draws over the
 * screen and lets every tap through. It is worth being exact about what that did and did not risk,
 * because the first correction here overstated it — the two rows underneath are already
 * `enabled = state.busy == null`, and the button that actually replaces the agenda lives in an
 * `AlertDialog`, i.e. in its own window above this one. **What fixed the double tap is the guard in
 * `BackupViewModel.restore`, not this overlay.**
 *
 * What the overlay is for is the rest: consumed pointer events so nothing underneath can be reached
 * as states change, a scrim that says the screen is busy, and a swallowed back gesture. Back is the
 * one that matters — the work runs in `viewModelScope` and would carry on after the user navigated
 * away believing they had stopped it.
 */
@Composable
private fun BusyOverlay(op: BackupOp) {
    BackHandler(enabled = true) { /* deliberately swallowed: see KDoc */ }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    stringResource(
                        if (op == BackupOp.EXPORT) R.string.backup_working else R.string.backup_working_restore,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.backup_working_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Collects the password and hands the caller a [CharArray], which every consumer wipes.
 *
 * The typed text itself is an immutable [String] in Compose state until then — one unscrubbable
 * instance per keystroke. That residual is accepted and documented in `SECURITY.md` (same trade-off
 * as the PIN, LOCK-8): `OutlinedTextField` is String-backed, so scrubbing the Compose path would
 * mean rewriting the field, and reaching those instances needs a memory dump of a non-debuggable
 * release build. The [CharArray] boundary is where scrubbing starts, not where the secret begins.
 */
@Composable
private fun PasswordDialog(
    title: String,
    requireConfirmation: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
    destructiveWarning: String? = null,
    confirmLabel: String? = null,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    val tooShort = password.length < BackupEnvelope.MIN_PASSWORD_LENGTH
    val mismatch = requireConfirmation && confirmation.isNotEmpty() && password != confirmation
    // On restore the length rule doesn't apply: the file was sealed with whatever password it was
    // sealed with, and pre-judging it here would lock the user out of their own backup.
    val canConfirm = if (requireConfirmation) !tooShort && password == confirmation else password.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (destructiveWarning != null) {
                    Text(destructiveWarning, style = MaterialTheme.typography.bodySmall, color = BrandDanger)
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_password)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(
                                    if (visible) R.string.backup_password_hide else R.string.backup_password_show,
                                ),
                            )
                        }
                    },
                    supportingText = if (requireConfirmation) {
                        { Text(stringResource(R.string.backup_password_hint)) }
                    } else {
                        null
                    },
                    isError = requireConfirmation && password.isNotEmpty() && tooShort,
                )
                if (requireConfirmation) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text(stringResource(R.string.backup_password_confirm)) },
                        singleLine = true,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        // Same toggle as the field above, driving the same state: this is the field
                        // the user is looking at when a typo blocks them, so the way out has to be here.
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(
                                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = stringResource(
                                        if (visible) R.string.backup_password_hide else R.string.backup_password_show,
                                    ),
                                )
                            }
                        },
                        isError = mismatch,
                        supportingText = if (mismatch) {
                            { Text(stringResource(R.string.backup_password_mismatch), color = BrandDanger) }
                        } else {
                            null
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password.toCharArray()) },
                enabled = canConfirm,
                colors = if (destructiveWarning != null) {
                    ButtonDefaults.textButtonColors(contentColor = BrandDanger)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(confirmLabel ?: stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** Enough to read as "not now" without hiding what is behind it. */
private const val SCRIM_ALPHA = 0.4f
