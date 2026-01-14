package com.example.aivoice.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.aivoice.network.ConnectionState
import com.example.aivoice.viewmodel.UiState
import com.example.aivoice.viewmodel.VoiceAssistantViewModel

@Composable
fun VoiceAssistantScreen(viewModel: VoiceAssistantViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ConnectionStatusIndicator(state = uiState.connectionState)

            Spacer(modifier = Modifier.height(24.dp))

            if (uiState.connectionState == ConnectionState.DISCONNECTED) {
                ServerUrlInput(
                    url = uiState.serverUrl,
                    onUrlChange = viewModel::updateServerUrl
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            MainButton(
                uiState = uiState,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (uiState.transcript.isNotEmpty()) {
                TranscriptDisplay(text = uiState.transcript)
            }

            if (uiState.lastToolCall.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                ToolCallDisplay(text = uiState.lastToolCall)
            }

            uiState.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                ErrorDisplay(message = error)
            }
        }
    }
}

@Composable
private fun ConnectionStatusIndicator(state: ConnectionState) {
    val color by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.DISCONNECTED -> Color.Gray
            ConnectionState.CONNECTING -> Color.Yellow
            ConnectionState.CONNECTED -> Color.Green
            ConnectionState.ERROR -> Color.Red
        },
        animationSpec = tween(300),
        label = "status_color"
    )

    val statusText = when (state) {
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.ERROR -> "Error"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color = color, shape = CircleShape)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ServerUrlInput(
    url: String,
    onUrlChange: (String) -> Unit
) {
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text("Server URL") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun MainButton(
    uiState: UiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = uiState.connectionState == ConnectionState.CONNECTED
    val isConnecting = uiState.connectionState == ConnectionState.CONNECTING

    val scale by animateFloatAsState(
        targetValue = if (uiState.isListening) 1.1f else 1f,
        animationSpec = tween(500),
        label = "button_scale"
    )

    val buttonColor = when {
        uiState.isListening -> MaterialTheme.colorScheme.primary
        isConnected -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    Button(
        onClick = {
            if (isConnected) onDisconnect() else onConnect()
        },
        enabled = !isConnecting,
        modifier = Modifier
            .size(120.dp)
            .scale(scale),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
    ) {
        Text(
            text = when {
                isConnecting -> "..."
                isConnected -> if (uiState.isListening) "Listening" else "Stop"
                else -> "Connect"
            },
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun TranscriptDisplay(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Assistant:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ToolCallDisplay(text: String) {
    Text(
        text = "Tool: $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary
    )
}

@Composable
private fun ErrorDisplay(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
}
