package com.yshah.alfred.wear.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * In-app speech capture with silence auto-stop, ported from the phone app's
 * SpeechRecognizerCaptureController. Auto-stop is driven by our own debounce on partial
 * results — the EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS intent extras are
 * inconsistently honored across OEM recognizer services, so a pause after the last partial
 * transcript is the reliable end-of-utterance signal.
 *
 * All entry points must be called on the main thread (SpeechRecognizer requirement).
 */
class WearSpeechCapture(private val context: Context) {

    sealed class Event {
        data class Partial(val text: String) : Event()
        data class Final(val text: String) : Event()
        data class Failed(val message: String) : Event()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val silenceRunnable = Runnable { recognizer?.stopListening() }

    private var recognizer: SpeechRecognizer? = null
    private var onEvent: ((Event) -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(onEvent: (Event) -> Unit) {
        cancel()
        this.onEvent = onEvent
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            it.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
            )
        }
    }

    fun cancel() {
        mainHandler.removeCallbacks(silenceRunnable)
        onEvent = null
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
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
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = bestResult(partialResults)
            if (text.isNotEmpty()) {
                onEvent?.invoke(Event.Partial(text))
            }
            mainHandler.removeCallbacks(silenceRunnable)
            mainHandler.postDelayed(silenceRunnable, AUTO_STOP_SILENCE_DEBOUNCE_MS)
        }

        override fun onResults(results: Bundle?) {
            mainHandler.removeCallbacks(silenceRunnable)
            val text = bestResult(results)
            val callback = onEvent
            // Session is over — release the recognizer before dispatching so a callback that
            // immediately restarts capture doesn't race the old session's teardown.
            cancel()
            callback?.invoke(Event.Final(text))
        }

        override fun onError(error: Int) {
            mainHandler.removeCallbacks(silenceRunnable)
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
            val callback = onEvent
            cancel()
            callback?.invoke(Event.Failed(message))
        }
    }

    private companion object {
        const val AUTO_STOP_SILENCE_DEBOUNCE_MS = 1400L
    }
}
