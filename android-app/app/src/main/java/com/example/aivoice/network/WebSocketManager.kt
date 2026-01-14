package com.example.aivoice.network

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class WebSocketEvent {
    data object Connected : WebSocketEvent()
    data object Disconnected : WebSocketEvent()
    data class SessionReady(val sessionId: String) : WebSocketEvent()
    data class AudioReceived(val data: ByteArray) : WebSocketEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioReceived) return false
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int = data.contentHashCode()
    }
    data class TranscriptReceived(val text: String, val isFinal: Boolean) : WebSocketEvent()
    data class ToolCallReceived(val name: String, val status: String) : WebSocketEvent()
    data class Error(val code: String, val message: String) : WebSocketEvent()
}

class WebSocketManager {

    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
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

    fun connect(serverUrl: String, clientId: String = "android-client") {
        _connectionState.tryEmit(ConnectionState.CONNECTING)

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _events.tryEmit(WebSocketEvent.Connected)
                sendHello(clientId)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                _events.tryEmit(WebSocketEvent.AudioReceived(bytes.toByteArray()))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseJsonMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.tryEmit(ConnectionState.DISCONNECTED)
                _events.tryEmit(WebSocketEvent.Disconnected)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketManager", "Connection failed", t)
                _connectionState.tryEmit(ConnectionState.ERROR)
                _events.tryEmit(WebSocketEvent.Error("connection_failed", t.message ?: "Unknown error"))
            }
        })
    }

    private fun parseJsonMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.getString("type")) {
                "session_ready" -> {
                    _connectionState.tryEmit(ConnectionState.CONNECTED)
                    _events.tryEmit(WebSocketEvent.SessionReady(json.getString("session_id")))
                }
                "transcript" -> {
                    _events.tryEmit(
                        WebSocketEvent.TranscriptReceived(
                            text = json.getString("text"),
                            isFinal = json.optBoolean("is_final", true)
                        )
                    )
                }
                "tool_call" -> {
                    _events.tryEmit(
                        WebSocketEvent.ToolCallReceived(
                            name = json.getString("name"),
                            status = json.getString("status")
                        )
                    )
                }
                "error" -> {
                    _events.tryEmit(
                        WebSocketEvent.Error(
                            code = json.getString("code"),
                            message = json.getString("message")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Error parsing JSON", e)
        }
    }

    private fun sendHello(clientId: String) {
        val capabilities = JSONArray().apply {
            put("audio_input")
            put("audio_output")
        }
        val json = JSONObject().apply {
            put("type", "hello")
            put("client_id", clientId)
            put("capabilities", capabilities)
        }
        webSocket?.send(json.toString())
    }

    fun sendAudio(data: ByteArray) {
        webSocket?.send(data.toByteString())
    }

    fun sendControl(action: String) {
        val json = JSONObject().apply {
            put("type", "control")
            put("action", action)
        }
        webSocket?.send(json.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}
