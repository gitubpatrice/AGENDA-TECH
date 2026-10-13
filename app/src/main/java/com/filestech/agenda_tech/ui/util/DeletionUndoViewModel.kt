package com.filestech.agenda_tech.ui.util

import androidx.lifecycle.ViewModel
import com.filestech.agenda_tech.system.DeletionUndo
import com.filestech.agenda_tech.system.PendingDeletion
import com.filestech.agenda_tech.system.UndoResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** A screen's window on [DeletionUndo], which lives for the whole app — see [DeletionUndoSnackbarHost]. */
@HiltViewModel
class DeletionUndoViewModel @Inject constructor(private val deletionUndo: DeletionUndo) : ViewModel() {
    val pending = deletionUndo.pending
    fun isFresh(pending: PendingDeletion): Boolean = deletionUndo.isFresh(pending)
    fun dismiss(pending: PendingDeletion) = deletionUndo.dismiss(pending)
    suspend fun undo(pending: PendingDeletion): UndoResult = deletionUndo.undo(pending)
}
