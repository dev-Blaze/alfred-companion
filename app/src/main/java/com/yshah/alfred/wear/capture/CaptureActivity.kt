package com.yshah.alfred.wear.capture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Temporary test hook: `adb shell am start ... --ez auto_send_test true` triggers a send
        // without needing a physical tap — useful for verifying the Data Layer relay when the
        // watch's screen won't reliably stay awake over wireless adb. Remove once real speech
        // capture (and its own on-device verification) lands.
        val autoSendTest = intent?.getBooleanExtra("auto_send_test", false) ?: false
        setContent {
            MaterialTheme {
                CaptureScreen(autoSendTest = autoSendTest)
            }
        }
    }
}

@Composable
private fun CaptureScreen(autoSendTest: Boolean = false, viewModel: CaptureViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsState()
    LaunchedEffect(autoSendTest) {
        if (autoSendTest) viewModel.sendTestCapture()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(statusText(status))
        Button(onClick = viewModel::sendTestCapture) {
            Text("Send test")
        }
    }
}

private fun statusText(status: SendStatus): String = when (status) {
    is SendStatus.Idle -> "Alfred"
    is SendStatus.Sending -> "Sending…"
    is SendStatus.Queued -> "Queued"
    is SendStatus.Error -> "Error: ${status.message}"
}
