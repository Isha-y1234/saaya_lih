package com.project.saaya.data.repository

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Explicit foreground recording only. Caller owns consent, runtime permission and lifecycle stop. */
class NativeWavRecorder {
    @SuppressLint("MissingPermission")
    suspend fun record(target: File, stop: AtomicBoolean) = withContext(Dispatchers.IO) {
        require((target.parentFile?.usableSpace ?: 0) > 2L * WavFormat.MAX_FILE_BYTES)
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "16kHz recording unavailable on this device" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0); out.write(ByteArray(44))
                var count = 0
                try {
                    recorder.startRecording()
                    val buffer = ByteArray(3200)
                    while (!stop.get() && count < WavFormat.MAX_PCM_BYTES) {
                        coroutineContext.ensureActive()
                        val read = recorder.read(buffer, 0, minOf(buffer.size, WavFormat.MAX_PCM_BYTES - count))
                        check(read > 0 && read % 2 == 0) { "Microphone recording failed" }
                        out.write(buffer, 0, read); count += read
                    }
                } finally {
                    if (count >= 2) { out.seek(0); out.write(WavFormat.header(count)) }
                }
            }
        } finally {
            try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            finally { recorder.release() }
        }
    }
}
