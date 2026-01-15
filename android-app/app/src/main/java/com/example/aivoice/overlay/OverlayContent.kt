package com.example.aivoice.overlay

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aivoice.network.ConnectionState
import com.example.aivoice.ui.theme.AIVoiceAssistantTheme
import com.example.aivoice.viewmodel.VoiceAssistantViewModel

@Composable
fun OverlayContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val viewModel: VoiceAssistantViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as Application
        )
    )
    val uiState by viewModel.uiState.collectAsState()
    val isConnected = uiState.connectionState == ConnectionState.CONNECTED

    AIVoiceAssistantTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .align(Alignment.BottomCenter)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = Color.White
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .size(width = 32.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.Gray.copy(alpha = 0.3f))
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.Gray
                            )
                        }
                    }

                    OverlayChatMessageList(
                        messages = uiState.chatMessages,
                        pendingUserText = uiState.pendingUserText,
                        pendingAssistantText = uiState.pendingAssistantText,
                        isListening = uiState.isListening,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    uiState.errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    OverlayBottomBar(
                        isConnected = isConnected,
                        isListening = uiState.isListening,
                        inputMode = uiState.inputMode,
                        textInput = uiState.textInput,
                        onTextInputChange = viewModel::updateTextInput,
                        onSendText = viewModel::sendTextMessage,
                        onMicClick = {
                            if (isConnected) {
                                viewModel.disconnect()
                            } else {
                                viewModel.connect()
                            }
                        },
                        onModeToggle = viewModel::toggleInputMode
                    )
                }
            }
        }

        LaunchedEffect(Unit) {
            if (!isConnected && uiState.serverUrl.isNotBlank()) {
                viewModel.connect()
            }
        }
    }
}
