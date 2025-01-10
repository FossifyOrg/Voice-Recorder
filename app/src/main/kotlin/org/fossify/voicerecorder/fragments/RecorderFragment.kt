package org.fossify.voicerecorder.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.compose.extensions.getActivity
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.toast
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.FragmentRecorderBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.ensureStoragePermission
import org.fossify.voicerecorder.extensions.setDebouncedClickListener
import org.fossify.voicerecorder.extensions.setKeepScreenAwake
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
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
    context: Context,
    attributeSet: AttributeSet
) : MyViewPagerFragment(context, attributeSet) {

    private var status = RECORDING_STOPPED
    private var pauseBlinkTimer = Timer()
    private var bus: EventBus? = null
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
    }

    override fun onDestroy() {
        bus?.unregister(this)
        pauseBlinkTimer.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupColors()
        binding.recorderVisualizer.recreate()
        bus = EventBus.getDefault()
        bus!!.register(this)

        updateRecordingDuration(0)
        binding.toggleRecordingButton.setDebouncedClickListener {
            val activity = context as? BaseSimpleActivity
            activity?.ensureStoragePermission {
                if (it) {
                    activity.handleNotificationPermission { granted ->
                        if (granted) {
                            toggleRecording()
                        } else {
                            PermissionRequiredDialog(
                                activity = context as BaseSimpleActivity,
                                textId = org.fossify.commons.R.string.allow_notifications_voice_recorder,
                                positiveActionCallback = {
                                    (context as BaseSimpleActivity).openNotificationSettings()
                                }
                            )
                        }
                    }
                } else {
                    activity.toast(org.fossify.commons.R.string.no_storage_permissions)
                }
            }
        }

        binding.cancelRecordingButton.setDebouncedClickListener { cancelRecording() }
        binding.saveRecordingButton.setDebouncedClickListener { saveRecording() }
        Intent(context, RecorderService::class.java).apply {
            action = GET_RECORDER_INFO
            try {
                context.startService(this)
            } catch (ignored: Exception) {
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
            drawableId = drawable,
            color = context.getProperPrimaryColor().getContrastColor()
        )
    }

    private fun toggleRecording() {
        status = if (status == RECORDING_RUNNING) RECORDING_PAUSED else RECORDING_RUNNING
        binding.toggleRecordingButton.setImageDrawable(getToggleButtonIcon())

        if (status == RECORDING_RUNNING) {
            startRecording()
        } else {
            Intent(context, RecorderService::class.java).apply {
                action = TOGGLE_PAUSE
                context.startService(this)
            }
        }
    }

    private fun startRecording() {
        Intent(context, RecorderService::class.java).apply {
            context.startService(this)
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
                    binding.toggleRecordingButton.alpha =
                        if (binding.toggleRecordingButton.alpha == 0f) 1f else 0f
                }
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun refreshView() {
        binding.toggleRecordingButton.setImageDrawable(getToggleButtonIcon())
        binding.saveRecordingButton.beVisibleIf(status != RECORDING_STOPPED)
        binding.cancelRecordingButton.beVisibleIf(status != RECORDING_STOPPED)
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
}
