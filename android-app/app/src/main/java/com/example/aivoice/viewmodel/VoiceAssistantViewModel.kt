package com.example.aivoice.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aivoice.audio.AudioCaptureManager
import com.example.aivoice.audio.AudioPlaybackManager
import com.example.aivoice.network.ConnectionState
import com.example.aivoice.network.WebSocketEvent
import com.example.aivoice.network.WebSocketManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isListening: Boolean = false,
    val serverUrl: String = "ws://100.123.253.113:8765",
    val transcript: String = "",
    val lastToolCall: String = "",
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val webSocketManager = WebSocketManager()
    private val audioCaptureManager = AudioCaptureManager()
    private val audioPlaybackManager = AudioPlaybackManager()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        observeWebSocketEvents()
        observeConnectionState()
        observeAndCaptureAudio()
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
                    is WebSocketEvent.Error -> {
                        _uiState.update {
                            it.copy(errorMessage = "${event.code}: ${event.message}")
                        }
                    }
                    is WebSocketEvent.Disconnected -> {
                        audioPlaybackManager.release()
                    }
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            webSocketManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    private fun observeAndCaptureAudio() {
        uiState.map { it.connectionState }
            .distinctUntilChanged()
            .flatMapLatest { state ->
                if (state == ConnectionState.CONNECTED) {
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
        webSocketManager.connect(url)
    }

    fun disconnect() {
        webSocketManager.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
