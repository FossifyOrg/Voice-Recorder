package org.fossify.voicerecorder.helpers

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

class BluetoothScoManager(private val audioManager: AudioManager) {

    companion object {
        fun isBluetoothDevice(device: AudioDeviceInfo): Boolean {
            return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    var isActive: Boolean = false
        private set

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    fun start(device: AudioDeviceInfo? = null, onReady: (() -> Unit)? = null) {
        if (isActive) {
            onReady?.invoke()
            return
        }

        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (device != null) {
                audioManager.setCommunicationDevice(device)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
        isActive = true
        onReady?.invoke()
    }

    fun stop() {
        if (!isActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
        audioManager.mode = previousAudioMode
        isActive = false
    }
}
