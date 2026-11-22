package com.project.saaya.data.repository

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical RIFF PCM16 mono 16kHz; shared by native recorder, extractor and validation. */
object WavFormat {
    const val MAX_PCM_BYTES = 960000
    const val MAX_FILE_BYTES = MAX_PCM_BYTES + 44
    fun header(pcmBytes: Int): ByteArray {
        require(pcmBytes in 2..MAX_PCM_BYTES && pcmBytes % 2 == 0)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(pcmBytes + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmBytes)
        }.array()
    }
    data class Info(val durationMs: Long, val silent: Boolean)
    fun validate(bytes: ByteArray): Info {
        require(bytes.size in 46..MAX_FILE_BYTES && (bytes.size - 44) % 2 == 0) { "Empty or oversized WAV" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun string(start: Int, end: Int) = String(bytes.copyOfRange(start, end), Charsets.US_ASCII)
        require(string(0,4) == "RIFF" && b.getInt(4) == bytes.size - 8 && string(8,12) == "WAVE" &&
            string(12,16) == "fmt " && b.getInt(16) == 16 && b.getShort(20).toInt() == 1 &&
            b.getShort(22).toInt() == 1 && b.getInt(24) == 16000 && b.getInt(28) == 32000 &&
            b.getShort(32).toInt() == 2 && b.getShort(34).toInt() == 16 && string(36,40) == "data" &&
            b.getInt(40) == bytes.size - 44) { "Expected PCM16 mono 16kHz RIFF WAV" }
        var silent = true
        for (index in 44 until bytes.size step 2) if (kotlin.math.abs(b.getShort(index).toInt()) > 32) { silent = false; break }
        return Info((bytes.size - 44) * 1000L / 32000, silent)
    }
}
