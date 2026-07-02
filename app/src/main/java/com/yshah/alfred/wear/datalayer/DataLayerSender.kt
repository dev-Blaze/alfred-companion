package com.yshah.alfred.wear.datalayer

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Sends a capture to the paired phone via DataClient (not MessageClient) — DataClient buffers
 * locally and syncs once the Bluetooth link is available, so a capture made while out of range
 * of the phone still gets delivered once reconnected, satisfying the "must work without the
 * watch having its own internet" requirement. setUrgent() is mandatory: without it, non-urgent
 * DataItems can take up to 30 minutes to sync by default.
 *
 * Each capture uses a unique path (/alfred/capture/{sessionId}) rather than one reused path —
 * reusing a path risks a second capture's write clobbering an unsynced first one.
 */
class DataLayerSender(private val context: Context) {
    suspend fun send(type: String, text: String, sessionId: String = UUID.randomUUID().toString()) {
        val request = PutDataMapRequest.create("/alfred/capture/$sessionId").apply {
            dataMap.putString("type", type)
            dataMap.putString("text", text)
            dataMap.putLong("timestamp", System.currentTimeMillis())
            dataMap.putString("sessionId", sessionId)
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request).await()
    }
}
