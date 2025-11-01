package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import org.fossify.voicerecorder.extensions.config

class MediaRecorderWrapper(val context: Context) : Recorder {

    @Suppress("DEPRECATION")
    private var recorder = MediaRecorder()

    init {
        recorder.setAudioSource(context.config.microphoneMode)
        recorder.setOutputFormat(context.config.getOutputFormat())
        recorder.setAudioEncoder(context.config.getAudioEncoder())
        recorder.setAudioChannels(context.config.channelCount)
        recorder.setAudioSamplingRate(context.config.samplingRate)
        recorder.setAudioEncodingBitRate(context.config.bitrate)
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
