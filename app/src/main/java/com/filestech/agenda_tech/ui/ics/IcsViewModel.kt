package com.filestech.agenda_tech.ui.ics

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.agenda_tech.core.io.BoundedRead
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.usecase.ExportEventsUseCase
import com.filestech.agenda_tech.domain.usecase.ImportEventsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.ZoneId
import javax.inject.Inject

/** One-shot result of an import/export, consumed by the screen to show a message. */
sealed interface IcsResult {
    data class Exported(val count: Int) : IcsResult
    data class Imported(val count: Int) : IcsResult
    data object Failed : IcsResult
}

/**
 * Drives `.ics` import/export against a user-picked document [Uri]. All file I/O runs off the main
 * thread; failures are logged (never crash) and surfaced as [IcsResult.Failed].
 */
@HiltViewModel
class IcsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exportEvents: ExportEventsUseCase,
    private val importEvents: ImportEventsUseCase,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _result = MutableStateFlow<IcsResult?>(null)
    val result: StateFlow<IcsResult?> = _result.asStateFlow()

    fun export(uri: Uri) = viewModelScope.launch {
        val outcome = withContext(io) {
            runCatching {
                val exported = exportEvents(System.currentTimeMillis())
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(exported.ics.toByteArray(Charsets.UTF_8))
                } ?: error("no output stream for $uri")
                exported.eventCount
            }
        }
        _result.value = outcome.fold(
            onSuccess = { IcsResult.Exported(it) },
            // Only the failure TYPE, never the throwable: the same rule NoOpReleaseTree sets and that
            // the backup path already follows. What is being written here is the user's whole agenda,
            // and a serialization or SQLite error quotes the offending input in its message.
            onFailure = { Timber.w("ICS export failed (%s)", it.javaClass.simpleName); IcsResult.Failed },
        )
    }

    /**
     * Audit F4 — the ceiling now bounds the read itself ([BoundedRead]), instead of trusting the size
     * the document provider declares. Asking first failed three ways: `openFileDescriptor` returning
     * null skipped the check and read whatever came, a provider reporting a small size then serving
     * gigabytes passed it, and a provider that honestly does not know reports `-1`, which the check
     * refused — turning a legitimate file into "import failed". One correction covers all three,
     * because none of them is about the size: they are about who is asked.
     */
    fun import(uri: Uri) = viewModelScope.launch {
        val outcome = withContext(io) {
            runCatching {
                val stream = context.contentResolver.openInputStream(uri) ?: error("no input stream")
                // Two distinct refusals, kept distinct: an unreadable pick and a file over the ceiling
                // are different things to diagnose, and folding them into one elvis would have made
                // "too large" report itself as "no input stream".
                val bytes = stream.use { BoundedRead.readAtMost(it, MAX_ICS_BYTES) }
                    ?: error("ics file exceeds $MAX_ICS_BYTES bytes")
                importEvents(bytes.toString(Charsets.UTF_8), ZoneId.systemDefault().id)
            }
        }
        _result.value = outcome.fold(
            onSuccess = { IcsResult.Imported(it) },
            onFailure = { Timber.w("ICS import failed (%s)", it.javaClass.simpleName); IcsResult.Failed },
        )
    }

    fun consumeResult() {
        _result.value = null
    }

    private companion object {
        // A personal agenda export is a few KB; 5 MB is a generous ceiling that blocks a DoS file.
        const val MAX_ICS_BYTES = 5L * 1024 * 1024
    }
}
