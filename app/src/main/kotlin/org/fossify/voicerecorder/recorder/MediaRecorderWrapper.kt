package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.store.RecordingFormat

class MediaRecorderWrapper(val context: Context) : Recorder {

    @Suppress("DEPRECATION")
    private var recorder = MediaRecorder().apply {
        setAudioSource(context.config.microphoneMode)

        when (context.config.recordingFormat) {
            RecordingFormat.M4A -> {
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            RecordingFormat.OGG -> {
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            }
            else -> error("unsupported format for MediaRecorder: ${context.config.recordingFormat}")
        }

        setAudioEncodingBitRate(context.config.bitrate)
        setAudioSamplingRate(context.config.samplingRate)
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        recorder.setOutputFile(parcelFileDescriptor.fileDescriptor)
    }

    override fun prepare() {
        recorder.prepare()
    }

    override fun start() {
        recorder.start()
    }

    override fun stop() {
        recorder.stop()
    }

    @SuppressLint("NewApi")
    override fun pause() {
        recorder.pause()
    }

    @SuppressLint("NewApi")
    override fun resume() {
        recorder.resume()
    }

    override fun release() {
        recorder.release()
    }

    override fun getMaxAmplitude(): Int {
        return recorder.maxAmplitude
    }
}
