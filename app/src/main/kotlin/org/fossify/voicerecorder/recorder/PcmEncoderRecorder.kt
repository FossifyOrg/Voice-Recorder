package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.EXTENSION_OGG
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records m4a (AAC) or ogg (Opus) by reading PCM from AudioRecord, so that [GainProcessor] can be applied
 * before encoding with MediaCodec and muxing with MediaMuxer. Replaces MediaRecorder, which has no gain control.
 */
class PcmEncoderRecorder(val context: Context, audioSourceOverride: Int? = null) : Recorder {
    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_EOS_DRAIN_ATTEMPTS = 100
        private const val MICROS_PER_SECOND = 1_000_000L
    }

    private val sampleRate = context.config.samplingRate
    private val isOpus = context.config.extension == EXTENSION_OGG
    private val gainProcessor = GainProcessor(sampleRate)
    private val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    // create the codec first, so a missing encoder fails before the microphone is opened
    private val codec = createCodec()

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        audioSourceOverride ?: context.config.microphoneMode,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBufferSize * 2
    ).also {
        if (it.state != AudioRecord.STATE_INITIALIZED) {
            it.release()
            codec.release()
            throw IllegalStateException("AudioRecord could not be initialized")
        }
    }

    private var outputPath: String? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var isMuxerStarted = false
    private var framesQueued = 0L
    private var worker: Thread? = null
    private var isReleased = false
    private val isPaused = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)
    private val bufferInfo = MediaCodec.BufferInfo()

    override fun setOutputFile(path: String) {
        outputPath = path
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        fileDescriptor?.close()
        fileDescriptor = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
    }

    override fun setPreferredDevice(device: AudioDeviceInfo?) {
        audioRecord.setPreferredDevice(device)
    }

    override fun setGainDb(gainDb: Float) {
        gainProcessor.setGainDb(gainDb)
    }

    // OGG output is only offered on Android 10+, see SettingsActivity
    @SuppressLint("InlinedApi")
    override fun prepare() {
        val format = if (isOpus) {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
        } else {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }

        val descriptor = fileDescriptor
        muxer = if (descriptor != null) {
            MediaMuxer(descriptor.fileDescriptor, format)
        } else {
            MediaMuxer(checkNotNull(outputPath) { "No output file set" }, format)
        }
    }

    override fun start() {
        codec.start()
        audioRecord.startRecording()
        worker = thread(name = "PcmEncoderRecorder") {
            try {
                recordLoop()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                context.showErrorToast(e)
            }
        }
    }

    /**
     * Stops recording, finalizes the file and frees every resource.
     * Throws IllegalStateException if nothing was encoded, like MediaRecorder does for too short recordings.
     */
    override fun stop() {
        isPaused.set(true)
        isStopped.set(true)
        worker?.join()
        worker = null
        val wroteData = isMuxerStarted
        release()
        check(wroteData) { "Recording too short" }
    }

    override fun pause() {
        isPaused.set(true)
    }

    override fun resume() {
        isPaused.set(false)
    }

    override fun release() {
        if (isReleased) return
        isReleased = true
        isStopped.set(true)
        worker?.join()

        runCatching { audioRecord.stop() }
        audioRecord.release()
        runCatching { codec.stop() }
        codec.release()
        if (isMuxerStarted) {
            runCatching { muxer?.stop() }
        }
        muxer?.release()
        muxer = null
        fileDescriptor?.close()
        fileDescriptor = null
    }

    override fun getMaxAmplitude() = gainProcessor.getAndResetPeak()

    private fun createCodec(): MediaCodec {
        val mimeType = if (isOpus) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mimeType, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, context.config.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize * 2)
            if (!isOpus) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }

        val codec = MediaCodec.createEncoderByType(mimeType)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: IllegalStateException) {
            codec.release()
            throw e
        } catch (e: IllegalArgumentException) {
            codec.release()
            throw e
        }
        return codec
    }

    private fun recordLoop() {
        val pcm = ShortArray(minBufferSize / 2)
        while (!isStopped.get()) {
            val count = audioRecord.read(pcm, 0, pcm.size)
            if (count > 0) {
                // keep processing while paused, so the level meter stays live
                gainProcessor.process(pcm, count)
                if (!isPaused.get()) {
                    queuePcm(pcm, count)
                }
            }
            drainEncoder(endOfStream = false)
        }

        signalEndOfStream()
        drainEncoder(endOfStream = true)
    }

    private fun queuePcm(pcm: ShortArray, count: Int) {
        var offset = 0
        while (offset < count && !isStopped.get()) {
            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index < 0) {
                drainEncoder(endOfStream = false)
                continue
            }

            val buffer = codec.getInputBuffer(index)!!
            buffer.clear()
            val samples = minOf(count - offset, buffer.remaining() / Short.SIZE_BYTES)
            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, samples)
            codec.queueInputBuffer(index, 0, samples * Short.SIZE_BYTES, presentationTimeUs(), 0)
            offset += samples
            // timestamps come from the sample count, so pausing leaves no gap in the file
            framesQueued += samples
        }
    }

    private fun signalEndOfStream() {
        repeat(MAX_EOS_DRAIN_ATTEMPTS) {
            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drainEncoder(endOfStream = false)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        var attempts = 0
        while (attempts < MAX_EOS_DRAIN_ATTEMPTS) {
            val index = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) CODEC_TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    attempts++
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val muxer = checkNotNull(muxer) { "prepare() was not called" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    isMuxerStarted = true
                }

                index >= 0 -> {
                    writeEncodedData(index)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun writeEncodedData(index: Int) {
        val buffer = codec.getOutputBuffer(index)!!
        val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (!isConfig && bufferInfo.size > 0 && isMuxerStarted) {
            buffer.position(bufferInfo.offset)
            buffer.limit(bufferInfo.offset + bufferInfo.size)
            muxer!!.writeSampleData(trackIndex, buffer, bufferInfo)
        }
        codec.releaseOutputBuffer(index, false)
    }

    private fun presentationTimeUs() = framesQueued * MICROS_PER_SECOND / sampleRate
}
