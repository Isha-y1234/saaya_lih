package com.project.saaya.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class DerivedAsset(val file: File, val kind: String, val offsetMs: Long)

/** Native APIs only. One frame at a time; <=30s audio, <=640px, <=4 frames, <=20s work. */
class AnalysisPreprocessor {
    fun prepare(original: File, output: File, frames: Int, audio: Boolean): List<DerivedAsset> {
        require(frames in 1..4)
        require(original.length() in 1..(50L * 1024 * 1024))
        output.mkdirs()
        require(output.usableSpace > 12L * 1024 * 1024) { "Insufficient phone storage" }
        val assets = mutableListOf<DerivedAsset>()
        val started = SystemClock.elapsedRealtime()
        fun checkTime() { check(SystemClock.elapsedRealtime() - started < 20_000) { "Phone preprocessing time limit reached" } }
        if (original.extension == "jpg") {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(original.path, bounds)
            require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1280) inSampleSize *= 2
            }
            val bitmap = requireNotNull(BitmapFactory.decodeFile(original.path, options))
            saveFrame(bitmap, File(output, "frame-0.jpg"))
            assets += DerivedAsset(File(output, "frame-0.jpg"), "frame", 0)
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(original.path)
                val duration = requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                require(duration in 1..31_000) { "Recording exceeds analysis duration limit" }
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                // API 24-26 cannot request a scaled decode; reject oversized sources before allocating.
                require(width in 1..4096 && height in 1..4096 && (Build.VERSION.SDK_INT >= 27 || width.toLong()*height <= 1280*720))
                repeat(frames) { index ->
                    checkTime()
                    val offset = ((duration - 1).coerceAtMost(29999) * index / frames)
                    val bitmap = if (Build.VERSION.SDK_INT >= 27)
                        retriever.getScaledFrameAtTime(offset * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 640, 640)
                    else retriever.getFrameAtTime(offset * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        val file = File(output, "frame-$offset.jpg")
                        saveFrame(bitmap, file); assets += DerivedAsset(file, "frame", offset)
                    }
                }
            } finally { retriever.release() }
            if (audio) {
                try {
                    val wav = File(output, "audio-0.wav")
                    val audioOffsetMs = decodeAudio(original, wav, ::checkTime)
                    assets += DerivedAsset(wav, "audio", audioOffsetMs)
                } catch (_: Exception) {
                    File(output, "audio-0.wav").delete()
                    File(output, "audio-unavailable.txt").writeText("Audio missing or native decoding unavailable; visual analysis only.")
                }
            }
        }
        check(assets.isNotEmpty()) { "Phone could not prepare analysis assets" }
        return assets
    }

    private fun saveFrame(bitmap: Bitmap, file: File) {
        try {
            val scale = minOf(1.0, 640.0 / maxOf(bitmap.width, bitmap.height))
            val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width*scale).roundToInt().coerceAtLeast(1),
                (bitmap.height*scale).roundToInt().coerceAtLeast(1), true)
            try { file.outputStream().use { check(resized.compress(Bitmap.CompressFormat.JPEG, 75, it)) } }
            finally { if (resized !== bitmap) resized.recycle() }
            check(file.length() in 1..1_000_000)
        } finally { bitmap.recycle() }
    }

    private fun decodeAudio(source: File, target: File, checkTime: () -> Unit): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var firstPresentationUs: Long? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var inputDone = false
            var outputDone = false
            var sourceSamples = 0L
            var outputSamples = 0L
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0); out.write(ByteArray(44))
                val info = MediaCodec.BufferInfo()
                while (!outputDone && outputSamples < 480000) {
                    checkTime()
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = requireNotNull(decoder.getInputBuffer(index))
                            val count = extractor.readSampleData(buffer, 0)
                            if (count < 0 || extractor.sampleTime >= 30_000_000) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0); extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val decoded = decoder.outputFormat
                        rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(rate in 8000..96000 && channels in 1..2)
                        require(!decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                            decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                    } else if (index >= 0) {
                        if (info.size > 0 && firstPresentationUs == null) {
                            require(info.presentationTimeUs in 0 until 30_000_000L) { "Audio timestamp unavailable" }
                            firstPresentationUs = info.presentationTimeUs
                        }
                        val buffer = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        // Bounded nearest-sample downmix/resampling to mono 16kHz PCM16.
                        while (buffer.remaining() >= channels * 2 && outputSamples < 480000) {
                            var sum = 0
                            repeat(channels) { sum += buffer.short.toInt() }
                            sourceSamples++
                            while (outputSamples * rate < sourceSamples * 16000L && outputSamples < 480000) {
                                val sample = sum / channels
                                out.write(sample and 255); out.write((sample shr 8) and 255); outputSamples++
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
                require(outputSamples > 0)
                val bytes = (outputSamples * 2).toInt()
                out.seek(0); out.write(WavFormat.header(bytes))
            }
            require(!WavFormat.validate(target.readBytes()).silent) { "Audio is silent" }
        } finally { try { codec?.stop() } finally { codec?.release(); extractor.release() } }
        return requireNotNull(firstPresentationUs) / 1000
    }
}
