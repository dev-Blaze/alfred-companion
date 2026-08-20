package com.yshah.alfred.wear.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ViewModel is the only branching logic in the app, and the failure it guards against is
 * silent: a stale send writing an error over a capture that is actually fine. Everything else
 * needs a real recognizer or Play Services, so it stays out of the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val capture = FakeSpeechCapture()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(transmit: suspend (String, String) -> Unit = { _, _ -> }) =
        CaptureViewModel(capture, transmit)

    @Test
    fun `a new capture started mid-send is never clobbered by the old send`() = runTest(dispatcher) {
        val inFlight = CompletableDeferred<Unit>()
        val vm = viewModel { _, _ -> inFlight.await() }

        vm.startCapture("task")
        capture.emit(SpeechCapture.Event.Final("first one"))
        assertEquals(CapturePhase.Sending, vm.ui.value.phase)

        // User speaks again before the first send resolves.
        vm.startCapture("note")
        assertEquals(CapturePhase.Listening, vm.ui.value.phase)

        inFlight.complete(Unit)
        advanceUntilIdle()

        assertEquals("the stale send overwrote a live capture", CapturePhase.Listening, vm.ui.value.phase)
        assertEquals("note", vm.ui.value.mode)
    }

    @Test
    fun `switching mode during the Captured window does not surface a cancellation error`() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.startCapture("task")
            capture.emit(SpeechCapture.Event.Final("buy milk"))
            // runCurrent, not advanceUntilIdle — the latter would burn through the 2.5s reset
            // delay and land on Idle, skipping the window this test is about.
            runCurrent()
            assertEquals(CapturePhase.Queued, vm.ui.value.phase)

            // Inside the 2.5s status reset, which used to be cancelled and caught as an Exception.
            vm.startCapture("note")
            advanceUntilIdle()

            assertEquals(CapturePhase.Listening, vm.ui.value.phase)
        }

    @Test
    fun `backgrounding mid-sentence sends what was heard instead of dropping it`() =
        runTest(dispatcher) {
            val sent = mutableListOf<Pair<String, String>>()
            val vm = viewModel { type, text -> sent += type to text }

            vm.startCapture("task")
            capture.emit(SpeechCapture.Event.Partial("call the dentist"))
            vm.cancelCapture()
            advanceUntilIdle()

            assertEquals(listOf("task" to "call the dentist"), sent)
        }

    @Test
    fun `backgrounding before any words leaves nothing to send`() = runTest(dispatcher) {
        val sent = mutableListOf<Pair<String, String>>()
        val vm = viewModel { type, text -> sent += type to text }

        vm.startCapture("task")
        vm.cancelCapture()
        advanceUntilIdle()

        assertEquals(emptyList<Pair<String, String>>(), sent)
        assertEquals(CapturePhase.Idle, vm.ui.value.phase)
    }

    @Test
    fun `restarting the same mode while listening does not rebind the recognizer`() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.startCapture("task")
            vm.startCapture("task")

            assertEquals("recognizer was restarted mid-listen", 1, capture.startCount)
        }

    @Test
    fun `a send that never resolves times out instead of hanging on Sending`() =
        runTest(dispatcher) {
            val vm = viewModel { _, _ -> CompletableDeferred<Unit>().await() }

            vm.startCapture("task")
            capture.emit(SpeechCapture.Event.Final("something"))
            // Virtual time, so this returns as soon as the withTimeout fires — it does not
            // spend 10 real seconds, and it can't hang on the deferred that never completes.
            advanceUntilIdle()

            val phase = vm.ui.value.phase
            assertTrue("expected an error, got $phase", phase is CapturePhase.Error)
        }

    private class FakeSpeechCapture : SpeechCapture {
        var startCount = 0
        private var onEvent: ((SpeechCapture.Event) -> Unit)? = null

        override fun isAvailable() = true
        override fun start(onEvent: (SpeechCapture.Event) -> Unit) {
            startCount++
            this.onEvent = onEvent
        }
        override fun cancel() { onEvent = null }

        fun emit(event: SpeechCapture.Event) = onEvent!!.invoke(event)
    }
}
