package io.github.ccoodduu.aivoice

import android.app.Activity
import android.app.Application
import io.github.ccoodduu.aivoice.audio.AudioCaptureManager
import io.github.ccoodduu.aivoice.audio.AudioPlaybackManager
import io.github.ccoodduu.aivoice.network.ConnectionState
import io.github.ccoodduu.aivoice.network.InputMode
import io.github.ccoodduu.aivoice.network.WebRTCManager
import io.github.ccoodduu.aivoice.network.WebSocketEvent
import io.github.ccoodduu.aivoice.network.WebSocketManager
import io.github.ccoodduu.aivoice.service.VoiceAssistantService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

enum class TransportMode { WEBSOCKET, WEBRTC }

class AIVoiceApplication : Application() {

    val webSocketManager = WebSocketManager()
    lateinit var webRTCManager: WebRTCManager
        private set
    val audioCaptureManager = AudioCaptureManager()
    val audioPlaybackManager = AudioPlaybackManager()

    private val _transportMode = MutableStateFlow(TransportMode.WEBRTC)
    val transportMode: StateFlow<TransportMode> = _transportMode.asStateFlow()

    val currentEvents: SharedFlow<WebSocketEvent>
        get() = if (_transportMode.value == TransportMode.WEBRTC) webRTCManager.events else webSocketManager.events

    val currentConnectionState: SharedFlow<ConnectionState>
        get() = if (_transportMode.value == TransportMode.WEBRTC) webRTCManager.connectionState else webSocketManager.connectionState

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioCaptureJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _inputMode = MutableStateFlow(InputMode.AUDIO)
    val inputMode: StateFlow<InputMode> = _inputMode.asStateFlow()

    private var currentActivity: WeakReference<Activity>? = null

    fun setCurrentActivity(activity: Activity?) {
        currentActivity = activity?.let { WeakReference(it) }
    }

    fun hasActiveActivity(): Boolean {
        return currentActivity?.get() != null
    }

    fun finishCurrentActivity() {
        currentActivity?.get()?.finish()
        currentActivity = null
    }

    fun setInputMode(mode: InputMode) {
        _inputMode.value = mode
        updateAudioCapture()
    }

    fun setTransportMode(mode: TransportMode) {
        _transportMode.value = mode
    }

    private fun updateAudioCapture() {
        val shouldCapture = _connectionState.value == ConnectionState.CONNECTED &&
                           _inputMode.value == InputMode.AUDIO &&
                           _transportMode.value == TransportMode.WEBSOCKET

        if (shouldCapture && audioCaptureJob == null) {
            audioCaptureJob = applicationScope.launch(Dispatchers.IO) {
                audioCaptureManager.startCapture()
                    .onEach { audioData ->
                        webSocketManager.sendAudio(audioData)
                    }
                    .launchIn(this)
            }
        } else if (!shouldCapture && audioCaptureJob != null) {
            audioCaptureJob?.cancel()
            audioCaptureJob = null
            audioCaptureManager.stopCapture()
        }
    }

    companion object {
        private lateinit var instance: AIVoiceApplication

        fun getInstance(): AIVoiceApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        webRTCManager = WebRTCManager(this)

        // Observe WebSocket connection state changes
        applicationScope.launch {
            webSocketManager.connectionState.collect { state ->
                if (_transportMode.value == TransportMode.WEBSOCKET) {
                    _connectionState.value = state
                    updateAudioCapture()
                    handleConnectionStateChange(state)
                }
            }
        }

        // Observe WebRTC connection state changes
        applicationScope.launch {
            webRTCManager.connectionState.collect { state ->
                if (_transportMode.value == TransportMode.WEBRTC) {
                    _connectionState.value = state
                    updateAudioCapture()
                    handleConnectionStateChange(state)
                }
            }
        }

        // Handle WebSocket events
        applicationScope.launch {
            webSocketManager.events.collect { event ->
                if (_transportMode.value == TransportMode.WEBSOCKET) {
                    handleEvent(event)
                }
            }
        }

        // Handle WebRTC events
        applicationScope.launch {
            webRTCManager.events.collect { event ->
                if (_transportMode.value == TransportMode.WEBRTC) {
                    handleEvent(event)
                }
            }
        }
    }

    private fun handleConnectionStateChange(state: ConnectionState) {
        if (state == ConnectionState.CONNECTED) {
            VoiceAssistantService.start(this@AIVoiceApplication)
        } else if (state == ConnectionState.DISCONNECTED) {
            VoiceAssistantService.stop(this@AIVoiceApplication)
        }
    }

    private fun handleEvent(event: WebSocketEvent) {
        when (event) {
            is WebSocketEvent.SessionReady -> {
                if (_transportMode.value == TransportMode.WEBSOCKET) {
                    audioPlaybackManager.initialize()
                }
            }
            is WebSocketEvent.AudioReceived -> {
                audioPlaybackManager.playAudio(event.data)
            }
            is WebSocketEvent.Disconnected -> {
                audioPlaybackManager.release()
                audioCaptureJob?.cancel()
                audioCaptureJob = null
            }
            else -> { }
        }
    }
}
