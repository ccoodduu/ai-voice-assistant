package com.example.aivoice.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aivoice.audio.AudioCaptureManager
import com.example.aivoice.audio.AudioPlaybackManager
import com.example.aivoice.network.ConnectionState
import com.example.aivoice.network.WebSocketEvent
import com.example.aivoice.network.WebSocketManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class VoiceAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val webSocketManager = WebSocketManager()
    private val audioCaptureManager = AudioCaptureManager()
    private val audioPlaybackManager = AudioPlaybackManager()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var audioCaptureJob: Job? = null

    init {
        observeWebSocketEvents()
        observeConnectionState()
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
                        startAudioCapture()
                    }
                    is WebSocketEvent.AudioReceived -> {
                        viewModelScope.launch {
                            audioPlaybackManager.playAudio(event.data)
                        }
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
                        stopAudioCapture()
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

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url) }
    }

    fun connect() {
        val url = _uiState.value.serverUrl
        webSocketManager.connect(url)
    }

    fun disconnect() {
        stopAudioCapture()
        audioPlaybackManager.release()
        webSocketManager.disconnect()
    }

    private fun startAudioCapture() {
        audioCaptureJob = viewModelScope.launch {
            _uiState.update { it.copy(isListening = true) }
            audioCaptureManager.startCapture().collect { audioData ->
                webSocketManager.sendAudio(audioData)
            }
        }
    }

    private fun stopAudioCapture() {
        audioCaptureJob?.cancel()
        audioCaptureJob = null
        audioCaptureManager.stopCapture()
        _uiState.update { it.copy(isListening = false) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
