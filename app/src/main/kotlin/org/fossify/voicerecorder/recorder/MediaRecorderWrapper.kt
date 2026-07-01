package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import org.fossify.voicerecorder.extensions.config

class MediaRecorderWrapper(val context: Context, audioSourceOverride: Int? = null) : Recorder {

    private var outputParcelFileDescriptor: ParcelFileDescriptor? = null

    private var recorder = createMediaRecorder().apply {
        setAudioSource(audioSourceOverride ?: context.config.microphoneMode)
        setOutputFormat(context.config.getOutputFormat())
        setAudioEncoder(context.config.getAudioEncoder())
        setAudioEncodingBitRate(context.config.bitrate)
        setAudioSamplingRate(context.config.samplingRate)
    }

    override fun setOutputFile(path: String) {
        recorder.setOutputFile(path)
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        outputParcelFileDescriptor?.close()
        val pFD = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
        outputParcelFileDescriptor = pFD
        recorder.setOutputFile(pFD.fileDescriptor)
    }

    override fun setPreferredDevice(device: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            recorder.setPreferredDevice(device)
        }
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
        outputParcelFileDescriptor?.close()
        outputParcelFileDescriptor = null
    }

    override fun getMaxAmplitude(): Int {
        return recorder.maxAmplitude
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }
}
