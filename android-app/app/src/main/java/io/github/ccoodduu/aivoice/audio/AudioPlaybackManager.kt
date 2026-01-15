package io.github.ccoodduu.aivoice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class AudioPlaybackManager {

    private var audioTrack: AudioTrack? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var isPlaying = false
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()

    private val bufferSize = AudioTrack.getMinBufferSize(
        AudioConfig.OUTPUT_SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(AudioConfig.OUTPUT_CHUNK_SIZE * 4)

    fun initialize() {
        synchronized(lock) {
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
    }

    fun playAudio(data: ByteArray) {
        if (!isPlaying) return
        executor.execute {
            synchronized(lock) {
                val track = audioTrack ?: return@execute
                if (!isPlaying) return@execute
                try {
                    track.write(data, 0, data.size)
                } catch (e: Exception) {
                    // Track was released or other error
                }
            }
        }
    }

    fun queueAudio(data: ByteArray) {
        audioQueue.offer(data)
    }

    fun clearQueue() {
        audioQueue.clear()
        synchronized(lock) {
            audioTrack?.flush()
        }
    }

    fun release() {
        synchronized(lock) {
            isPlaying = false
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                // Ignore
            }
            audioTrack = null
        }
        audioQueue.clear()
    }
}
