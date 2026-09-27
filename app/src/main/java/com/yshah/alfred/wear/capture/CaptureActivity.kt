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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.wear.compose.material3.*
import com.yshah.alfred.wear.datalayer.refreshResults
import com.yshah.alfred.wear.datalayer.sendCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CaptureActivity : ComponentActivity() {
    private val viewModel: CaptureViewModel by viewModels {
        viewModelFactory {
            initializer {
                CaptureViewModel(WearSpeechCapture(applicationContext), CaptureStore.get(applicationContext),
                    hasMicPermission = { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                    transmit = { sendCapture(applicationContext, it) })
            }
        }
    }
    private var permissionPending = false
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (granted && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) viewModel.startCapture()
        else if (!granted) viewModel.onPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { AppScaffold { ScreenScaffold {
                CaptureScreen(viewModel, ::requestCapture, ::openAppSettings)
            } } }
        }
    }

    override fun onStart() {
        super.onStart()
        // A restored draft/history is never replaced by capture-on-open.
        if (viewModel.ui.value.records.isEmpty() && viewModel.ui.value.message.isEmpty()) requestCapture()
        lifecycleScope.launch {
            try { refreshResults(applicationContext) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Persisted statuses remain visible while disconnected. */ }
        }
    }

    private fun requestCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startCapture()
        } else if (!permissionPending) {
            permissionPending = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        viewModel.interruptCapture()
        super.onStop()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}

@Composable
private fun CaptureScreen(viewModel: CaptureViewModel, onRecord: () -> Unit, onSettings: () -> Unit) {
    val ui by viewModel.ui.collectAsState()
    val haptics = LocalHapticFeedback.current
    KeepScreenOn(ui.listening)
    LaunchedEffect(ui.listening) {
        if (ui.listening) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val draft = ui.draft
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(if (ui.listening) "Listening…" else "Alfred", textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (ui.message.isNotEmpty()) item {
            Text(ui.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (ui.needsSettings) item { ActionButton("Microphone settings", onSettings) }
        item {
            ModeButton("Task", (draft?.type ?: ui.mode) == "task") { viewModel.selectMode("task") }
        }
        item {
            ModeButton("Note", (draft?.type ?: ui.mode) == "note") { viewModel.selectMode("note") }
        }
        if (draft != null) {
            item { Text("Draft — review before sending") }
            item {
                BasicTextField(
                    value = draft.text, onValueChange = viewModel::editText,
                    readOnly = ui.listening,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(8.dp)
                        .semantics { contentDescription = "Draft text. Tap to edit" },
                )
            }
            if (!ui.listening) item {
                Button(onClick = {
                    viewModel.send(draft.requestId)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }, enabled = draft.text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Send") }
            }
            item { ActionButton("Cancel draft", viewModel::cancelDraft) }
        }
        item {
            if (ui.listening) ActionButton("Stop and review", viewModel::stopListening)
            else ActionButton(if (draft == null) "Record" else "Record again", onRecord)
        }
        if (draft == null) item { ActionButton("Type a draft") { viewModel.editText("") } }
        if (ui.records.any { it.status != "draft" }) item { Text("Requests and results") }
        items(ui.records.filter { it.status != "draft" }.asReversed(), key = { it.requestId }) { record ->
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${record.type.replaceFirstChar { it.uppercase() }} · ${record.statusLabel}",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(record.text)
                if (record.message.isNotBlank()) Text(record.message)
                if (record.canRetry) ActionButton("Retry transfer") { viewModel.send(record.requestId) }
                if (record.status in setOf("http_error", "uncertain")) {
                    Text("Check phone History to retry processing. The server may already have executed this request.")
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ModeButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics { selected = isSelected; role = Role.RadioButton }) {
        Text(if (isSelected) "$label — selected" else label)
    }
}

@Composable
private fun KeepScreenOn(active: Boolean) {
    val window = LocalActivity.current?.window
    DisposableEffect(active, window) {
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
