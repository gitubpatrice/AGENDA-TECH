package com.filestech.agenda_tech.ui.screens.backup

import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.core.crypto.BackupEnvelope
import com.filestech.agenda_tech.core.crypto.wipe
import com.filestech.agenda_tech.domain.usecase.ExportBackupUseCase
import com.filestech.agenda_tech.ui.theme.BrandDanger

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
    // Restore is the mirror image and is driven by the ViewModel, which holds the vetted file.
    var pendingExportPassword by remember { mutableStateOf<CharArray?>(null) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportBackupUseCase.MIME_TYPE),
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        when {
            uri == null -> password?.wipe() // picker cancelled: nothing to encrypt, scrub what we hold
            password != null -> viewModel.export(uri, password)
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Hands the file straight to the ViewModel, which rejects a wrong pick on its magic bytes
        // before the password dialog is ever shown.
        if (uri != null) viewModel.onRestoreFilePicked(uri)
    }

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
                pendingExportPassword = password
                createFile.launch(viewModel.suggestedFileName())
            },
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
    BackupMessage.PasswordTooShort -> stringResource(R.string.backup_password_too_short)
    BackupMessage.Failed -> stringResource(R.string.backup_failed)
    null -> null
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

/** Blocks input while the KDF runs — several seconds during which a second tap must not start a second run. */
@Composable
private fun BusyOverlay(op: BackupOp) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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
