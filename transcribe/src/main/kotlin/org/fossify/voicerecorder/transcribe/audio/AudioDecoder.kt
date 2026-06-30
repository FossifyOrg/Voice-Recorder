package org.fossify.voicerecorder.transcribe.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * A 30 s chunk of mono 16 kHz Float32 PCM in [-1, 1].
 * The final chunk may be shorter (samples.size < CHUNK_SAMPLES).
 */
data class PcmChunk(
    val samples: FloatArray,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Decodes any Android-supported audio (M4A/AAC, MP3, OGG/Opus, WAV) at [uri] into
 * 16 kHz mono Float32 PCM chunks suitable for sherpa-onnx Whisper inference.
 *
 * Streaming: holds at most one chunk worth of samples in memory at a time, so a
 * multi-hour recording will not OOM.
 */
class AudioDecoder(
    private val context: Context,
    private val uri: Uri,
) {
    /**
     * Run the decode loop on the calling thread. Caller is responsible for offloading
     * to a background dispatcher.
     *
     * @param isCancelled polled between codec dequeues; set to abort cleanly.
     * @param onProgress fraction in [0, 1] based on `KEY_DURATION` and the codec PTS.
     * @param onChunk receives each chunk; return false to abort.
     * @return total decoded duration in milliseconds (may differ slightly from container metadata).
     * @throws IllegalStateException if the URI has no audio track.
     */
    fun decodeChunks(
        isCancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Float) -> Unit = {},
        onChunk: (PcmChunk) -> Boolean,
    ): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track in $uri")

        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            inputFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L
        extractor.selectTrack(trackIndex)

        var sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var sourceChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        var resampler = LinearResampler(sourceRate, TARGET_SAMPLE_RATE)
        val outBuffer = FloatArray(CHUNK_SAMPLES)
        var outIndex = 0
        var totalOutSamples = 0L
        var chunkStartMs = 0L
        var aborted = false

        fun flushChunk() {
            if (outIndex == 0) return
            val endSamples = totalOutSamples + outIndex
            val chunk = PcmChunk(
                samples = outBuffer.copyOf(outIndex),
                startMs = chunkStartMs,
                endMs = endSamples * 1000L / TARGET_SAMPLE_RATE,
            )
            totalOutSamples = endSamples
            outIndex = 0
            chunkStartMs = totalOutSamples * 1000L / TARGET_SAMPLE_RATE
            if (!onChunk(chunk)) aborted = true
        }

        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        try {
            while (!sawOutputEos && !aborted) {
                if (isCancelled.get()) {
                    aborted = true
                    break
                }

                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sourceRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        sourceChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        resampler = LinearResampler(sourceRate, TARGET_SAMPLE_RATE)
                    }

                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            buf.position(info.offset).limit(info.offset + info.size)
                            val mono = readMono(buf, sourceChannels, pcmEncoding)
                            resampler.process(mono) { sample ->
                                outBuffer[outIndex++] = sample
                                if (outIndex == CHUNK_SAMPLES) flushChunk()
                            }
                            if (durationUs > 0 && info.presentationTimeUs > 0) {
                                onProgress(
                                    min(1f, info.presentationTimeUs.toFloat() / durationUs)
                                )
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }
                    // INFO_TRY_AGAIN_LATER and INFO_OUTPUT_BUFFERS_CHANGED: just loop.
                    else -> Unit
                }
            }

            if (!aborted) flushChunk()
        } finally {
            try {
                codec.stop()
            } catch (_: Throwable) {
            }
            codec.release()
            extractor.release()
        }

        if (!aborted) onProgress(1f)
        return totalOutSamples * 1000L / TARGET_SAMPLE_RATE
    }

    private fun readMono(buf: ByteBuffer, channels: Int, encoding: Int): FloatArray {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                val frames = fb.remaining() / channels
                val out = FloatArray(frames)
                if (channels == 1) {
                    fb.get(out)
                } else {
                    for (f in 0 until frames) {
                        var s = 0f
                        for (c in 0 until channels) s += fb.get()
                        out[f] = s / channels
                    }
                }
                out
            }

            else -> {
                val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = sb.remaining() / channels
                val out = FloatArray(frames)
                if (channels == 1) {
                    for (f in 0 until frames) out[f] = sb.get().toFloat() / 32768f
                } else {
                    for (f in 0 until frames) {
                        var s = 0
                        for (c in 0 until channels) s += sb.get()
                        out[f] = s.toFloat() / (channels * 32768f)
                    }
                }
                out
            }
        }
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000

        /** Whisper's input window is exactly 30 s; one chunk = one inference. */
        const val CHUNK_SECONDS = 30
        const val CHUNK_SAMPLES = TARGET_SAMPLE_RATE * CHUNK_SECONDS

        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}

/**
 * Linear-interpolation resampler. Stateful across multiple [process] calls so it can
 * be driven by a streaming decoder. Mono in, mono out.
 *
 * Quality is sufficient for ASR; not for music. Cheap O(srcSamples).
 */
internal class LinearResampler(srcRate: Int, dstRate: Int) {
    private val step: Double = srcRate.toDouble() / dstRate.toDouble()
    private var prev: Float = 0f
    private var srcIndex: Long = 0L
    private var dstIndex: Long = 0L
    private var hasPrev: Boolean = false

    fun process(src: FloatArray, emit: (Float) -> Unit) {
        for (i in src.indices) {
            val cur = src[i]
            if (!hasPrev) {
                prev = cur
                hasPrev = true
                continue
            }
            // Emit dst samples whose source position falls in [srcIndex, srcIndex + 1)
            // (i.e. between `prev` and `cur`).
            while (dstIndex * step < srcIndex + 1) {
                val frac = (dstIndex * step - srcIndex).toFloat()
                emit(prev * (1f - frac) + cur * frac)
                dstIndex++
            }
            prev = cur
            srcIndex++
        }
    }
}
