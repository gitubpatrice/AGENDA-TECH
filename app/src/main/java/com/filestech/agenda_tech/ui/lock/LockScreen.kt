package com.filestech.agenda_tech.ui.lock

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.security.StrongBiometrics

/** Minimum PIN length, shared with the settings PIN dialog. */
const val MIN_PIN_LENGTH = 4

@Composable
fun LockScreen(
    onRequestBiometric: () -> Unit,
    viewModel: LockViewModel = hiltViewModel(),
) {
    val biometricPreferred by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val wrongPin by viewModel.wrongPin.collectAsStateWithLifecycle()
    val throttleSeconds by viewModel.throttleSeconds.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }

    // Audit F3 — the stored preference is not enough: it may have been switched on by an earlier
    // build that still accepted Class 2, and the prompt now refuses that tier. Offering the button
    // anyway would auto-fire and tap into a prompt that never appears, with no way to tell why. The
    // preference is left untouched, so the unlock comes back by itself on a Class 3 device.
    // Deliberately not remember{}-ed: this screen stays composed across onStop/onResume whenever the
    // app was already locked when it went to the background, so a cached answer would outlive an
    // enrolment change made in the meantime — the button would stay hidden, or stay visible and dead.
    // canAuthenticate() is a cheap local query, so re-asking on recomposition costs nothing.
    val context = LocalContext.current
    val biometricUsable = biometricPreferred && StrongBiometrics.isAvailable(context)

    androidx.compose.runtime.LaunchedEffect(biometricUsable) {
        if (biometricUsable) onRequestBiometric()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.lock_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { input ->
                    if (input.all { it.isDigit() } && input.length <= MAX_PIN_LENGTH) {
                        pin = input
                        if (wrongPin) viewModel.clearError()
                    }
                },
                label = { Text(stringResource(R.string.lock_pin)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = wrongPin,
                supportingText = if (wrongPin) {
                    { Text(stringResource(R.string.lock_wrong_pin)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.submitPin(pin) },
                enabled = pin.length >= MIN_PIN_LENGTH && throttleSeconds == 0,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.lock_unlock))
            }
            if (throttleSeconds > 0) {
                Text(
                    text = stringResource(R.string.lock_throttled, throttleSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (biometricUsable) {
                TextButton(
                    onClick = onRequestBiometric,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Text(
                        text = stringResource(R.string.lock_use_biometric),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

private const val MAX_PIN_LENGTH = 12
