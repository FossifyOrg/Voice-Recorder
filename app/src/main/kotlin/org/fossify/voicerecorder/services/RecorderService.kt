package org.fossify.voicerecorder.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import org.fossify.commons.extensions.getLaunchIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SplashActivity
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.getFormattedFilename
import org.fossify.voicerecorder.extensions.isStorageVolumeMounted
import org.fossify.voicerecorder.extensions.recordingStore
import org.fossify.voicerecorder.extensions.recordingStoreFor
import org.fossify.voicerecorder.extensions.removableVolumeId
import org.fossify.voicerecorder.extensions.updateWidgets
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
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
import org.fossify.voicerecorder.store.DEFAULT_MEDIA_URI
import org.fossify.voicerecorder.store.RecordingFormat
import org.fossify.voicerecorder.store.RecordingStore
import org.greenrobot.eventbus.EventBus
import java.util.Timer
import java.util.TimerTask

class RecorderService : Service() {
    companion object {
        var isRunning = false

        private const val AMPLITUDE_UPDATE_MS = 75L

        private const val TAG = "RecorderService"
    }

    private var duration = 0
    private var status = RECORDING_STOPPED
    private var durationTimer = Timer()
    private var amplitudeTimer = Timer()
    private var recorder: Recorder? = null
    private var writer: RecordingStore.Writer? = null

    // UUID of the removable volume being recorded to, null when recording to internal storage
    private var recordingVolumeId: String? = null

    // Android kills processes that still have files open on a volume within a second of starting
    // to eject it, so the recording has to be finalized as soon as the eject is announced. On
    // Android 11+ this uses a StorageVolumeCallback, since the ACTION_MEDIA_EJECT broadcast
    // usually arrives after the process has already been killed.
    private var storageVolumeCallback: Any? = null
    private var isStorageReceiverRegistered = false

    private val storageRemovedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val volume = IntentCompat.getParcelableExtra(
                intent, StorageVolume.EXTRA_STORAGE_VOLUME, StorageVolume::class.java
            )

            // The intent data is the volume's mount point, e.g. file:///storage/1234-ABCD
            onStorageVolumeRemoved(volume?.uuid ?: intent.data?.lastPathSegment)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent.action) {
            GET_RECORDER_INFO -> broadcastRecorderInfo()
            STOP_AMPLITUDE_UPDATE -> amplitudeTimer.cancel()
            TOGGLE_PAUSE -> togglePause()
            CANCEL_RECORDING -> cancelRecording()
            else -> startRecording()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        isRunning = false
        updateWidgets(false)
    }

    // mp4 output format with aac encoding should produce good enough m4a files according to https://stackoverflow.com/a/33054794/1967672
    @SuppressLint("DiscouragedApi")
    private fun startRecording() {
        isRunning = true
        updateWidgets(true)
        if (status == RECORDING_RUNNING) {
            return
        }

        val recordingFormat = config.recordingFormat

        try {
            val recordingName = "${getFormattedFilename()}.${recordingFormat.getExtension(this)}"
            val (store, writer) = try {
                createWriter(recordingName)
            } catch (e: Exception) {
                cancelRecording()
                EventBus.getDefault().post(Events.RecordingFailed(e))
                return
            }
            this.writer = writer

            recorder = when (recordingFormat) {
                RecordingFormat.M4A, RecordingFormat.OGG -> MediaRecorderWrapper(this)
                RecordingFormat.MP3 -> Mp3Recorder(this)
            }

            recorder?.setOutputFile(writer.fileDescriptor)
            recorder?.prepare()
            recorder?.start()
            recordingVolumeId = store.uri.removableVolumeId
            registerStorageListener()
            duration = 0
            status = RECORDING_RUNNING
            broadcastRecorderInfo()
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())

            durationTimer = Timer()
            durationTimer.scheduleAtFixedRate(getDurationUpdateTask(), 1000, 1000)

            startAmplitudeUpdates()
        } catch (e: Exception) {
            showErrorToast(e)
            stopRecording()
        }
    }

    // Creates the writer in the configured recordings folder. If that folder is on a removable
    // volume that isn't mounted (e.g. the USB drive was unplugged), falls back to the default
    // media folder on the device instead of failing.
    private fun createWriter(name: String): Pair<RecordingStore, RecordingStore.Writer> {
        val store = recordingStore
        return try {
            store to store.createWriter(name)
        } catch (e: Exception) {
            val volumeId = store.uri.removableVolumeId
            if (volumeId == null || isStorageVolumeMounted(volumeId)) {
                throw e
            }

            val fallbackStore = recordingStoreFor(DEFAULT_MEDIA_URI)
            val writer = fallbackStore.createWriter(name)
            toast(
                getString(R.string.recordings_folder_unavailable_fallback, fallbackStore.shortName),
                Toast.LENGTH_LONG
            )
            fallbackStore to writer
        }
    }

    private fun stopRecording(commitInBackground: Boolean = true) {
        durationTimer.cancel()
        amplitudeTimer.cancel()
        unregisterStorageListener()
        status = RECORDING_STOPPED

        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (
            @Suppress(
                "TooGenericExceptionCaught", "SwallowedException"
            ) e: RuntimeException
        ) {
            toast(R.string.recording_too_short)
        } catch (e: Exception) {
            Log.e(TAG, "failed to stop recording", e)
            showErrorToast(e)
        } finally {
            recorder = null
        }

        writer?.let { writer ->
            if (commitInBackground) {
                ensureBackgroundThread { commitRecording(writer) }
            } else {
                commitRecording(writer)
            }
        }
        writer = null
    }

    private fun commitRecording(writer: RecordingStore.Writer) {
        try {
            val uri = writer.commit()
            recordingSavedSuccessfully(uri)
            EventBus.getDefault().post(Events.RecordingCompleted())
        } catch (e: Exception) {
            Log.e(TAG, "failed to commit recording writer", e)
            showErrorToast(e)
        }
    }

    private fun cancelRecording() {
        durationTimer.cancel()
        amplitudeTimer.cancel()
        unregisterStorageListener()
        status = RECORDING_STOPPED

        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        }

        recorder = null

        writer?.cancel()
        writer = null

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
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun registerStorageListener() {
        if (recordingVolumeId == null) {
            return
        }

        if (isRPlus()) {
            registerStorageVolumeCallback()
        } else if (!isStorageReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }

            ContextCompat.registerReceiver(
                this, storageRemovedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isStorageReceiverRegistered = true
        }
    }

    private fun unregisterStorageListener() {
        if (isRPlus()) {
            unregisterStorageVolumeCallback()
        } else if (isStorageReceiverRegistered) {
            unregisterReceiver(storageRemovedReceiver)
            isStorageReceiverRegistered = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun registerStorageVolumeCallback() {
        if (storageVolumeCallback != null) {
            return
        }

        val callback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                if (volume.state != Environment.MEDIA_MOUNTED) {
                    onStorageVolumeRemoved(volume.uuid)
                }
            }
        }

        getSystemService(StorageManager::class.java)
            .registerStorageVolumeCallback(mainExecutor, callback)
        storageVolumeCallback = callback
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun unregisterStorageVolumeCallback() {
        val callback = storageVolumeCallback as? StorageManager.StorageVolumeCallback ?: return
        getSystemService(StorageManager::class.java).unregisterStorageVolumeCallback(callback)
        storageVolumeCallback = null
    }

    private fun onStorageVolumeRemoved(volumeId: String?) {
        if (status == RECORDING_STOPPED || !recordingVolumeId.equals(volumeId, ignoreCase = true)) {
            return
        }

        stopRecording(commitInBackground = false)
        broadcastStatus()
        toast(R.string.recording_stopped_storage_removed)
        stopSelf()
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
                    EventBus.getDefault().post(Events.RecordingAmplitude(recorder!!.getMaxAmplitude()))
                } catch (_: Exception) {
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
        val visibility = NotificationCompat.VISIBILITY_PUBLIC
        var text = getString(R.string.recording)
        if (status == RECORDING_PAUSED) {
            text += " (${getString(R.string.paused)})"
        }

        val builder =
            NotificationCompat.Builder(this, channelId).setContentTitle(label).setContentText(text).setSmallIcon(icon)
                .setContentIntent(getOpenAppIntent()).setPriority(NotificationManager.IMPORTANCE_DEFAULT)
                .setVisibility(visibility).setSound(null).setOngoing(true).setAutoCancel(true)

        return builder.build()
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = getLaunchIntent() ?: Intent(this, SplashActivity::class.java)
        return PendingIntent.getActivity(
            this, RECORDER_RUNNING_NOTIF_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun broadcastDuration() {
        EventBus.getDefault().post(Events.RecordingDuration(duration))
    }

    private fun broadcastStatus() {
        EventBus.getDefault().post(Events.RecordingStatus(status))
    }
}
