package org.fossify.voicerecorder.recorder

import android.media.AudioDeviceInfo
import android.os.ParcelFileDescriptor

interface Recorder {
    fun setOutputFile(path: String)
    fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor)
    fun setPreferredDevice(device: AudioDeviceInfo?)
    fun prepare()
    fun start()
    fun stop()
    fun pause()
    fun resume()
    fun release()
    fun getMaxAmplitude(): Int
}
