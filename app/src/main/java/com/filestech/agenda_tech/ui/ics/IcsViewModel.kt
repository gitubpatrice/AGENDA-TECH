package com.filestech.agenda_tech.ui.ics

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
            onFailure = { Timber.w(it, "ICS export failed"); IcsResult.Failed },
        )
    }

    fun import(uri: Uri) = viewModelScope.launch {
        val outcome = withContext(io) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("no input stream for $uri")
                importEvents(text, ZoneId.systemDefault().id)
            }
        }
        _result.value = outcome.fold(
            onSuccess = { IcsResult.Imported(it) },
            onFailure = { Timber.w(it, "ICS import failed"); IcsResult.Failed },
        )
    }

    fun consumeResult() {
        _result.value = null
    }
}
