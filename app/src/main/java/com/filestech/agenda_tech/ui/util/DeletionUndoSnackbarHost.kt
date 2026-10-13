package com.filestech.agenda_tech.ui.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.system.DeletionUndo
import com.filestech.agenda_tech.system.UndoResult
import com.filestech.agenda_tech.ui.theme.BrandDanger
import kotlinx.coroutines.launch

/**
 * "Event deleted · Undo", on every screen the editor can return to after a deletion: the four calendar
 * views (through `CalendarScaffold`) and the search.
 *
 * Leaving the screen while it shows does not dismiss the offer — the next screen shows it again, as long
 * as it is recent ([DeletionUndo.isFresh]). Only running out, a swipe, or "Undo" ends it.
 *
 * [modifier] places the bar: the search, whose keyboard opens again on return, keeps it above the
 * keyboard — under it, the offer ran out unseen.
 */
@Composable
fun DeletionUndoSnackbarHost(
    modifier: Modifier = Modifier,
    viewModel: DeletionUndoViewModel = hiltViewModel(),
) {
    val hostState = remember { SnackbarHostState() }
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    // Read at the moment of showing, in the app's language — see MonthScreen for why not the context.
    val resources = LocalResources.current
    // Not the effect's own scope: claiming the offer clears it, which restarts the effect — and would
    // cancel the wait for the restore's outcome, so a failure would go unsaid.
    val scope = rememberCoroutineScope()

    LaunchedEffect(pending) {
        val offer = pending ?: return@LaunchedEffect
        if (!viewModel.isFresh(offer)) {
            viewModel.dismiss(offer)
            return@LaunchedEffect
        }
        val result = hostState.showSnackbar(
            message = resources.getString(R.string.event_deleted),
            actionLabel = resources.getString(R.string.action_undo),
            duration = SnackbarDuration.Long,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> scope.launch {
                if (viewModel.undo(offer) == UndoResult.FAILED) {
                    hostState.showSnackbar(resources.getString(R.string.event_restore_failed))
                }
            }
            SnackbarResult.Dismissed -> viewModel.dismiss(offer)
        }
    }
    // Red with white text, and "Undo" as a white rounded button with black text — asked on the S9, then
    // made compact: with a button's default padding, the white filled the bar to its edge.
    SnackbarHost(hostState, modifier) { data ->
        Snackbar(
            modifier = Modifier.padding(12.dp),
            action = data.visuals.actionLabel?.let { label ->
                {
                    Button(
                        onClick = data::performAction,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                        // The visible button only: Material keeps the 48 dp touch target around it.
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .heightIn(min = 32.dp),
                    ) {
                        Text(label)
                    }
                }
            },
            containerColor = BrandDanger,
            contentColor = Color.White,
        ) {
            Text(data.visuals.message)
        }
    }
}
