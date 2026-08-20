package com.yshah.alfred.wear.capture

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yshah.alfred.wear.datalayer.MODE_TASK
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed class CapturePhase {
    data object Idle : CapturePhase()
    data object Listening : CapturePhase()
    data object Sending : CapturePhase()
    data object Queued : CapturePhase()
    /** [needsSettings] when the only way to recover is the system app-info screen. */
    data class Error(val message: String, val needsSettings: Boolean = false) : CapturePhase()
}

data class CaptureUiState(
    val mode: String = MODE_TASK,
    val phase: CapturePhase = CapturePhase.Idle,
    val transcript: String = "",
)

class CaptureViewModel(
    private val speechCapture: SpeechCapture,
    private val transmit: suspend (type: String, text: String) -> Unit,
) : ViewModel() {

    private val _ui = MutableStateFlow(CaptureUiState())
    val ui: StateFlow<CaptureUiState> = _ui.asStateFlow()

    // Bumped on every new capture. A send's UI updates are dropped once it's stale, so a slow
    // send can't clobber a newer capture's state. The send itself is deliberately never
    // cancelled — a capture the user was told we took must not be lost because they reopened
    // the app, and each capture writes its own DataItem path so concurrent sends can't collide.
    private var generation = 0

    /** Starts (or restarts) listening in the given mode. Requires RECORD_AUDIO already granted. */
    fun startCapture(mode: String) {
        // The permission-grant round trip drives onStart and the result callback, and tapping
        // the selected chip mid-listen re-enters here. Restarting would destroy a recognizer
        // bound milliseconds ago and rebind, which reliably yields ERROR_CLIENT/BUSY.
        val current = _ui.value
        if (current.phase == CapturePhase.Listening && current.mode == mode) return

        if (!speechCapture.isAvailable()) {
            _ui.value = CaptureUiState(mode, CapturePhase.Error("No speech recognizer"))
            return
        }
        generation++
        _ui.value = CaptureUiState(mode = mode, phase = CapturePhase.Listening)
        speechCapture.start { event ->
            when (event) {
                is SpeechCapture.Event.Partial ->
                    _ui.update { it.copy(transcript = event.text) }
                is SpeechCapture.Event.Final ->
                    if (event.text.isBlank()) {
                        _ui.update {
                            it.copy(phase = CapturePhase.Error("Didn't catch that"), transcript = "")
                        }
                    } else {
                        send(mode, event.text)
                    }
                is SpeechCapture.Event.Failed ->
                    _ui.update { it.copy(phase = CapturePhase.Error(event.message), transcript = "") }
            }
        }
    }

    /**
     * Stops listening — e.g. when the activity leaves the foreground, which on a watch usually
     * means the screen timed out or the user turned their wrist mid-sentence. Whatever was
     * transcribed so far is sent rather than discarded: a truncated capture that lands beats a
     * complete one that vanishes silently.
     */
    fun cancelCapture() {
        speechCapture.cancel()
        val state = _ui.value
        if (state.phase == CapturePhase.Listening && state.transcript.isNotBlank()) {
            Log.d(TAG, "backgrounded mid-capture, salvaging ${state.transcript.length} chars")
            send(state.mode, state.transcript)
        } else {
            _ui.update {
                if (it.phase == CapturePhase.Listening) it.copy(phase = CapturePhase.Idle) else it
            }
        }
    }

    /** Denial leaves the app inert, so say so — the screen would otherwise just read "Alfred". */
    fun onPermissionDenied(permanent: Boolean) {
        _ui.value = CaptureUiState(
            mode = _ui.value.mode,
            phase = CapturePhase.Error("Mic access needed", needsSettings = permanent),
        )
    }

    private fun send(mode: String, text: String) {
        val sendGeneration = generation
        _ui.update { it.copy(phase = CapturePhase.Sending, transcript = text) }
        viewModelScope.launch {
            try {
                withTimeout(SEND_TIMEOUT_MS) { transmit(mode, text) }
                // putDataItem() succeeding only confirms local buffering, not phone receipt —
                // "Queued" is the honest status until an ack path exists (deferred to v2).
                updateIfCurrent(sendGeneration) { it.copy(phase = CapturePhase.Queued) }
                delay(STATUS_RESET_MS)
                updateIfCurrent(sendGeneration) {
                    it.copy(phase = CapturePhase.Idle, transcript = "")
                }
            } catch (e: TimeoutCancellationException) {
                // Must precede the CancellationException rethrow below — this one is a real
                // failure to report, not the scope tearing down.
                Log.w(TAG, "send timed out", e)
                updateIfCurrent(sendGeneration) {
                    it.copy(phase = CapturePhase.Error("Send timed out"), transcript = "")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
                updateIfCurrent(sendGeneration) {
                    it.copy(phase = CapturePhase.Error(e.message ?: "Send failed"), transcript = "")
                }
            }
        }
    }

    private fun updateIfCurrent(sendGeneration: Int, block: (CaptureUiState) -> CaptureUiState) {
        if (sendGeneration == generation) _ui.update(block)
    }

    override fun onCleared() {
        speechCapture.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "AlfredCapture"
        const val STATUS_RESET_MS = 2_500L
        const val SEND_TIMEOUT_MS = 10_000L
    }
}
