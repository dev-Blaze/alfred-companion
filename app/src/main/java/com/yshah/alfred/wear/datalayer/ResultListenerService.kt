package com.yshah.alfred.wear.datalayer

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.yshah.alfred.wear.capture.CaptureStore
import kotlinx.coroutines.tasks.await

class ResultListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) receiveResult(this, it.dataItem) }
    }
}

private fun receiveResult(context: Context, item: DataItem) {
    val path = item.uri.path.orEmpty()
    if (!path.startsWith("/alfred/result/")) return
    try {
        val map = DataMapItem.fromDataItem(item).dataMap
        CaptureStore.get(context).receive(path, map.getString("requestId").orEmpty(),
            map.getString("status").orEmpty(), map.getString("message").orEmpty(),
            if (map.containsKey("resultRevision")) map.getLong("resultRevision") else null)
        // Retain the phone's DataItem so foreground reconciliation can recover missed callbacks.
    } catch (e: Exception) {
        Log.e("AlfredResults", "Could not persist phone result", e)
    }
}

suspend fun refreshResults(context: Context) {
    val items = Wearable.getDataClient(context).dataItems.await()
    try { items.forEach { receiveResult(context, it) } } finally { items.release() }
}
