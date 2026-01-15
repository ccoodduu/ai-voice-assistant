package io.github.ccoodduu.aivoice.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ccoodduu.aivoice.audio.AudioCaptureManager
import io.github.ccoodduu.aivoice.audio.AudioPlaybackManager
import io.github.ccoodduu.aivoice.network.ConnectionState
import io.github.ccoodduu.aivoice.network.InputMode
import io.github.ccoodduu.aivoice.network.WebSocketEvent
import io.github.ccoodduu.aivoice.network.WebSocketManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class UiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isListening: Boolean = false,
    val serverUrl: String = "",
    val transcript: String = "",
    val lastToolCall: String = "",
    val errorMessage: String? = null,
    val chatMessages: List<ChatMessage> = emptyList(),
    val pendingUserText: String = "",
    val pendingAssistantText: String = "",
    val inputMode: InputMode = InputMode.AUDIO,
    val textInput: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val webSocketManager = WebSocketManager()
    private val audioCaptureManager = AudioCaptureManager()
    private val audioPlaybackManager = AudioPlaybackManager()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val prefs = application.getSharedPreferences("voice_assistant_prefs", Context.MODE_PRIVATE)

    private var pendingUserText = StringBuilder()
    private var pendingAssistantText = StringBuilder()

    init {
        val savedUrl = prefs.getString("server_url", "") ?: ""
        _uiState.update { it.copy(serverUrl = savedUrl) }

        observeWebSocketEvents()
        observeConnectionState()
        observeAndCaptureAudio()

        if (savedUrl.isNotBlank()) {
            connect()
        }
    }

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            webSocketManager.events.collect { event ->
                when (event) {
                    is WebSocketEvent.Connected -> {
                        _uiState.update { it.copy(errorMessage = null) }
                    }
                    is WebSocketEvent.SessionReady -> {
                        audioPlaybackManager.initialize()
                    }
                    is WebSocketEvent.AudioReceived -> {
                        audioPlaybackManager.playAudio(event.data)
                    }
                    is WebSocketEvent.TranscriptReceived -> {
                        _uiState.update { it.copy(transcript = event.text) }
                    }
                    is WebSocketEvent.ToolCallReceived -> {
                        _uiState.update {
                            it.copy(lastToolCall = "${event.name}: ${event.status}")
                        }
                    }
                    is WebSocketEvent.UserTranscriptReceived -> {
                        // Skip in text mode - message already added locally when sent
                        if (_uiState.value.inputMode == InputMode.AUDIO) {
                            finalizeAssistantMessage()
                            val trimmed = event.text.trim()
                            if (trimmed.isNotEmpty()) {
                                val newText = smartJoin(pendingUserText.toString(), trimmed)
                                pendingUserText.clear()
                                pendingUserText.append(newText)
                                _uiState.update { it.copy(pendingUserText = newText) }
                            }
                        }
                    }
                    is WebSocketEvent.AssistantTranscriptReceived -> {
                        finalizeUserMessage()
                        val trimmed = event.text.trim()
                        if (trimmed.isNotEmpty()) {
                            val newText = smartJoin(pendingAssistantText.toString(), trimmed)
                            pendingAssistantText.clear()
                            pendingAssistantText.append(newText)
                            _uiState.update { it.copy(pendingAssistantText = newText) }
                        }
                    }
                    is WebSocketEvent.TurnComplete -> {
                        finalizeAssistantMessage()
                    }
                    is WebSocketEvent.AssistantTextReceived -> {
                        finalizeUserMessage()
                        val trimmed = event.text.trim()
                        if (trimmed.isNotEmpty()) {
                            val newText = smartJoin(pendingAssistantText.toString(), trimmed)
                            pendingAssistantText.clear()
                            pendingAssistantText.append(newText)
                            _uiState.update { it.copy(pendingAssistantText = newText) }
                        }
                    }
                    is WebSocketEvent.ModeChanged -> {
                        val mode = if (event.mode == "text") InputMode.TEXT else InputMode.AUDIO
                        _uiState.update { it.copy(inputMode = mode) }
                    }
                    is WebSocketEvent.Error -> {
                        _uiState.update {
                            it.copy(errorMessage = "${event.code}: ${event.message}")
                        }
                    }
                    is WebSocketEvent.Disconnected -> {
                        audioPlaybackManager.release()
                        pendingUserText.clear()
                        pendingAssistantText.clear()
                        _uiState.update {
                            it.copy(
                                chatMessages = emptyList(),
                                pendingUserText = "",
                                pendingAssistantText = ""
                            )
                        }
                    }
                }
            }
        }
    }

    private fun finalizeUserMessage() {
        if (pendingUserText.isNotEmpty()) {
            val text = pendingUserText.toString()
            pendingUserText.clear()
            _uiState.update {
                it.copy(
                    chatMessages = it.chatMessages + ChatMessage(text = text, isFromUser = true),
                    pendingUserText = ""
                )
            }
        }
    }

    private fun finalizeAssistantMessage() {
        if (pendingAssistantText.isNotEmpty()) {
            val text = pendingAssistantText.toString()
            pendingAssistantText.clear()
            _uiState.update {
                it.copy(
                    chatMessages = it.chatMessages + ChatMessage(text = text, isFromUser = false),
                    pendingAssistantText = ""
                )
            }
        }
    }

    private fun smartJoin(existing: String, newChunk: String): String {
        if (existing.isEmpty()) return newChunk
        if (newChunk.isEmpty()) return existing
        val needsSpace = !newChunk.first().let { it in ",.!?;:'\")" } && existing.last() !in "('\""
        return if (needsSpace) "$existing $newChunk" else "$existing$newChunk"
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            webSocketManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    private fun observeAndCaptureAudio() {
        combine(
            uiState.map { it.connectionState }.distinctUntilChanged(),
            uiState.map { it.inputMode }.distinctUntilChanged()
        ) { connectionState, inputMode ->
            Pair(connectionState, inputMode)
        }.flatMapLatest { (connectionState, inputMode) ->
            val shouldCapture = connectionState == ConnectionState.CONNECTED && inputMode == InputMode.AUDIO
            if (shouldCapture) {
                _uiState.update { it.copy(isListening = true) }
                audioCaptureManager.startCapture()
            } else {
                _uiState.update { it.copy(isListening = false) }
                flow { }
            }
        }.onEach { audioData ->
            webSocketManager.sendAudio(audioData)
        }.launchIn(viewModelScope)
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url) }
    }

    fun connect() {
        val url = _uiState.value.serverUrl
        if (url.isNotBlank()) {
            prefs.edit().putString("server_url", url).apply()
            webSocketManager.connect(url)
        }
    }

    fun disconnect() {
        webSocketManager.disconnect()
    }

    fun updateTextInput(text: String) {
        _uiState.update { it.copy(textInput = text) }
    }

    fun sendTextMessage() {
        val text = _uiState.value.textInput.trim()
        if (text.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    chatMessages = it.chatMessages + ChatMessage(text = text, isFromUser = true),
                    textInput = ""
                )
            }
            webSocketManager.sendText(text)
        }
    }

    fun toggleInputMode() {
        val currentMode = _uiState.value.inputMode
        val newMode = if (currentMode == InputMode.AUDIO) InputMode.TEXT else InputMode.AUDIO
        Log.d("VoiceAssistant", "toggleInputMode: $currentMode -> $newMode")
        _uiState.update { it.copy(inputMode = newMode) }
        Log.d("VoiceAssistant", "State updated, new inputMode: ${_uiState.value.inputMode}")
        webSocketManager.setMode(newMode)
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
