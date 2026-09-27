package com.yshah.alfred.wear.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class CaptureUiState(
    val mode: String = "task",
    val listening: Boolean = false,
    val message: String = "",
    val needsSettings: Boolean = false,
    val records: List<CaptureRecord> = emptyList(),
) {
    val draft: CaptureRecord? get() = records.firstOrNull { it.status == "draft" }
}

class CaptureViewModel(
    private val speechCapture: SpeechCapture,
    private val store: CaptureStore,
    private val hasMicPermission: () -> Boolean,
    private val transmit: suspend (CaptureRecord) -> Unit,
) : ViewModel() {
    private val _ui = MutableStateFlow(CaptureUiState(records = store.records.value))
    val ui = _ui.asStateFlow()
    private var generation = 0
    private val inFlight = mutableSetOf<String>()

    init {
        viewModelScope.launch { store.records.collect { records -> _ui.update { it.copy(records = records) } } }
    }

    fun selectMode(mode: String) {
        require(mode == "task" || mode == "note")
        interruptCapture()
        if (save { store.change { records -> records.map { if (it.status == "draft") it.copy(type = mode) else it } } }) {
            _ui.update { it.copy(mode = mode) }
        }
    }

    /** Permission is checked here too, including re-record attempts and settings returns. */
    fun startCapture() {
        if (!hasMicPermission()) { onPermissionDenied(); return }
        if (_ui.value.listening) return
        if (!speechCapture.isAvailable()) { showError("No speech recognizer. You can type a draft."); return }
        val record = store.records.value.firstOrNull { it.status == "draft" }
            ?: CaptureRecord(type = _ui.value.mode)
        if (!save { store.change { records -> if (records.any { it.requestId == record.requestId }) records else records + record } }) return
        val token = ++generation
        _ui.update { it.copy(listening = true, message = "", needsSettings = false) }
        try {
            speechCapture.start { event ->
                if (token != generation || !_ui.value.listening) return@start
                when (event) {
                    is SpeechCapture.Event.Partial -> saveText(record.requestId, event.text)
                    is SpeechCapture.Event.Final -> {
                        if (event.text.isNotBlank()) saveText(record.requestId, event.text)
                        interruptCapture()
                    }
                    is SpeechCapture.Event.Failed -> {
                        interruptCapture()
                        showError(event.message + ". Review the retained draft or record again.")
                    }
                }
            }
        } catch (_: SecurityException) {
            interruptCapture()
            onPermissionDenied()
        } catch (_: Exception) {
            interruptCapture()
            showError("Speech could not start. Draft retained.")
        }
    }

    fun stopListening() = speechCapture.stop()

    /** Lifecycle interruption never authorizes a send. Invalidate callbacks before cancellation. */
    fun interruptCapture() {
        generation++
        speechCapture.cancel()
        _ui.update { it.copy(listening = false) }
    }

    fun editText(text: String) {
        if (text.length > 50_000) { showError("Draft must be at most 50,000 characters"); return }
        interruptCapture()
        val draft = store.records.value.firstOrNull { it.status == "draft" }
        if (draft != null) saveText(draft.requestId, text)
        else save { store.change { it + CaptureRecord(type = _ui.value.mode, text = text) } }
    }

    fun cancelDraft() {
        interruptCapture()
        if (save { store.change { it.filterNot { record -> record.status == "draft" } } }) {
            _ui.update { it.copy(message = "Draft cancelled") }
        }
    }

    fun onPermissionDenied() {
        _ui.update { it.copy(message = "Microphone access needed to record. Enable it in settings or type a draft.", needsSettings = true) }
    }

    fun send(id: String) {
        val record = store.records.value.firstOrNull { it.requestId == id } ?: return
        if (id in inFlight || (record.status != "draft" && !record.canRetry)) return
        if (record.text.isBlank()) { showError("Add text before sending"); return }
        interruptCapture()
        var pending: CaptureRecord? = null
        if (!save { store.update(id) { current ->
            if (current.status != "draft" && !current.canRetry) current
            else current.copy(status = "pending", message = "", attempt = current.attempt + 1)
                .also { pending = it }
        } }) return
        val transfer = pending ?: return
        inFlight += id
        viewModelScope.launch {
            try {
                withTimeout(10_000) { transmit(transfer) }
                updateTransfer(id, "local_queued", "Waiting for phone receipt. Safe to close the app.")
            } catch (_: TimeoutCancellationException) {
                updateTransfer(id, "unknown", "Transfer timed out; receipt unknown. Retry uses the same request ID.")
            } catch (e: CancellationException) {
                // Durable pending record recovers as unknown on the next process start.
                throw e
            } catch (_: Exception) {
                updateTransfer(id, "unknown", "Transfer could not be confirmed. Retry uses the same request ID.")
            } finally {
                inFlight -= id
            }
        }
    }

    private fun updateTransfer(id: String, status: String, message: String) = save {
        store.update(id) { if (it.status == "pending") it.copy(status = status, message = message) else it }
    }

    private fun saveText(id: String, text: String) {
        if (text.length <= 50_000) {
            save { store.update(id) { it.copy(text = text) } }
        }
    }

    private fun save(block: () -> Unit): Boolean = try {
        block()
        _ui.update { it.copy(records = store.records.value) }
        true
    } catch (_: Exception) {
        showError("Watch storage could not save this change. Keep this screen open and try again.")
        false
    }

    private fun showError(message: String) { _ui.update { it.copy(message = message) } }

    override fun onCleared() {
        interruptCapture()
        super.onCleared()
    }
}
