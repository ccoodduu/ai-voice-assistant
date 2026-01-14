package com.example.aivoice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

class AudioPlaybackManager {

    private var audioTrack: AudioTrack? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isPlaying = false

    private val bufferSize = AudioTrack.getMinBufferSize(
        AudioConfig.OUTPUT_SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(AudioConfig.OUTPUT_CHUNK_SIZE * 4)

    fun initialize() {
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConfig.OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isPlaying = true
    }

    suspend fun playAudio(data: ByteArray) {
        val track = audioTrack ?: return
        if (!isPlaying) return

        withContext(Dispatchers.IO) {
            try {
                track.write(data, 0, data.size)
            } catch (e: IllegalStateException) {
                // Track was released
            }
        }
    }

    fun queueAudio(data: ByteArray) {
        audioQueue.offer(data)
    }

    fun clearQueue() {
        audioQueue.clear()
        audioTrack?.flush()
    }

    fun release() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        audioQueue.clear()
    }
}
