package io.github.ccoodduu.aivoice.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ccoodduu.aivoice.AIVoiceApplication
import io.github.ccoodduu.aivoice.TransportMode
import io.github.ccoodduu.aivoice.network.ConnectionState
import io.github.ccoodduu.aivoice.network.InputMode
import io.github.ccoodduu.aivoice.network.WebSocketEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val textInput: String = "",
    val transportMode: TransportMode = TransportMode.WEBRTC
)

class VoiceAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AIVoiceApplication
    private val webSocketManager = app.webSocketManager
    private val webRTCManager by lazy { app.webRTCManager }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val prefs = application.getSharedPreferences("voice_assistant_prefs", Context.MODE_PRIVATE)

    private var pendingUserText = StringBuilder()
    private var pendingAssistantText = StringBuilder()

    init {
        val savedUrl = prefs.getString("server_url", "") ?: ""
        val savedTransport = prefs.getString("transport_mode", "webrtc") ?: "webrtc"
        val transportMode = if (savedTransport == "websocket") TransportMode.WEBSOCKET else TransportMode.WEBRTC

        _uiState.update { it.copy(serverUrl = savedUrl, transportMode = transportMode) }
        app.setTransportMode(transportMode)

        observeWebSocketEvents()
        observeWebRTCEvents()
        observeConnectionState()
        observeInputMode()

        if (savedUrl.isNotBlank()) {
            connect()
        }
    }

    private fun handleEvent(event: WebSocketEvent) {
        when (event) {
            is WebSocketEvent.Connected -> {
                _uiState.update { it.copy(errorMessage = null) }
            }
            is WebSocketEvent.SessionReady -> { }
            is WebSocketEvent.AudioReceived -> { }
            is WebSocketEvent.TranscriptReceived -> {
                _uiState.update { it.copy(transcript = event.text) }
            }
            is WebSocketEvent.ToolCallReceived -> {
                _uiState.update {
                    it.copy(lastToolCall = "${event.name}: ${event.status}")
                }
            }
            is WebSocketEvent.UserTranscriptReceived -> {
                if (_uiState.value.inputMode == InputMode.AUDIO) {
                    finalizeAssistantMessage()
                    if (event.text.isNotEmpty()) {
                        val newText = smartJoin(pendingUserText.toString(), event.text)
                        pendingUserText.clear()
                        pendingUserText.append(newText)
                        _uiState.update { it.copy(pendingUserText = newText.trim()) }
                    }
                }
            }
            is WebSocketEvent.AssistantTranscriptReceived -> {
                finalizeUserMessage()
                if (event.text.isNotEmpty()) {
                    val newText = smartJoin(pendingAssistantText.toString(), event.text)
                    pendingAssistantText.clear()
                    pendingAssistantText.append(newText)
                    _uiState.update { it.copy(pendingAssistantText = newText.trim()) }
                }
            }
            is WebSocketEvent.TurnComplete -> {
                finalizeAssistantMessage()
            }
            is WebSocketEvent.AssistantTextReceived -> {
                finalizeUserMessage()
                if (event.text.isNotEmpty()) {
                    val newText = smartJoin(pendingAssistantText.toString(), event.text)
                    pendingAssistantText.clear()
                    pendingAssistantText.append(newText)
                    _uiState.update { it.copy(pendingAssistantText = newText.trim()) }
                }
            }
            is WebSocketEvent.ModeChanged -> {
                val mode = if (event.mode == "text") InputMode.TEXT else InputMode.AUDIO
                _uiState.update { it.copy(inputMode = mode) }
                app.setInputMode(mode)
            }
            is WebSocketEvent.Error -> {
                _uiState.update {
                    it.copy(errorMessage = "${event.code}: ${event.message}")
                }
            }
            is WebSocketEvent.Disconnected -> {
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

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            webSocketManager.events.collect { event ->
                if (_uiState.value.transportMode == TransportMode.WEBSOCKET) {
                    handleEvent(event)
                }
            }
        }
    }

    private fun observeWebRTCEvents() {
        viewModelScope.launch {
            webRTCManager.events.collect { event ->
                if (_uiState.value.transportMode == TransportMode.WEBRTC) {
                    handleEvent(event)
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
        return "$existing$newChunk"
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            webSocketManager.connectionState.collect { state ->
                if (_uiState.value.transportMode == TransportMode.WEBSOCKET) {
                    _uiState.update { it.copy(connectionState = state) }
                }
            }
        }
        viewModelScope.launch {
            webRTCManager.connectionState.collect { state ->
                if (_uiState.value.transportMode == TransportMode.WEBRTC) {
                    _uiState.update { it.copy(connectionState = state) }
                }
            }
        }
    }

    private fun observeInputMode() {
        viewModelScope.launch {
            app.connectionState.collect { state ->
                val isListening = state == ConnectionState.CONNECTED &&
                                  app.inputMode.value == InputMode.AUDIO
                _uiState.update { it.copy(isListening = isListening) }
            }
        }
        viewModelScope.launch {
            app.inputMode.collect { mode ->
                val isListening = app.connectionState.value == ConnectionState.CONNECTED &&
                                  mode == InputMode.AUDIO
                _uiState.update { it.copy(isListening = isListening, inputMode = mode) }
            }
        }
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url) }
    }

    fun setTransportMode(mode: TransportMode) {
        _uiState.update { it.copy(transportMode = mode) }
        app.setTransportMode(mode)
        prefs.edit().putString("transport_mode", if (mode == TransportMode.WEBSOCKET) "websocket" else "webrtc").apply()
    }

    fun connect() {
        val url = _uiState.value.serverUrl
        if (url.isNotBlank()) {
            prefs.edit().putString("server_url", url).apply()

            if (_uiState.value.transportMode == TransportMode.WEBRTC) {
                val httpUrl = url.replace("ws://", "http://").replace("wss://", "https://")
                webRTCManager.connect(httpUrl)
            } else {
                webSocketManager.connect(url)
            }
        }
    }

    fun disconnect() {
        if (_uiState.value.transportMode == TransportMode.WEBRTC) {
            webRTCManager.disconnect()
        } else {
            webSocketManager.disconnect()
        }
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
            if (_uiState.value.transportMode == TransportMode.WEBRTC) {
                webRTCManager.sendText(text)
            } else {
                webSocketManager.sendText(text)
            }
        }
    }

    fun toggleInputMode() {
        val currentMode = _uiState.value.inputMode
        val newMode = if (currentMode == InputMode.AUDIO) InputMode.TEXT else InputMode.AUDIO
        Log.d("VoiceAssistant", "toggleInputMode: $currentMode -> $newMode")
        _uiState.update { it.copy(inputMode = newMode) }
        app.setInputMode(newMode)
        if (_uiState.value.transportMode == TransportMode.WEBSOCKET) {
            webSocketManager.setMode(newMode)
        }
    }

}

