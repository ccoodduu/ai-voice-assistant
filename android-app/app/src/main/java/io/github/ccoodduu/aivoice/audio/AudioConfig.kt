package io.github.ccoodduu.aivoice.audio

object AudioConfig {
    const val INPUT_SAMPLE_RATE = 16000
    const val OUTPUT_SAMPLE_RATE = 24000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val CHUNK_DURATION_MS = 100

    val INPUT_CHUNK_SIZE = INPUT_SAMPLE_RATE * CHUNK_DURATION_MS / 1000 * 2 // 3200 bytes
    val OUTPUT_CHUNK_SIZE = OUTPUT_SAMPLE_RATE * CHUNK_DURATION_MS / 1000 * 2 // 4800 bytes
}
