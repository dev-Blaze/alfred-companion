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
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SendStatus {
    data object Idle : SendStatus()
    data object Sending : SendStatus()
    data object Queued : SendStatus()
    data class Error(val message: String) : SendStatus()
}

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val dataLayerSender: DataLayerSender,
) : ViewModel() {

    private val _status = MutableStateFlow<SendStatus>(SendStatus.Idle)
    val status: StateFlow<SendStatus> = _status.asStateFlow()

    // One job per send so a rapid follow-up capture cancels the previous send's pending
    // status-reset instead of having it clobber the new send's "Sending…"/"Queued" state.
    private var sendJob: Job? = null

    fun send(type: String, text: String) {
        sendJob?.cancel()
        _status.value = SendStatus.Sending
        sendJob = viewModelScope.launch {
            try {
                dataLayerSender.send(type = type, text = text)
                // putDataItem() succeeding only confirms local buffering, not phone receipt —
                // "Queued" is the honest status until an ack path exists (deferred to v2).
                _status.value = SendStatus.Queued
                delay(STATUS_RESET_MS)
                _status.value = SendStatus.Idle
            } catch (e: Exception) {
                _status.value = SendStatus.Error(e.message ?: "Send failed")
            }
        }
    }

    private companion object {
        const val STATUS_RESET_MS = 2_500L
    }
}
