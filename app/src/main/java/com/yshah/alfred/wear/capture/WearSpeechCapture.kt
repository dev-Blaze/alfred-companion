package com.yshah.alfred.wear.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * The three recognizer operations [CaptureViewModel] needs. Exists so the ViewModel's state
 * machine can be exercised on the JVM without a Context or a live RecognitionService.
 */
interface SpeechCapture {
    sealed class Event {
        data class Partial(val text: String) : Event()
        data class Final(val text: String) : Event()
        data class Failed(val message: String) : Event()
    }

    fun isAvailable(): Boolean
    fun start(onEvent: (Event) -> Unit)
    fun cancel()
}

/**
 * In-app speech capture with silence auto-stop, ported from the phone app's
 * SpeechRecognizerCaptureController. Auto-stop is driven by our own debounce on partial results
 * and on onEndOfSpeech — the EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS intent extras are
 * inconsistently honored across OEM recognizer services, so a pause after the last partial
 * transcript is the reliable end-of-utterance signal. A recognizer that emits no partials at all
 * would otherwise never arm the debounce, hence the independent [MAX_LISTEN_MS] ceiling.
 *
 * Prefers the on-device recognizer when the platform has one, so a capture still works with no
 * route to the internet (out of phone range and no Wi-Fi) — the default recognizer streams audio
 * to a server. Falls back to the default recognizer once if the on-device one can't run.
 *
 * All entry points must be called on the main thread (SpeechRecognizer requirement).
 */
class WearSpeechCapture(private val context: Context) : SpeechCapture {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val silenceRunnable = Runnable {
        Log.d(TAG, "silence debounce elapsed, stopping")
        recognizer?.stopListening()
    }

    private val hardStopRunnable = Runnable {
        Log.w(TAG, "hit the ${MAX_LISTEN_MS}ms listening ceiling, stopping")
        recognizer?.stopListening()
    }

    private var recognizer: SpeechRecognizer? = null
    private var onEvent: ((SpeechCapture.Event) -> Unit)? = null
    private var usingOnDevice = false
    private var networkFallbackUsed = false

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(onEvent: (SpeechCapture.Event) -> Unit) {
        networkFallbackUsed = false
        startSession(onEvent, onDevice = onDeviceAvailable())
    }

    override fun cancel() {
        mainHandler.removeCallbacks(silenceRunnable)
        mainHandler.removeCallbacks(hardStopRunnable)
        onEvent = null
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun startSession(onEvent: (SpeechCapture.Event) -> Unit, onDevice: Boolean) {
        cancel()
        this.onEvent = onEvent
        usingOnDevice = onDevice
        Log.d(TAG, "starting session, onDevice=$onDevice")
        recognizer = createRecognizer(onDevice).also {
            it.setRecognitionListener(listener)
            it.startListening(recognizerIntent())
        }
        mainHandler.postDelayed(hardStopRunnable, MAX_LISTEN_MS)
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun createRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Some recognizer builds answer ERROR_CLIENT when the calling package is absent.
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun armSilenceDebounce() {
        mainHandler.removeCallbacks(silenceRunnable)
        mainHandler.postDelayed(silenceRunnable, AUTO_STOP_SILENCE_DEBOUNCE_MS)
    }

    /** No-match/timeout/permission are answers, not recognizer failures — don't burn the retry. */
    private fun isRecognizerFailure(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
        -> false
        else -> true
    }

    private fun bestResult(bundle: Bundle?): String = bundle
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        .orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        // Free end-of-utterance signal, and the only one a recognizer that never emits partials
        // gives us before its own (untrusted) endpointing decides to fire.
        override fun onEndOfSpeech() = armSilenceDebounce()

        override fun onPartialResults(partialResults: Bundle?) {
            val text = bestResult(partialResults)
            if (text.isNotEmpty()) {
                onEvent?.invoke(SpeechCapture.Event.Partial(text))
            }
            armSilenceDebounce()
        }

        override fun onResults(results: Bundle?) {
            mainHandler.removeCallbacks(silenceRunnable)
            mainHandler.removeCallbacks(hardStopRunnable)
            val text = bestResult(results)
            Log.d(TAG, "final result, ${text.length} chars")
            val callback = onEvent
            // Session is over — release the recognizer before dispatching so a callback that
            // immediately restarts capture doesn't race the old session's teardown.
            cancel()
            callback?.invoke(SpeechCapture.Event.Final(text))
        }

        override fun onError(error: Int) {
            mainHandler.removeCallbacks(silenceRunnable)
            mainHandler.removeCallbacks(hardStopRunnable)
            Log.w(TAG, "recognizer error $error (onDevice=$usingOnDevice)")
            val callback = onEvent

            // An on-device recognizer with no downloaded model for the active locale fails
            // immediately; the networked one is still worth a shot before bothering the user.
            if (usingOnDevice && !networkFallbackUsed && isRecognizerFailure(error) && callback != null) {
                networkFallbackUsed = true
                startSession(callback, onDevice = false)
                return
            }

            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> "Didn't catch that"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> "Speech service needs network"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing"
                else -> "Speech error ($error)"
            }
            cancel()
            callback?.invoke(SpeechCapture.Event.Failed(message))
        }
    }

    private companion object {
        const val TAG = "AlfredCapture"
        const val AUTO_STOP_SILENCE_DEBOUNCE_MS = 1400L
        const val MAX_LISTEN_MS = 20_000L
    }
}
