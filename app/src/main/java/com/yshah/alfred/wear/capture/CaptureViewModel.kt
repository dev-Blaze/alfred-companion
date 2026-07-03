package com.yshah.alfred.wear.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yshah.alfred.wear.datalayer.DataLayerSender
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CapturePhase {
    data object Idle : CapturePhase()
    data object Listening : CapturePhase()
    data object Sending : CapturePhase()
    data object Queued : CapturePhase()
    data class Error(val message: String) : CapturePhase()
}

data class CaptureUiState(
    val mode: String = "task",
    val phase: CapturePhase = CapturePhase.Idle,
    val transcript: String = "",
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val dataLayerSender: DataLayerSender,
    private val speechCapture: WearSpeechCapture,
) : ViewModel() {

    private val _ui = MutableStateFlow(CaptureUiState())
    val ui: StateFlow<CaptureUiState> = _ui.asStateFlow()

    // One job per send so a rapid follow-up capture cancels the previous send's pending
    // status-reset instead of having it clobber the new send's state.
    private var sendJob: Job? = null

    /** Starts (or restarts) listening in the given mode. Requires RECORD_AUDIO already granted. */
    fun startCapture(mode: String) {
        sendJob?.cancel()
        if (!speechCapture.isAvailable()) {
            _ui.value = CaptureUiState(mode, CapturePhase.Error("No speech recognizer"))
            return
        }
        _ui.value = CaptureUiState(mode = mode, phase = CapturePhase.Listening)
        speechCapture.start { event ->
            when (event) {
                is WearSpeechCapture.Event.Partial ->
                    _ui.update { it.copy(transcript = event.text) }
                is WearSpeechCapture.Event.Final ->
                    if (event.text.isBlank()) {
                        _ui.update { it.copy(phase = CapturePhase.Error("Didn't catch that")) }
                    } else {
                        send(mode, event.text)
                    }
                is WearSpeechCapture.Event.Failed ->
                    _ui.update { it.copy(phase = CapturePhase.Error(event.message)) }
            }
        }
    }

    /** Stops listening without sending — e.g. when the activity leaves the foreground. */
    fun cancelCapture() {
        speechCapture.cancel()
        _ui.update {
            if (it.phase == CapturePhase.Listening) it.copy(phase = CapturePhase.Idle) else it
        }
    }

    private fun send(mode: String, text: String) {
        _ui.update { it.copy(phase = CapturePhase.Sending, transcript = text) }
        sendJob = viewModelScope.launch {
            try {
                dataLayerSender.send(type = mode, text = text)
                // putDataItem() succeeding only confirms local buffering, not phone receipt —
                // "Queued" is the honest status until an ack path exists (deferred to v2).
                _ui.update { it.copy(phase = CapturePhase.Queued) }
                delay(STATUS_RESET_MS)
                _ui.update { it.copy(phase = CapturePhase.Idle, transcript = "") }
            } catch (e: Exception) {
                _ui.update { it.copy(phase = CapturePhase.Error(e.message ?: "Send failed")) }
            }
        }
    }

    override fun onCleared() {
        speechCapture.cancel()
        super.onCleared()
    }

    private companion object {
        const val STATUS_RESET_MS = 2_500L
    }
}
