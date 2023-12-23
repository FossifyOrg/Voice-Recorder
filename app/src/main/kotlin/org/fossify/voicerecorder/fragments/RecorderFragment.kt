package org.fossify.voicerecorder.fragments

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.isNougatPlus
import org.fossify.voicerecorder.databinding.FragmentRecorderBinding
import org.fossify.voicerecorder.helpers.*
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.services.RecorderService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Timer
import java.util.TimerTask

class RecorderFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment(context, attributeSet) {
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
        binding.toggleRecordingButton.setOnClickListener {
            (context as? BaseSimpleActivity)?.handleNotificationPermission { granted ->
                if (granted) {
                    toggleRecording()
                } else {
                    PermissionRequiredDialog(context as BaseSimpleActivity, org.fossify.commons.R.string.allow_notifications_voice_recorder, {
                        (context as BaseSimpleActivity).openNotificationSettings()
                    })
                }
            }
        }

        binding.togglePauseButton.setOnClickListener {
            Intent(context, RecorderService::class.java).apply {
                action = TOGGLE_PAUSE
                context.startService(this)
            }
        }

        Intent(context, RecorderService::class.java).apply {
            action = GET_RECORDER_INFO
            try {
                context.startService(this)
            } catch (e: Exception) {
            }
        }
    }

    private fun setupColors() {
        val properPrimaryColor = context.getProperPrimaryColor()
        binding.toggleRecordingButton.apply {
            setImageDrawable(getToggleButtonIcon())
            background.applyColorFilter(properPrimaryColor)
        }

        binding.togglePauseButton.apply {
            setImageDrawable(resources.getColoredDrawableWithColor(org.fossify.commons.R.drawable.ic_pause_vector, properPrimaryColor.getContrastColor()))
            background.applyColorFilter(properPrimaryColor)
        }

        binding.recorderVisualizer.chunkColor = properPrimaryColor
        binding.recordingDuration.setTextColor(context.getProperTextColor())
    }

    private fun updateRecordingDuration(duration: Int) {
        binding.recordingDuration.text = duration.getFormattedDuration()
    }

    private fun getToggleButtonIcon(): Drawable {
        val drawable =
            if (status == RECORDING_RUNNING || status == RECORDING_PAUSED) org.fossify.commons.R.drawable.ic_stop_vector else org.fossify.commons.R.drawable.ic_microphone_vector
        return resources.getColoredDrawableWithColor(drawable, context.getProperPrimaryColor().getContrastColor())
    }

    private fun toggleRecording() {
        status = if (status == RECORDING_RUNNING || status == RECORDING_PAUSED) {
            RECORDING_STOPPED
        } else {
            RECORDING_RUNNING
        }

        binding.toggleRecordingButton.setImageDrawable(getToggleButtonIcon())

        if (status == RECORDING_RUNNING) {
            startRecording()
        } else {
            binding.togglePauseButton.beGone()
            stopRecording()
        }
    }

    private fun startRecording() {
        Intent(context, RecorderService::class.java).apply {
            context.startService(this)
        }
        binding.recorderVisualizer.recreate()
    }

    private fun stopRecording() {
        Intent(context, RecorderService::class.java).apply {
            context.stopService(this)
        }
    }

    private fun getPauseBlinkTask() = object : TimerTask() {
        override fun run() {
            if (status == RECORDING_PAUSED) {
                // update just the alpha so that it will always be clickable
                Handler(Looper.getMainLooper()).post {
                    binding.togglePauseButton.alpha = if (binding.togglePauseButton.alpha == 0f) 1f else 0f
                }
            }
        }
    }

    private fun refreshView() {
        binding.toggleRecordingButton.setImageDrawable(getToggleButtonIcon())
        binding.togglePauseButton.beVisibleIf(status != RECORDING_STOPPED && isNougatPlus())
        pauseBlinkTimer.cancel()

        if (status == RECORDING_PAUSED) {
            pauseBlinkTimer = Timer()
            pauseBlinkTimer.scheduleAtFixedRate(getPauseBlinkTask(), 500, 500)
        }

        if (status == RECORDING_RUNNING) {
            binding.togglePauseButton.alpha = 1f
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotDurationEvent(event: Events.RecordingDuration) {
        updateRecordingDuration(event.duration)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotStatusEvent(event: Events.RecordingStatus) {
        status = event.status
        refreshView()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun gotAmplitudeEvent(event: Events.RecordingAmplitude) {
        val amplitude = event.amplitude
        if (status == RECORDING_RUNNING) {
            binding.recorderVisualizer.update(amplitude)
        }
    }
}
