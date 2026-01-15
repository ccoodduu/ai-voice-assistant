package io.github.ccoodduu.aivoice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class AudioCaptureManager {

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var isRecording = false

    private val bufferSize = AudioRecord.getMinBufferSize(
        AudioConfig.INPUT_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(AudioConfig.INPUT_CHUNK_SIZE * 2)

    @SuppressLint("MissingPermission")
    fun startCapture(): Flow<ByteArray> = flow {
        // Stop any existing capture first
        stopCapture()

        synchronized(this@AudioCaptureManager) {
            if (isRecording) return@flow

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioConfig.INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                throw IllegalStateException("AudioRecord failed to initialize")
            }

            val sessionId = audioRecord!!.audioSessionId

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
                Log.d("AudioCapture", "AEC enabled: ${echoCanceler?.enabled}")
            } else {
                Log.w("AudioCapture", "AEC not available on this device")
            }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.d("AudioCapture", "Noise suppressor enabled: ${noiseSuppressor?.enabled}")
            }

            isRecording = true
            audioRecord?.startRecording()
        }

        val buffer = ByteArray(AudioConfig.INPUT_CHUNK_SIZE)

        try {
            while (coroutineContext.isActive && isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    emit(buffer.copyOf(bytesRead))
                }
            }
        } finally {
            stopCapture()
        }
    }.flowOn(Dispatchers.IO)

    @Synchronized
    fun stopCapture() {
        if (isRecording) {
            isRecording = false
            try {
                audioRecord?.stop()
            } catch (e: Exception) {
                Log.e("AudioCapture", "Error stopping AudioRecord", e)
            }
            audioRecord?.release()
            audioRecord = null
            echoCanceler?.release()
            echoCanceler = null
            noiseSuppressor?.release()
            noiseSuppressor = null
        }
    }
}
