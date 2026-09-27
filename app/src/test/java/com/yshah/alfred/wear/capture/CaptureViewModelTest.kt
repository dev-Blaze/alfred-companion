package com.yshah.alfred.wear.capture

import com.yshah.alfred.wear.datalayer.capturePath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val speech = FakeSpeech()
    private var permission = true
    private var disk = emptyList<CaptureRecord>()
    private val store = CaptureStore { disk = it }
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()
    private fun model(transmit: suspend (CaptureRecord) -> Unit = {}) =
        CaptureViewModel(speech, store, { permission }, transmit)

    @Test fun `interruption persists draft and ignores late final without sending`() = runTest(dispatcher) {
        val sent = mutableListOf<CaptureRecord>()
        val vm = model { sent += it }
        vm.startCapture()
        val stale = speech.callback!!
        speech.emit(SpeechCapture.Event.Partial("call dentist"))
        vm.interruptCapture()
        stale(SpeechCapture.Event.Final("incorrect late result"))
        runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals("call dentist", disk.single().text)
        assertEquals("draft", disk.single().status)
        val restored = CaptureViewModel(speech, CaptureStore(disk), { permission }) {}
        assertEquals(vm.ui.value.draft, restored.ui.value.draft)
    }

    @Test fun `final requires send and correction keeps capture metadata`() = runTest(dispatcher) {
        val sent = mutableListOf<CaptureRecord>()
        val vm = model { sent += it }
        vm.startCapture()
        val original = disk.single()
        speech.emit(SpeechCapture.Event.Final("buy mil"))
        runCurrent()
        assertTrue(sent.isEmpty())
        vm.editText("buy milk")
        vm.send(original.requestId)
        advanceUntilIdle()
        assertEquals("buy milk", sent.single().text)
        assertEquals(original.requestId, sent.single().requestId)
        assertEquals(original.capturedAt, sent.single().capturedAt)
        assertEquals(original.timeZone, sent.single().timeZone)
        assertEquals("local_queued", disk.single().status)
    }

    @Test fun `timeout is unknown and retry uses same payload and path`() = runTest(dispatcher) {
        val sent = mutableListOf<CaptureRecord>()
        val vm = model {
            sent += it
            if (sent.size == 1) CompletableDeferred<Unit>().await()
        }
        vm.startCapture()
        speech.emit(SpeechCapture.Event.Final("buy milk"))
        val id = disk.single().requestId
        vm.send(id)
        advanceUntilIdle()
        assertEquals("unknown", disk.single().status)
        assertEquals("buy milk", disk.single().text)
        vm.send(id)
        advanceUntilIdle()
        assertEquals(sent[0].copy(attempt = sent[1].attempt), sent[1])
        assertEquals(capturePath(sent[0].requestId), capturePath(sent[1].requestId))
    }

    @Test fun `failure retains transcript and result arriving during transfer wins`() = runTest(dispatcher) {
        var fail = true
        val vm = model { item ->
            if (fail) error("offline")
            store.receive("/alfred/result/${item.requestId}", item.requestId, "success", "Done")
        }
        vm.editText("remember this")
        val id = disk.single().requestId
        vm.send(id)
        advanceUntilIdle()
        assertEquals("remember this", disk.single().text)
        assertEquals("unknown", disk.single().status)
        fail = false
        vm.send(id)
        advanceUntilIdle()
        assertEquals("success", disk.single().status)
        store.receive("/alfred/result/$id", id, "accepted", "Saved on phone")
        assertEquals("Done", disk.single().message)
    }

    @Test fun `every recording attempt checks permission and preserves draft`() = runTest(dispatcher) {
        val vm = model()
        vm.editText("retained")
        permission = false
        vm.startCapture()
        assertEquals(0, speech.starts)
        assertTrue(vm.ui.value.needsSettings)
        permission = true
        vm.startCapture()
        assertEquals(1, speech.starts)
        vm.interruptCapture()
        permission = false
        vm.startCapture()
        assertEquals(1, speech.starts)
        assertEquals("retained", disk.single().text)
    }

    @Test fun `speech finalization failure preserves partial and cancel prevents stale writes`() = runTest(dispatcher) {
        val vm = model()
        vm.startCapture()
        speech.emit(SpeechCapture.Event.Partial("partial"))
        vm.stopListening()
        assertEquals(1, speech.stops)
        speech.emit(SpeechCapture.Event.Failed("Speech finalization timed out"))
        assertFalse(vm.ui.value.listening)
        assertEquals("partial", disk.single().text)
        vm.startCapture()
        val stale = speech.callback!!
        vm.cancelDraft()
        stale(SpeechCapture.Event.Final("stale"))
        assertTrue(disk.isEmpty())
    }

    @Test fun `result validation rejects mismatched unknown and draft requests`() {
        val item = CaptureRecord(text = "draft")
        store.change { listOf(item) }
        assertFalse(store.receive("/alfred/result/other", item.requestId, "success", "Done"))
        assertFalse(store.receive("/alfred/result/${item.requestId}", item.requestId, "surprise", "Done"))
        assertFalse(store.receive("/alfred/result/${item.requestId}", item.requestId, "success", "Done"))
        assertEquals("draft", disk.single().status)
    }

    @Test fun `storage failure never transmits or publishes acceptance`() = runTest(dispatcher) {
        var sends = 0
        val broken = CaptureStore { error("disk full") }
        val vm = CaptureViewModel(speech, broken, { true }) { sends++ }
        vm.editText("important")
        vm.startCapture()
        advanceUntilIdle()
        assertEquals(0, sends)
        assertEquals(0, speech.starts)
        assertTrue(vm.ui.value.message.contains("storage"))
        assertTrue(broken.records.value.isEmpty())
    }

    @Test fun `receipt racing a retry cannot be overwritten by pending`() = runTest(dispatcher) {
        val record = CaptureRecord(text = "buy milk", status = "local_queued")
        store.change { listOf(record) }
        var sends = 0
        val vm = model { sends++ }
        speech.onCancel = {
            store.receive("/alfred/result/${record.requestId}", record.requestId, "success", "Done")
        }
        vm.send(record.requestId)
        advanceUntilIdle()
        assertEquals(0, sends)
        assertEquals("success", disk.single().status)
    }

    private class FakeSpeech : SpeechCapture {
        var onCancel: () -> Unit = {}
        var callback: ((SpeechCapture.Event) -> Unit)? = null
        var starts = 0
        var stops = 0
        override fun isAvailable() = true
        override fun start(onEvent: (SpeechCapture.Event) -> Unit) { starts++; callback = onEvent }
        override fun stop() { stops++ }
        override fun cancel() { callback = null; onCancel() }
        fun emit(event: SpeechCapture.Event) = callback!!.invoke(event)
    }
}
