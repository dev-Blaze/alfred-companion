package com.yshah.alfred.wear.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.yshah.alfred.wear.datalayer.MODE_NOTE
import com.yshah.alfred.wear.datalayer.MODE_TASK
import com.yshah.alfred.wear.datalayer.sendCapture

class CaptureActivity : ComponentActivity() {

    private val viewModel: CaptureViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = applicationContext
                CaptureViewModel(
                    speechCapture = WearSpeechCapture(app),
                    transmit = { type, text -> sendCapture(app, type, text) },
                )
            }
        }
    }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // onStart may already have started capture if the OEM's permission UI stopped
                // this activity; startCapture is idempotent while listening, so this is safe
                // on the devices where it doesn't.
                viewModel.startCapture(viewModel.ui.value.mode)
            } else {
                // Once shouldShowRequestPermissionRationale goes false after a denial, further
                // requests return instantly without any UI — settings is the only way back.
                viewModel.onPermissionDenied(
                    permanent = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppScaffold {
                    ScreenScaffold {
                        CaptureScreen(viewModel, onOpenSettings = ::openAppSettings)
                    }
                }
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
        // Don't hold the mic while backgrounded. Anything transcribed so far is sent rather
        // than dropped (see CaptureViewModel.cancelCapture); an in-flight send is unaffected.
        viewModel.cancelCapture()
        super.onStop()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
    }
}

@Composable
private fun CaptureScreen(viewModel: CaptureViewModel, onOpenSettings: () -> Unit) {
    val ui by viewModel.ui.collectAsState()

    // A watch blanks its screen ~15s after the last touch, and this app is designed never to be
    // touched — speaking doesn't count. Without this the display sleeps mid-sentence, the
    // activity stops, and the capture is lost. Released as soon as we're no longer capturing so
    // the watch can sleep normally.
    KeepScreenOn(ui.phase is CapturePhase.Listening || ui.phase is CapturePhase.Sending)

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
        val phase = ui.phase
        if (phase is CapturePhase.Error && phase.needsSettings) {
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open settings", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(
                    label = "Task",
                    selected = ui.mode == MODE_TASK,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.startCapture(MODE_TASK) },
                )
                ModeChip(
                    label = "Note",
                    selected = ui.mode == MODE_NOTE,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.startCapture(MODE_NOTE) },
                )
            }
        }
    }
}

@Composable
private fun KeepScreenOn(active: Boolean) {
    val window = LocalActivity.current?.window
    DisposableEffect(active, window) {
        if (active) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
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
