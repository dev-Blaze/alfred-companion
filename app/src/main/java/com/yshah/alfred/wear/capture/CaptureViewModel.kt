package com.yshah.alfred.wear.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yshah.alfred.wear.datalayer.DataLayerSender
import dagger.hilt.android.lifecycle.HiltViewModel
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

    /** Temporary test hook for validating the Data Layer path before real speech capture exists. */
    fun sendTestCapture() {
        _status.value = SendStatus.Sending
        viewModelScope.launch {
            try {
                dataLayerSender.send(type = "task", text = "Test capture from watch")
                // putDataItem() succeeding only confirms local buffering, not phone receipt —
                // "Queued" is the honest status until an ack path exists (deferred to v2).
                _status.value = SendStatus.Queued
            } catch (e: Exception) {
                _status.value = SendStatus.Error(e.message ?: "Send failed")
            }
        }
    }
}
