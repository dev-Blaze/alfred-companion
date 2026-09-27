package com.yshah.alfred.wear.datalayer

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.yshah.alfred.wear.capture.CaptureRecord
import kotlinx.coroutines.tasks.await
import java.time.ZoneId

const val MODE_TASK = "task"
const val MODE_NOTE = "note"

fun capturePath(requestId: String): String {
    require(requestId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    return "/alfred/capture/$requestId"
}

suspend fun sendCapture(context: Context, capture: CaptureRecord) {
    require(capture.type == MODE_TASK || capture.type == MODE_NOTE)
    require(capture.text.isNotBlank() && capture.text.length <= 50_000)
    require(capture.capturedAt > 0)
    ZoneId.of(capture.timeZone)
    val request = PutDataMapRequest.create(capturePath(capture.requestId)).apply {
        dataMap.putString("type", capture.type)
        dataMap.putString("text", capture.text)
        dataMap.putString("requestId", capture.requestId)
        dataMap.putString("sessionId", capture.requestId)
        dataMap.putLong("capturedAt", capture.capturedAt)
        dataMap.putLong("timestamp", capture.capturedAt)
        dataMap.putString("timeZone", capture.timeZone)
        dataMap.putInt("schemaVersion", 1)
        dataMap.putString("source", "watch")
        // Trigger a new change event on explicit retry while preserving identity and payload.
        dataMap.putLong("attempt", capture.attempt)
    }.asPutDataRequest().setUrgent()
    Wearable.getDataClient(context).putDataItem(request).await()
}
