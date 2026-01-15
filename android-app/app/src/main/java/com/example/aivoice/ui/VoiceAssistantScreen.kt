package com.example.aivoice.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.aivoice.network.ConnectionState
import com.example.aivoice.viewmodel.ChatMessage
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionStatusIndicator(state = uiState.connectionState)

                MainButton(
                    uiState = uiState,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect
                )
            }

            if (uiState.connectionState == ConnectionState.DISCONNECTED) {
                Spacer(modifier = Modifier.height(16.dp))
                ServerUrlInput(
                    url = uiState.serverUrl,
                    onUrlChange = viewModel::updateServerUrl
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            ChatMessageList(
                messages = uiState.chatMessages,
                modifier = Modifier.weight(1f)
            )

            if (uiState.lastToolCall.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ToolCallDisplay(text = uiState.lastToolCall)
            }

            uiState.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
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
        targetValue = if (uiState.isListening) 1.05f else 1f,
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
            .size(72.dp)
            .scale(scale),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
    ) {
        Text(
            text = when {
                isConnecting -> "..."
                isConnected -> if (uiState.isListening) "On" else "Off"
                else -> "Go"
            },
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ChatMessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    if (messages.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Start talking to see the conversation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.timestamp }) { message ->
                ChatBubble(message = message)
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = if (message.isFromUser)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isFromUser) 16.dp else 4.dp,
                        bottomEnd = if (message.isFromUser) 4.dp else 16.dp
                    )
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.isFromUser)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
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
