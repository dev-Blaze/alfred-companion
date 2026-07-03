package com.yshah.alfred.wear.capture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {

    private val viewModel: CaptureViewModel by viewModels()

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.startCapture(viewModel.ui.value.mode)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CaptureScreen()
            }
        }
    }

    // Capture-on-open: every foreground entry starts listening in the current mode (task by
    // default) — the whole point of the watch app is raise-wrist → speak, no taps needed.
    override fun onStart() {
        super.onStart()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startCapture(viewModel.ui.value.mode)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        // Don't hold the mic while backgrounded; an in-flight send is unaffected.
        viewModel.cancelCapture()
        super.onStop()
    }
}

@Composable
private fun CaptureScreen(viewModel: CaptureViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = statusText(ui.phase),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (ui.transcript.isNotEmpty()) {
            Text(
                text = ui.transcript,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(
                label = "Task",
                selected = ui.mode == "task",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.startCapture("task") },
            )
            ModeChip(
                label = "Note",
                selected = ui.mode == "note",
                modifier = Modifier.weight(1f),
                onClick = { viewModel.startCapture("note") },
            )
        }
    }
}

/**
 * Selected mode renders as a filled button, the other as tonal. Tapping either (re)starts
 * listening in that mode — which doubles as the retry affordance after an error.
 */
@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    } else {
        FilledTonalButton(onClick = onClick, modifier = modifier) {
            Text(label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun statusText(phase: CapturePhase): String = when (phase) {
    is CapturePhase.Idle -> "Alfred"
    is CapturePhase.Listening -> "Listening…"
    is CapturePhase.Sending -> "Sending…"
    // Deliberately not "Sent" — the capture is locally queued and syncs when the phone is
    // reachable, which is the designed offline behavior, not a failure.
    is CapturePhase.Queued -> "Captured ✓"
    is CapturePhase.Error -> phase.message
}
