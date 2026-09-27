package com.yshah.alfred.wear.capture

import org.junit.Assert.*
import org.junit.Test

class ResultOrderingTest {
    @Test fun retryAcceptanceWinsButStaleAcknowledgementsAndLegacyResultsCannotRegress() {
        var disk = listOf(CaptureRecord(requestId = "id", text = "task", status = "local_queued"))
        val store = CaptureStore(disk) { disk = it }
        fun receive(status: String, revision: Long?) = store.receive("/alfred/result/id", "id", status, status, revision)
        receive("uncertain", 2)
        receive("accepted", 3)
        assertEquals("accepted", disk.single().status)
        receive("http_error", 2)
        receive("uncertain", null)
        assertEquals("accepted", disk.single().status)
        receive("success", 4)
        receive("accepted", 3)
        receive("uncertain", 4)
        assertEquals("success", disk.single().status)
        assertEquals(4L, disk.single().resultRevision)
        val restored = CaptureStore(disk)
        restored.receive("/alfred/result/id", "id", "accepted", "late", 1)
        assertEquals(disk, restored.records.value)
    }

    @Test fun legacyFailureUpgradesToOrderedRetryAndRejectsInvalidRevisions() {
        val store = CaptureStore(listOf(CaptureRecord(requestId = "id", status = "local_queued")))
        store.receive("/alfred/result/id", "id", "uncertain", "failed")
        store.receive("/alfred/result/id", "id", "accepted", "late")
        assertEquals("uncertain", store.records.value.single().status)
        assertFalse(store.receive("/alfred/result/id", "id", "accepted", "invalid", 0))
        store.receive("/alfred/result/id", "id", "accepted", "retry", 3)
        assertEquals("accepted", store.records.value.single().status)
    }
}
