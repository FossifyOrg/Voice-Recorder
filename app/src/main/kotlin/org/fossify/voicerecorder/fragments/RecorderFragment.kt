package org.fossify.voicerecorder.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.compose.extensions.getActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.setDebouncedClickListener
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.ExternalStoragePermission
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.databinding.FragmentRecorderBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.setKeepScreenAwake
import org.fossify.voicerecorder.helpers.BluetoothScoManager
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
import org.fossify.voicerecorder.helpers.EXTRA_BT_OUTPUT_DEVICE_ID
import org.fossify.voicerecorder.helpers.EXTRA_PREFERRED_AUDIO_DEVICE_ID
import org.fossify.voicerecorder.helpers.GET_RECORDER_INFO
import org.fossify.voicerecorder.helpers.RECORDING_PAUSED
import org.fossify.voicerecorder.helpers.RECORDING_RUNNING
import org.fossify.voicerecorder.helpers.RECORDING_STOPPED
import org.fossify.voicerecorder.helpers.TOGGLE_PAUSE
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.services.RecorderService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Timer
import java.util.TimerTask

class RecorderFragment(
    context: Context, attributeSet: AttributeSet
) : MyViewPagerFragment(context, attributeSet) {

    private var status = RECORDING_STOPPED
    private var pauseBlinkTimer = Timer()
    private var bus: EventBus? = null
    private var bluetoothSelected = false
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private lateinit var binding: FragmentRecorderBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecorderBinding.bind(this)
    }

    override fun onResume() {
        setupColors()
        if (!RecorderService.isRunning) {
            status = RECORDING_STOPPED
        }

        refreshView()
        refreshBluetoothVisibility()
    }

    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != BLUETOOTH_PERMISSION_REQUEST_CODE) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            && findBluetoothInputDevice() != null
        ) {
            bluetoothSelected = true
            refreshBluetoothVisibility()
            refreshDeviceSelectorStatus()
        }
    }

    override fun onDestroy() {
        bus?.unregister(this)
        pauseBlinkTimer.cancel()
        unregisterAudioDeviceCallback()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupColors()
        binding.recorderVisualizer.recreate()
        bus = EventBus.getDefault()
        bus!!.register(this)

        setupTabSelector()
        registerAudioDeviceCallback()
        updateRecordingDuration(0)
        binding.toggleRecordingButton.setDebouncedClickListener {
            (context as? SimpleActivity)?.apply {
                handleExternalStoragePermission(ExternalStoragePermission.WRITE) { granted ->
                    if (granted == true) {
                        handleNotificationPermission { granted ->
                            if (granted) {
                                cycleRecordingState()
                            } else {
                                PermissionRequiredDialog(
                                    activity = this,
                                    textId = org.fossify.commons.R.string.allow_notifications_voice_recorder,
                                    positiveActionCallback = {
                                        (context as BaseSimpleActivity).openNotificationSettings()
                                    })
                            }
                        }
                    }
                }
            }
        }

        binding.cancelRecordingButton.setDebouncedClickListener { showCancelRecordingDialog() }
        binding.saveRecordingButton.setDebouncedClickListener { saveRecording() }
        Intent(context, RecorderService::class.java).apply {
            action = GET_RECORDER_INFO
            try {
                context.startService(this)
            } catch (_: Exception) {
            }
        }
    }

    private fun setupColors() {
        val properTextColor = context.getProperTextColor()
        val properPrimaryColor = context.getProperPrimaryColor()
        binding.toggleRecordingButton.apply {
            setImageDrawable(getToggleButtonIcon())
            background.applyColorFilter(properPrimaryColor)
        }

        binding.cancelRecordingButton.applyColorFilter(properTextColor)
        binding.saveRecordingButton.applyColorFilter(properTextColor)
        binding.recorderVisualizer.chunkColor = properPrimaryColor
        binding.recordingDuration.setTextColor(properTextColor)
        refreshDeviceSelectorStatus()
    }

    private fun refreshDeviceSelectorStatus() {
        val properTextColor = context.getProperTextColor()
        val properPrimaryColor = context.getProperPrimaryColor()
        val contrastColor = properPrimaryColor.getContrastColor()
        val btDevice = findBluetoothInputDevice()
        val btAvailable = btDevice != null

        binding.tabBluetooth.text = bluetoothTabLabel(btDevice)
        binding.tabBluetooth.alpha = if (btAvailable) 1f else BT_DISABLED_ALPHA
        binding.tabBluetooth.isEnabled = btAvailable

        if (bluetoothSelected && btAvailable) {
            binding.tabDefault.setBackgroundResource(android.R.color.transparent)
            binding.tabDefault.setTextColor(properTextColor)
            binding.tabBluetooth.setBackgroundResource(R.drawable.tab_selector_selected)
            binding.tabBluetooth.background.applyColorFilter(properPrimaryColor)
            binding.tabBluetooth.setTextColor(contrastColor)
        } else {
            binding.tabDefault.setBackgroundResource(R.drawable.tab_selector_selected)
            binding.tabDefault.background.applyColorFilter(properPrimaryColor)
            binding.tabDefault.setTextColor(contrastColor)
            binding.tabBluetooth.setBackgroundResource(android.R.color.transparent)
            binding.tabBluetooth.setTextColor(properTextColor)
        }
    }

    private fun bluetoothTabLabel(device: AudioDeviceInfo?): String {
        if (device == null) {
            return context.getString(R.string.mic_type_bluetooth_not_connected)
        }
        val name = if (hasBluetoothPermission()) {
            device.productName?.toString()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        return if (name != null) {
            context.getString(R.string.mic_type_bluetooth_named, name)
        } else {
            context.getString(R.string.mic_type_bluetooth)
        }
    }

    private fun updateRecordingDuration(duration: Int) {
        binding.recordingDuration.text = duration.getFormattedDuration()
    }

    private fun getToggleButtonIcon(): Drawable {
        val drawable = if (status == RECORDING_RUNNING || status == RECORDING_PAUSED) {
            R.drawable.ic_pause_recording_vector
        } else {
            R.drawable.ic_start_recording_vector
        }

        return resources.getColoredDrawableWithColor(
            drawableId = drawable, color = context.getProperPrimaryColor().getContrastColor()
        )
    }

    private fun cycleRecordingState() {
        when (status) {
            RECORDING_PAUSED, RECORDING_RUNNING -> {
                Intent(context, RecorderService::class.java).apply {
                    action = TOGGLE_PAUSE
                    context.startService(this)
                }
            }

            else -> {
                startRecording()
            }
        }

        status = if (status == RECORDING_RUNNING) RECORDING_PAUSED else RECORDING_RUNNING
        binding.toggleRecordingButton.setImageDrawable(getToggleButtonIcon())
    }

    private fun startRecording() {
        Intent(context, RecorderService::class.java).apply {
            if (bluetoothSelected) {
                val inputDevice = findBluetoothInputDevice()
                val outputDevice = findBluetoothOutputDevice()
                if (inputDevice != null) {
                    putExtra(EXTRA_PREFERRED_AUDIO_DEVICE_ID, inputDevice.id)
                }
                if (outputDevice != null) {
                    putExtra(EXTRA_BT_OUTPUT_DEVICE_ID, outputDevice.id)
                }
            }
            context.startService(this)
        }
    }

    private fun setupTabSelector() {
        refreshBluetoothVisibility()

        binding.tabDefault.setDebouncedClickListener {
            if (bluetoothSelected) {
                bluetoothSelected = false
                refreshDeviceSelectorStatus()
            }
        }

        binding.tabBluetooth.setDebouncedClickListener {
            if (findBluetoothInputDevice() == null) return@setDebouncedClickListener
            if (!bluetoothSelected) {
                ensureBluetoothPermission {
                    bluetoothSelected = true
                    refreshDeviceSelectorStatus()
                }
            }
        }
    }

    private fun refreshBluetoothVisibility() {
        val hasBtDevice = findBluetoothInputDevice() != null
        if (!hasBtDevice && bluetoothSelected) {
            bluetoothSelected = false
        }
        refreshDeviceSelectorStatus()
        binding.microphoneSelectorHolder.beVisibleIf(status == RECORDING_STOPPED)
    }

    private fun registerAudioDeviceCallback() {
        if (audioDeviceCallback != null) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshBluetoothVisibility()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshBluetoothVisibility()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        audioDeviceCallback = callback
    }

    private fun unregisterAudioDeviceCallback() {
        val callback = audioDeviceCallback ?: return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(callback)
        audioDeviceCallback = null
    }

    private fun hasBluetoothPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun findBluetoothInputDevice(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { BluetoothScoManager.isBluetoothDevice(it) }
    }

    private fun findBluetoothOutputDevice(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { BluetoothScoManager.isBluetoothDevice(it) }
    }

    @SuppressLint("InlinedApi")
    private fun ensureBluetoothPermission(callback: () -> Unit) {
        if (hasBluetoothPermission()) {
            callback()
            return
        }

        val activity = context as? BaseSimpleActivity ?: return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            BLUETOOTH_PERMISSION_REQUEST_CODE
        )
    }

    private fun showCancelRecordingDialog() {
        val activity = context as? BaseSimpleActivity ?: return
        ConfirmationDialog(
            activity = activity,
            message = activity.getString(R.string.discard_recording_confirmation),
            dialogTitle = activity.getString(R.string.discard_recording)
        ) {
            cancelRecording()
        }
    }

    private fun cancelRecording() {
        status = RECORDING_STOPPED
        Intent(context, RecorderService::class.java).apply {
            action = CANCEL_RECORDING
            context.startService(this)
        }
        refreshView()
    }

    private fun saveRecording() {
        status = RECORDING_STOPPED
        Intent(context, RecorderService::class.java).apply {
            context.stopService(this)
        }
        refreshView()
    }

    private fun getPauseBlinkTask() = object : TimerTask() {
        override fun run() {
            if (status == RECORDING_PAUSED) {
                // update just the alpha so that it will always be clickable
                Handler(Looper.getMainLooper()).post {
                    binding.toggleRecordingButton.alpha = if (binding.toggleRecordingButton.alpha == 0f) 1f else 0f
                }
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun refreshView() {
        binding.toggleRecordingButton.setImageDrawable(getToggleButtonIcon())
        binding.saveRecordingButton.beVisibleIf(status != RECORDING_STOPPED)
        binding.cancelRecordingButton.beVisibleIf(status != RECORDING_STOPPED)
        if (status == RECORDING_STOPPED) {
            refreshBluetoothVisibility()
        } else {
            binding.microphoneSelectorHolder.beVisibleIf(false)
        }
        pauseBlinkTimer.cancel()

        when (status) {
            RECORDING_PAUSED -> {
                pauseBlinkTimer = Timer()
                pauseBlinkTimer.scheduleAtFixedRate(getPauseBlinkTask(), 500, 500)
            }

            RECORDING_RUNNING -> {
                binding.toggleRecordingButton.alpha = 1f
                if (context.config.keepScreenOn) {
                    context.getActivity().setKeepScreenAwake(true)
                }
            }

            else -> {
                binding.toggleRecordingButton.alpha = 1f
                binding.recorderVisualizer.recreate()
                binding.recordingDuration.text = null
                context.getActivity().setKeepScreenAwake(false)
            }
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotDurationEvent(event: Events.RecordingDuration) {
        updateRecordingDuration(event.duration)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotStatusEvent(event: Events.RecordingStatus) {
        status = event.status
        refreshView()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotAmplitudeEvent(event: Events.RecordingAmplitude) {
        val amplitude = event.amplitude
        if (status == RECORDING_RUNNING) {
            binding.recorderVisualizer.update(amplitude)
        }
    }

    companion object {
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 100
        private const val BT_DISABLED_ALPHA = 0.4f
    }
}
