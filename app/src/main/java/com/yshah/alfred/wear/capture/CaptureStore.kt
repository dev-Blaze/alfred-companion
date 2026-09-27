package com.yshah.alfred.wear.capture

import android.content.Context
import android.annotation.SuppressLint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.UUID

data class CaptureRecord(
    val requestId: String = UUID.randomUUID().toString(),
    val type: String = "task",
    val text: String = "",
    val capturedAt: Long = System.currentTimeMillis(),
    val timeZone: String = ZoneId.systemDefault().id,
    val status: String = "draft",
    val message: String = "",
    val attempt: Long = 0,
    val resultRevision: Long = 0,
) {
    val canRetry: Boolean get() = status in setOf("pending", "local_queued", "unknown")
    val statusLabel: String get() = when (status) {
        "draft" -> "Draft — review before sending"
        "pending" -> "Saving to Data Layer…"
        "local_queued" -> "Locally queued — awaiting phone"
        "accepted" -> "Phone received — processing"
        "success" -> "Completed"
        else -> "Needs attention"
    }
}

/** One process-wide store shared by the activity and background Data Layer listener. */
class CaptureStore(
    initial: List<CaptureRecord> = emptyList(),
    private val persist: (List<CaptureRecord>) -> Unit = {},
) {
    private val state = MutableStateFlow(initial)
    val records = state.asStateFlow()

    @Synchronized
    fun change(update: (List<CaptureRecord>) -> List<CaptureRecord>) {
        val next = update(state.value)
        // Publish only after durable commit. A failed write must never look like acceptance.
        persist(next)
        state.value = next
    }

    fun update(id: String, update: (CaptureRecord) -> CaptureRecord) = change { records ->
        records.map { if (it.requestId == id) update(it) else it }
    }

    fun receive(path: String, id: String, status: String, message: String, revision: Long? = null): Boolean {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}")) || path != "/alfred/result/$id" ||
            status !in setOf("accepted", "success", "http_error", "uncertain") || message.length > 4000 ||
            (revision != null && revision <= 0)
        ) return false
        var matched = false
        update(id) { record ->
            if (record.status == "draft") return@update record
            matched = true
            if (revision != null) {
                return@update if (revision <= record.resultRevision || record.status == "success") record
                else record.copy(status = status, message = message, resultRevision = revision)
            }
            // Unordered legacy results cannot overwrite any ordered result or regress a terminal result.
            if (record.resultRevision > 0) return@update record
            if (record.status == "success" ||
                (status == "accepted" && record.status in setOf("http_error", "uncertain"))
            ) record else record.copy(status = status, message = message)
        }
        return matched
    }

    companion object {
        @Volatile private var instance: CaptureStore? = null

        @SuppressLint("UseKtx") // Need commit's Boolean; KTX edit discards the durability result.
        fun get(context: Context): CaptureStore = instance ?: synchronized(this) {
            instance ?: run {
                val prefs = context.applicationContext.getSharedPreferences("captures", Context.MODE_PRIVATE)
                val array = JSONArray(prefs.getString("records", "[]"))
                val records = (0 until array.length()).map { index ->
                    val value = array.getJSONObject(index)
                    CaptureRecord(
                        requestId = value.getString("requestId"), type = value.getString("type"),
                        text = value.getString("text"), capturedAt = value.getLong("capturedAt"),
                        timeZone = value.getString("timeZone"), status = value.getString("status"),
                        message = value.getString("message"), attempt = value.getLong("attempt"),
                        resultRevision = value.optLong("resultRevision", 0),
                    ).let {
                        if (it.status == "pending") it.copy(status = "unknown", message = "Transfer interrupted; receipt unknown. Retry uses the same request ID.") else it
                    }
                }
                CaptureStore(records) { next ->
                    // ponytail: small personal history uses synchronous atomic preferences commits;
                    // move to Room on a worker dispatcher if history volume causes UI stalls.
                    val json = JSONArray()
                    next.forEach { item ->
                        json.put(JSONObject().apply {
                            put("requestId", item.requestId); put("type", item.type); put("text", item.text)
                            put("capturedAt", item.capturedAt); put("timeZone", item.timeZone)
                            put("status", item.status); put("message", item.message); put("attempt", item.attempt)
                            put("resultRevision", item.resultRevision)
                        })
                    }
                    check(prefs.edit().putString("records", json.toString()).commit()) { "Could not save captures on watch" }
                }.also { instance = it }
            }
        }
    }
}
