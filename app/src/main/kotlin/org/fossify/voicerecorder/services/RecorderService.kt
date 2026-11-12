package org.fossify.voicerecorder.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.fossify.commons.extensions.createDocumentUriUsingFirstParentTreeUri
import org.fossify.commons.extensions.createSAFFileSdk30
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getLaunchIntent
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isPathOnSD
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SplashActivity
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.getFormattedFilename
import org.fossify.voicerecorder.extensions.updateWidgets
import org.fossify.voicerecorder.helpers.BluetoothScoManager
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
import org.fossify.voicerecorder.helpers.EXTENSION_MP3
import org.fossify.voicerecorder.helpers.EXTRA_BT_OUTPUT_DEVICE_ID
import org.fossify.voicerecorder.helpers.EXTRA_PREFERRED_AUDIO_DEVICE_ID
import org.fossify.voicerecorder.helpers.GET_RECORDER_INFO
import org.fossify.voicerecorder.helpers.RECORDER_RUNNING_NOTIF_ID
import org.fossify.voicerecorder.helpers.RECORDING_PAUSED
import org.fossify.voicerecorder.helpers.RECORDING_RUNNING
import org.fossify.voicerecorder.helpers.RECORDING_STOPPED
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.helpers.TOGGLE_PAUSE
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.recorder.MediaRecorderWrapper
import org.fossify.voicerecorder.recorder.Mp3Recorder
import org.fossify.voicerecorder.recorder.Recorder
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.Timer
import java.util.TimerTask

class RecorderService : Service() {
    companion object {
        var isRunning = false

        private const val AMPLITUDE_UPDATE_MS = 75L
    }


    private var recordingPath = ""
    private var resultUri: Uri? = null

    private var duration = 0
    private var status = RECORDING_STOPPED
    private var durationTimer = Timer()
    private var amplitudeTimer = Timer()
    private var recorder: Recorder? = null
    private var bluetoothScoManager: BluetoothScoManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent.action) {
            GET_RECORDER_INFO -> broadcastRecorderInfo()
            STOP_AMPLITUDE_UPDATE -> amplitudeTimer.cancel()
            TOGGLE_PAUSE -> togglePause()
            CANCEL_RECORDING -> cancelRecording()
            else -> startRecording(intent)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        updateWidgets(false)
    }

    // mp4 output format with aac encoding should produce good enough m4a files according to https://stackoverflow.com/a/33054794/1967672
    @SuppressLint("DiscouragedApi")
    private fun startRecording(intent: Intent) {
        isRunning = true
        updateWidgets(true)
        if (status == RECORDING_RUNNING || status == RECORDING_PAUSED) {
            return
        }

        val defaultFolder = File(config.saveRecordingsFolder)
        if (!defaultFolder.exists()) {
            defaultFolder.mkdir()
        }

        val recordingFolder = defaultFolder.absolutePath
        recordingPath = "$recordingFolder/${getFormattedFilename()}.${config.getExtension()}"
        resultUri = null

        try {
            val preferredDeviceId = intent.getIntExtra(EXTRA_PREFERRED_AUDIO_DEVICE_ID, -1)
            val btOutputDeviceId = intent.getIntExtra(EXTRA_BT_OUTPUT_DEVICE_ID, -1)

            if (preferredDeviceId != -1) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val scoManager = BluetoothScoManager(audioManager)
                bluetoothScoManager = scoManager

                val inputDevice = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.id == preferredDeviceId }

                // Not setting the output device doesn't seem to enable the microphone.
                // So, we set both an OUTPUT device and an INPUT device
                val outputDevice = if (btOutputDeviceId != -1) {
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.id == btOutputDeviceId }
                } else {
                    null
                }

                if (inputDevice != null && BluetoothScoManager.isBluetoothDevice(inputDevice)) {
                    scoManager.start(outputDevice ?: inputDevice) {
                        try {
                            createAndStartRecorder(
                                audioSourceOverride = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                                preferredDevice = inputDevice
                            )
                        } catch (e: Exception) {
                            showErrorToast(e)
                            stopRecording()
                        }
                    }
                    return
                }
            }

            createAndStartRecorder(audioSourceOverride = null, preferredDevice = null)
        } catch (e: Exception) {
            showErrorToast(e)
            stopRecording()
        }
    }

    private fun createAndStartRecorder(audioSourceOverride: Int?, preferredDevice: AudioDeviceInfo?) {
        recorder = if (recordMp3()) {
            Mp3Recorder(this, audioSourceOverride)
        } else {
            MediaRecorderWrapper(this, audioSourceOverride)
        }
        recorder?.setPreferredDevice(preferredDevice)

        if (isRPlus()) {
            val fileUri = createDocumentUriUsingFirstParentTreeUri(recordingPath)
            createSAFFileSdk30(recordingPath)
            resultUri = fileUri
            // For the bluetooth path, we need to set "r" too
            contentResolver.openFileDescriptor(fileUri, "rw")!!
                .use { recorder?.setOutputFile(it) }
        } else if (isPathOnSD(recordingPath)) {
            var document = getDocumentFile(recordingPath.getParentPath())
            document = document?.createFile("", recordingPath.getFilenameFromPath())
            check(document != null) { "Failed to create document on SD Card" }
            resultUri = document.uri
            contentResolver.openFileDescriptor(document.uri, "rw")!!
                .use { recorder?.setOutputFile(it) }
        } else {
            recorder?.setOutputFile(recordingPath)
            resultUri = FileProvider.getUriForFile(
                this, "${BuildConfig.APPLICATION_ID}.provider", File(recordingPath)
            )
        }

        if (isQPlus()) {
            startForeground(
                RECORDER_RUNNING_NOTIF_ID,
                showNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())
        }

        recorder?.prepare()
        recorder?.start()
        duration = 0
        status = RECORDING_RUNNING
        broadcastRecorderInfo()

        durationTimer = Timer()
        durationTimer.scheduleAtFixedRate(getDurationUpdateTask(), 1000, 1000)

        startAmplitudeUpdates()
    }

    private fun stopRecording() {
        durationTimer.cancel()
        amplitudeTimer.cancel()
        status = RECORDING_STOPPED
        isRunning = false
        broadcastStatus()
        bluetoothScoManager?.stop()

        recorder?.apply {
            try {
                stop()
                release()
            } catch (
                @Suppress(
                    "TooGenericExceptionCaught",
                    "SwallowedException"
                ) e: RuntimeException
            ) {
                toast(R.string.recording_too_short)
            } catch (e: Exception) {
                showErrorToast(e)
                e.printStackTrace()
            }

            ensureBackgroundThread {
                scanRecording()
                EventBus.getDefault().post(Events.RecordingCompleted())
            }
        }
        recorder = null
    }

    private fun cancelRecording() {
        durationTimer.cancel()
        amplitudeTimer.cancel()
        status = RECORDING_STOPPED
        bluetoothScoManager?.stop()

        recorder?.apply {
            try {
                stop()
                release()
            } catch (ignored: Exception) {
            }
        }

        recorder = null
        if (isRPlus()) {
            val recordingUri = createDocumentUriUsingFirstParentTreeUri(recordingPath)
            DocumentsContract.deleteDocument(contentResolver, recordingUri)
        } else {
            File(recordingPath).delete()
        }

        EventBus.getDefault().post(Events.RecordingCompleted())
        stopSelf()
    }

    private fun broadcastRecorderInfo() {
        broadcastDuration()
        broadcastStatus()
        startAmplitudeUpdates()
    }

    @SuppressLint("DiscouragedApi")
    private fun startAmplitudeUpdates() {
        amplitudeTimer.cancel()
        amplitudeTimer = Timer()
        amplitudeTimer.scheduleAtFixedRate(getAmplitudeUpdateTask(), 0, AMPLITUDE_UPDATE_MS)
    }

    @SuppressLint("NewApi")
    private fun togglePause() {
        try {
            if (status == RECORDING_RUNNING) {
                recorder?.pause()
                status = RECORDING_PAUSED
            } else if (status == RECORDING_PAUSED) {
                recorder?.resume()
                status = RECORDING_RUNNING
            }
            broadcastStatus()
            if (isQPlus()) {
                startForeground(
                    RECORDER_RUNNING_NOTIF_ID,
                    showNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun scanRecording() {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(recordingPath),
            arrayOf(recordingPath.getMimeType())
        ) { _, uri ->
            if (uri == null) {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                return@scanFile
            }

            recordingSavedSuccessfully(resultUri ?: uri)
        }
    }

    private fun recordingSavedSuccessfully(savedUri: Uri) {
        toast(R.string.recording_saved_successfully)
        EventBus.getDefault().post(Events.RecordingSaved(savedUri))
    }

    private fun getDurationUpdateTask() = object : TimerTask() {
        override fun run() {
            if (status == RECORDING_RUNNING) {
                duration++
                broadcastDuration()
            }
        }
    }

    private fun getAmplitudeUpdateTask() = object : TimerTask() {
        override fun run() {
            if (recorder != null) {
                try {
                    EventBus.getDefault()
                        .post(Events.RecordingAmplitude(recorder!!.getMaxAmplitude()))
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun showNotification(): Notification {
        val channelId = "simple_recorder"
        val label = getString(R.string.app_name)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        NotificationChannel(channelId, label, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            notificationManager.createNotificationChannel(this)
        }

        val icon = R.drawable.ic_graphic_eq_vector
        val title = label
        val visibility = NotificationCompat.VISIBILITY_PUBLIC
        var text = getString(R.string.recording)
        if (status == RECORDING_PAUSED) {
            text += " (${getString(R.string.paused)})"
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(getOpenAppIntent())
            .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
            .setVisibility(visibility)
            .setSound(null)
            .setOngoing(true)
            .setAutoCancel(true)

        return builder.build()
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = getLaunchIntent() ?: Intent(this, SplashActivity::class.java)
        return PendingIntent.getActivity(
            this,
            RECORDER_RUNNING_NOTIF_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun broadcastDuration() {
        EventBus.getDefault().post(Events.RecordingDuration(duration))
    }

    private fun broadcastStatus() {
        EventBus.getDefault().post(Events.RecordingStatus(status))
    }

    private fun recordMp3(): Boolean {
        return config.extension == EXTENSION_MP3
    }
}
