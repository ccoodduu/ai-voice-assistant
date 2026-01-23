package io.github.ccoodduu.aivoice.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class WebRTCManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private var sessionId: String? = null

    private var serverUrl: String = ""
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isConnected = false

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<WebSocketEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    fun connect(signalingUrl: String) {
        serverUrl = signalingUrl.removeSuffix("/")
        _connectionState.tryEmit(ConnectionState.CONNECTING)

        scope.launch {
            try {
                initializePeerConnectionFactory()
                createPeerConnection()
                addLocalAudioTrack()
                createDataChannel()
                createAndSendOffer()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.tryEmit(ConnectionState.ERROR)
                _events.tryEmit(WebSocketEvent.Error("connection_failed", e.message ?: "Unknown error"))
            }
        }
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val eglBase = EglBase.create()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory initialized")
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        isConnected = true
                        _events.tryEmit(WebSocketEvent.Connected)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        isConnected = false
                        _connectionState.tryEmit(ConnectionState.DISCONNECTED)
                        _events.tryEmit(WebSocketEvent.Disconnected)
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "Local ICE candidate: ${it.sdp}")
                    scope.launch { sendIceCandidate(it) }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "Remote stream added: ${stream?.id}")
            }

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel?) {
                Log.d(TAG, "Data channel received: ${channel?.label()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                Log.d(TAG, "Remote track added")
            }
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
        Log.d(TAG, "PeerConnection created")
    }

    private fun addLocalAudioTrack() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
        }

        val audioSource: AudioSource? = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", audioSource)

        localAudioTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("stream0"))
            Log.d(TAG, "Local audio track added with AEC/NS/AGC enabled")
        }
    }

    private fun createDataChannel() {
        val config = DataChannel.Init().apply {
            ordered = true
        }

        dataChannel = peerConnection?.createDataChannel("control", config)
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                Log.d(TAG, "Data channel state: ${dataChannel?.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer?.let {
                    val data = ByteArray(it.data.remaining())
                    it.data.get(data)
                    val message = String(data, Charsets.UTF_8)
                    handleDataChannelMessage(message)
                }
            }
        })

        Log.d(TAG, "Data channel created")
    }

    private fun handleDataChannelMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.getString("type")) {
                "session_ready" -> {
                    sessionId = json.getString("session_id")
                    _connectionState.tryEmit(ConnectionState.CONNECTED)
                    _events.tryEmit(WebSocketEvent.SessionReady(
                        sessionId = sessionId ?: "",
                        mode = json.optString("mode", "audio")
                    ))
                }
                "user_transcript" -> {
                    _events.tryEmit(WebSocketEvent.UserTranscriptReceived(json.getString("text")))
                }
                "assistant_transcript" -> {
                    _events.tryEmit(WebSocketEvent.AssistantTranscriptReceived(json.getString("text")))
                }
                "assistant_text" -> {
                    _events.tryEmit(WebSocketEvent.AssistantTextReceived(json.getString("text")))
                }
                "turn_complete" -> {
                    _events.tryEmit(WebSocketEvent.TurnComplete)
                }
                "tool_call" -> {
                    _events.tryEmit(WebSocketEvent.ToolCallReceived(
                        name = json.getString("name"),
                        status = json.getString("status")
                    ))
                }
                "error" -> {
                    _events.tryEmit(WebSocketEvent.Error(
                        code = json.getString("code"),
                        message = json.getString("message")
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing data channel message", e)
        }
    }

    private suspend fun createAndSendOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { offer ->
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local description set")
                            scope.launch { sendOfferToServer(offer) }
                        }
                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Set local description failed: $error")
                        }
                    }, offer)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
                _connectionState.tryEmit(ConnectionState.ERROR)
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private suspend fun sendOfferToServer(offer: SessionDescription) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("sdp", offer.description)
                put("type", "offer")
            }

            val request = Request.Builder()
                .url("$serverUrl/rtc/offer")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val responseJson = JSONObject(responseBody)
                sessionId = responseJson.optString("session_id")
                val answerSdp = responseJson.getString("sdp")
                val answerType = responseJson.getString("type")

                Log.d(TAG, "Received answer from server, session: $sessionId")

                val answer = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(answerType),
                    answerSdp
                )

                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "Remote description set")
                        processPendingIceCandidates()
                    }
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Set remote description failed: $error")
                    }
                }, answer)
            } else {
                Log.e(TAG, "Server returned error: ${response.code}")
                _connectionState.tryEmit(ConnectionState.ERROR)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending offer", e)
            _connectionState.tryEmit(ConnectionState.ERROR)
        }
    }

    private suspend fun sendIceCandidate(candidate: IceCandidate) = withContext(Dispatchers.IO) {
        if (sessionId == null) {
            pendingIceCandidates.add(candidate)
            return@withContext
        }

        try {
            val json = JSONObject().apply {
                put("session_id", sessionId)
                put("candidate", JSONObject().apply {
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                })
            }

            val request = Request.Builder()
                .url("$serverUrl/rtc/ice")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ICE candidate", e)
        }
    }

    private fun processPendingIceCandidates() {
        pendingIceCandidates.forEach { candidate ->
            scope.launch { sendIceCandidate(candidate) }
        }
        pendingIceCandidates.clear()
    }

    fun sendText(text: String) {
        val json = JSONObject().apply {
            put("type", "text_input")
            put("text", text)
        }
        sendDataChannelMessage(json.toString())
    }

    fun sendControl(action: String) {
        val json = JSONObject().apply {
            put("type", "control")
            put("action", action)
        }
        sendDataChannelMessage(json.toString())
    }

    private fun sendDataChannelMessage(message: String) {
        dataChannel?.let { channel ->
            if (channel.state() == DataChannel.State.OPEN) {
                val buffer = ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8))
                channel.send(DataChannel.Buffer(buffer, false))
            }
        }
    }

    fun disconnect() {
        sendControl("end_session")

        localAudioTrack?.dispose()
        localAudioTrack = null

        dataChannel?.close()
        dataChannel = null

        peerConnection?.close()
        peerConnection = null

        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        sessionId = null
        isConnected = false
        pendingIceCandidates.clear()

        _connectionState.tryEmit(ConnectionState.DISCONNECTED)
        _events.tryEmit(WebSocketEvent.Disconnected)

        Log.d(TAG, "WebRTC disconnected")
    }

    companion object {
        private const val TAG = "WebRTCManager"
    }
}
