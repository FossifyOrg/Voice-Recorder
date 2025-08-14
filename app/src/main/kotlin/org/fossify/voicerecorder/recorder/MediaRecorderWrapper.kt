package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.RECORDING_AUDIO_SOURCE

class MediaRecorderWrapper(val context: Context) : Recorder {

    @Suppress("DEPRECATION")
    private var recorder = MediaRecorder().apply {
        setAudioSource(RECORDING_AUDIO_SOURCE)
        setOutputFormat(context.config.getOutputFormat())
        setAudioEncoder(context.config.getAudioEncoder())
        setAudioEncodingBitRate(context.config.bitrate)
        setAudioSamplingRate(context.config.samplingRate)
    }

    override fun setOutputFile(path: String) {
        recorder.setOutputFile(path)
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        val pFD = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
        recorder.setOutputFile(pFD.fileDescriptor)
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
