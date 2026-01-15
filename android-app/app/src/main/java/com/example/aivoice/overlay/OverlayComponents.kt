package com.example.aivoice.overlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.aivoice.viewmodel.ChatMessage

private val AccentColor = Color(0xFF1A73E8)

@Composable
fun OverlayChatMessageList(
    messages: List<ChatMessage>,
    pendingUserText: String,
    pendingAssistantText: String,
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val totalItems = messages.size +
        (if (pendingUserText.isNotEmpty()) 1 else 0) +
        (if (pendingAssistantText.isNotEmpty()) 1 else 0)

    LaunchedEffect(totalItems, pendingUserText, pendingAssistantText) {
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    val isEmpty = messages.isEmpty() && pendingUserText.isEmpty() && pendingAssistantText.isEmpty()

    Box(modifier = modifier) {
        if (isEmpty) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isListening) "Listening..." else "Hi, how can I help?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
            ) {
                items(messages, key = { it.timestamp }) { message ->
                    OverlayChatBubble(text = message.text, isFromUser = message.isFromUser)
                }
                if (pendingUserText.isNotEmpty()) {
                    item(key = "pending_user") {
                        OverlayChatBubble(text = pendingUserText, isFromUser = true, isStreaming = true)
                    }
                }
                if (pendingAssistantText.isNotEmpty()) {
                    item(key = "pending_assistant") {
                        OverlayChatBubble(text = pendingAssistantText, isFromUser = false, isStreaming = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayChatBubble(
    text: String,
    isFromUser: Boolean,
    isStreaming: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (isFromUser) Color(0xFFE3F2FD) else Color(0xFFF5F5F5),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = if (isStreaming) "$text▌" else text,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Black
            )
        }
    }
}

@Composable
fun OverlayBottomBar(
    isConnected: Boolean,
    isListening: Boolean,
    onMicClick: () -> Unit
) {
    val micScale by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onMicClick,
            modifier = Modifier
                .size(56.dp)
                .scale(micScale)
                .background(
                    color = if (isListening) AccentColor else Color(0xFFF1F3F4),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = if (isConnected) "Stop" else "Start",
                tint = if (isListening) Color.White else AccentColor,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
