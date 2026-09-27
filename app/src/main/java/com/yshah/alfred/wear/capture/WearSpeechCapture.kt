package com.yshah.alfred.wear.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

interface SpeechCapture {
    sealed class Event {
        data class Partial(val text: String) : Event()
        data class Final(val text: String) : Event()
        data class Failed(val message: String) : Event()
    }
    fun isAvailable(): Boolean
    fun start(onEvent: (Event) -> Unit)
    fun stop()
    fun cancel()
}

/** Main-thread only. Each recognizer owns its listener; destroyed sessions cannot call a new one. */
class WearSpeechCapture(private val context: Context) : SpeechCapture {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var callback: ((SpeechCapture.Event) -> Unit)? = null
    private var generation = 0
    private var stopping = false
    private var fallbackUsed = false
    private var deadline = 0L
    private val silence = Runnable { stop() }
    private val hardStop = Runnable { stop() }
    private val finalDeadline = Runnable {
        finish(SpeechCapture.Event.Failed("Speech finalization timed out"))
    }

    override fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context) ||
        onDeviceAvailable()

    override fun start(onEvent: (SpeechCapture.Event) -> Unit) {
        cancel()
        callback = onEvent
        fallbackUsed = false
        deadline = android.os.SystemClock.uptimeMillis() + 20_000
        startSession(onDeviceAvailable())
    }

    private fun startSession(onDevice: Boolean) {
        val token = ++generation
        stopping = false
        try {
            val engine = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                fun active() = generation == token && callback != null
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onEndOfSpeech() { if (active()) stop() }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (!active()) return
                    val text = bestResult(partialResults)
                    if (text.isNotBlank()) callback?.invoke(SpeechCapture.Event.Partial(text))
                    if (!stopping) {
                        handler.removeCallbacks(silence)
                        handler.postDelayed(silence, 1400)
                    }
                }
                override fun onResults(results: Bundle?) {
                    if (active()) finish(SpeechCapture.Event.Final(bestResult(results)))
                }
                override fun onError(error: Int) {
                    if (!active()) return
                    if (onDevice && !fallbackUsed && !stopping &&
                        android.os.SystemClock.uptimeMillis() < deadline &&
                        error !in setOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    ) {
                        fallbackUsed = true
                        release()
                        startSession(false)
                    } else {
                        finish(SpeechCapture.Event.Failed(when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service needs network"
                            else -> "Speech error ($error)"
                        }))
                    }
                }
            })
            handler.postAtTime(hardStop, deadline)
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            })
        } catch (_: SecurityException) {
            finish(SpeechCapture.Event.Failed("Microphone permission missing; open settings"))
        } catch (_: Exception) {
            finish(SpeechCapture.Event.Failed("Speech service unavailable"))
        }
    }

    override fun stop() {
        if (callback == null || stopping) return
        stopping = true
        handler.removeCallbacks(silence)
        handler.removeCallbacks(hardStop)
        handler.postDelayed(finalDeadline, 3000)
        try { recognizer?.stopListening() } catch (_: Exception) {
            finish(SpeechCapture.Event.Failed("Speech could not finish"))
        }
    }

    private fun finish(event: SpeechCapture.Event) {
        val target = callback
        cancel()
        target?.invoke(event)
    }

    override fun cancel() {
        callback = null
        release()
    }

    private fun release() {
        generation++
        handler.removeCallbacks(silence)
        handler.removeCallbacks(hardStop)
        handler.removeCallbacks(finalDeadline)
        val old = recognizer
        recognizer = null
        try { old?.cancel() } catch (_: Exception) { /* Already invalidated. */ }
        try { old?.destroy() } catch (_: Exception) { /* A dead service cannot own a new callback. */ }
    }

    private fun bestResult(bundle: Bundle?) = bundle
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun onDeviceAvailable() = Build.VERSION.SDK_INT >= 31 &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
}
