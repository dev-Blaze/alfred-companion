package com.yshah.alfred.wear.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CaptureScreen()
            }
        }
    }
}

@Composable
private fun CaptureScreen(viewModel: CaptureViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsState()

    // Which mode the in-flight speech capture belongs to. rememberSaveable because the system
    // speech activity fully covers ours and may cause it to be recreated before the result lands.
    var pendingType by rememberSaveable { mutableStateOf<String?>(null) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val type = pendingType
        pendingType = null
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (result.resultCode == Activity.RESULT_OK && type != null && text.isNotBlank()) {
            viewModel.send(type = type, text = text)
        }
    }

    fun startCapture(type: String) {
        pendingType = type
        // Speech capture is delegated to the system recognizer activity (round Wear OS mic UI)
        // rather than a raw SpeechRecognizer — it owns the mic, permissions, and error UX.
        speechLauncher.launch(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    if (type == "task") "Task for Alfred" else "Note for Alfred",
                )
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(statusText(status))
        Button(
            onClick = { startCapture("task") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Task")
        }
        FilledTonalButton(
            onClick = { startCapture("note") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Note")
        }
    }
}

private fun statusText(status: SendStatus): String = when (status) {
    is SendStatus.Idle -> "Alfred"
    is SendStatus.Sending -> "Sending…"
    // Deliberately not "Sent" — the capture is locally queued and syncs when the phone is
    // reachable, which is the designed offline behavior, not a failure.
    is SendStatus.Queued -> "Captured ✓"
    is SendStatus.Error -> "Error: ${status.message}"
}
