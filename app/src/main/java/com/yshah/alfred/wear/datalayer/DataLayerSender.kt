package com.yshah.alfred.wear.datalayer

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** The two capture kinds. The phone compares against these exact strings — see [sendCapture]. */
const val MODE_TASK = "task"
const val MODE_NOTE = "note"

/**
 * Sends a capture to the paired phone via DataClient (not MessageClient) — DataClient buffers
 * locally and syncs once the Bluetooth link is available, so a capture made while out of range
 * of the phone still gets delivered once reconnected, satisfying the "must work without the
 * watch having its own internet" requirement. setUrgent() is mandatory: without it, non-urgent
 * DataItems can take up to 30 minutes to sync by default.
 *
 * Each capture uses a unique path ([CAPTURE_PATH_PREFIX]/{sessionId}) rather than one reused path —
 * reusing a path risks a second capture's write clobbering an unsynced first one.
 *
 * Contract with the phone app (alfred/, AlfredWearableListenerService): it filters on the
 * "$CAPTURE_PATH_PREFIX" prefix, reads "type"/"text"/"sessionId", and **must delete the DataItem
 * once consumed**. That delete is load-bearing on this side too — a DataItem lives until someone
 * removes it, so without it every capture ever made replays into the webhook whenever the phone
 * app is reinstalled and re-syncs from the watch.
 */
suspend fun sendCapture(context: Context, type: String, text: String) {
    val sessionId = UUID.randomUUID().toString()
    val request = PutDataMapRequest.create("$CAPTURE_PATH_PREFIX/$sessionId").apply {
        dataMap.putString("type", type)
        dataMap.putString("text", text)
        dataMap.putLong("timestamp", System.currentTimeMillis())
        dataMap.putString("sessionId", sessionId)
    }.asPutDataRequest().setUrgent()

    Wearable.getDataClient(context).putDataItem(request).await()
    Log.d(TAG, "queued $type capture $sessionId (${text.length} chars)")
}

private const val CAPTURE_PATH_PREFIX = "/alfred/capture"
private const val TAG = "AlfredDataLayer"
