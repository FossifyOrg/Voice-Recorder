package org.fossify.voicerecorder.dialogs

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.DialogTranscriptBinding
import org.fossify.voicerecorder.databinding.ItemTranscriptSegmentBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.ACTION_CANCEL_TRANSCRIPTION
import org.fossify.voicerecorder.helpers.ACTION_START_TRANSCRIPTION
import org.fossify.voicerecorder.helpers.EXTRA_LANGUAGE
import org.fossify.voicerecorder.helpers.EXTRA_MODEL_ID
import org.fossify.voicerecorder.helpers.EXTRA_RECORDING_URI
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.TranscriptionPhase
import org.fossify.voicerecorder.services.TranscriptionService
import org.fossify.voicerecorder.store.Recording
import org.fossify.voicerecorder.store.Transcript
import org.fossify.voicerecorder.store.TranscriptSegment
import org.fossify.voicerecorder.store.TranscriptStore
import org.fossify.voicerecorder.transcribe.model.ModelCatalog
import org.fossify.voicerecorder.transcribe.model.ModelManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Locale

/**
 * Shows the transcript for a recording and offers Transcribe / Cancel actions.
 *
 * The dialog auto-updates as the [TranscriptionService] posts events. Tapping a segment
 * invokes [onSeek] (the player should seek to the segment's start) and dismisses the dialog.
 */
@Suppress("TooManyFunctions")
class TranscriptDialog(
    private val activity: BaseSimpleActivity,
    private val recording: Recording,
    private val onSeek: (positionMs: Long) -> Unit,
) {
    private val binding: DialogTranscriptBinding =
        DialogTranscriptBinding.inflate(LayoutInflater.from(activity))
    private var dialog: AlertDialog? = null
    private var currentTranscript: Transcript? = null

    init {
        EventBus.getDefault().register(this)

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.close, null)
            .setOnDismissListener { EventBus.getDefault().unregister(this) }
            .apply {
                activity.setupDialogStuff(
                    view = binding.root,
                    dialog = this,
                    titleId = R.string.transcript,
                ) { alertDialog ->
                    dialog = alertDialog
                }
            }

        binding.transcriptStartBtn.setOnClickListener { startTranscription() }
        binding.transcriptCancelBtn.setOnClickListener { cancelTranscription() }
        binding.transcriptRetranscribeBtn.setOnClickListener { startTranscription() }

        // Load initial state on a background thread (sidecar I/O can hit ContentResolver).
        ensureBackgroundThread {
            val store = TranscriptStore(activity, activity.config.saveRecordingsFolder)
            val existing = store.read(recording)
            activity.runOnUiThread { renderInitialState(existing) }
        }
    }

    private fun renderInitialState(existing: Transcript?) {
        currentTranscript = existing
        when {
            TranscriptionService.isRunning -> renderBusy(getString(R.string.transcribing), 0, indeterminate = true)
            existing != null -> renderReady(existing)
            else -> renderIdle()
        }
    }

    private fun renderIdle() {
        binding.transcriptIdle.visibility = View.VISIBLE
        binding.transcriptBusy.visibility = View.GONE
        binding.transcriptReady.visibility = View.GONE

        val spec = ModelCatalog.byId(activity.config.transcribeModelId ?: ModelCatalog.DEFAULT.id)
            ?: ModelCatalog.DEFAULT
        val mgr = ModelManager(activity)
        val subtitle = if (mgr.isModelInstalled(spec)) {
            spec.displayName
        } else {
            "${spec.displayName} (~${spec.archiveSizeBytes / BYTES_PER_MB} MB will be downloaded)"
        }
        binding.transcriptIdleSubtitle.text = subtitle
    }

    private fun renderBusy(label: String, progress: Int, indeterminate: Boolean) {
        binding.transcriptIdle.visibility = View.GONE
        binding.transcriptBusy.visibility = View.VISIBLE
        binding.transcriptReady.visibility = View.GONE
        binding.transcriptBusyLabel.text = label
        binding.transcriptBusyProgress.isIndeterminate = indeterminate
        if (!indeterminate) {
            binding.transcriptBusyProgress.setProgressCompat(progress, true)
        }
    }

    private fun renderReady(transcript: Transcript) {
        binding.transcriptIdle.visibility = View.GONE
        binding.transcriptBusy.visibility = View.GONE
        binding.transcriptReady.visibility = View.VISIBLE

        val langLabel = transcript.language.ifBlank { "?" }
        binding.transcriptReadySubtitle.text =
            "Language: $langLabel · ${transcript.segments.size} segments"

        val processingMs = transcript.processingMs
        if (processingMs != null && processingMs > 0L) {
            binding.transcriptReadyProcessingTime.visibility = View.VISIBLE
            binding.transcriptReadyProcessingTime.text =
                activity.getString(R.string.transcript_processing_time, formatProcessingTime(processingMs))
        } else {
            binding.transcriptReadyProcessingTime.visibility = View.GONE
        }

        val container = binding.transcriptSegmentsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        for (segment in transcript.segments) {
            val itemBinding = ItemTranscriptSegmentBinding.inflate(inflater, container, false)
            itemBinding.segmentTimestamp.text = formatTimestamp(segment.startMs)
            itemBinding.segmentText.text = segment.text
            itemBinding.root.setOnClickListener {
                onSeek(segment.startMs)
                dialog?.dismiss()
            }
            itemBinding.root.setOnLongClickListener {
                copySegment(segment)
                true
            }
            container.addView(itemBinding.root)
        }
    }

    private fun startTranscription() {
        if (TranscriptionService.isRunning) {
            activity.toast(R.string.transcribing)
            return
        }
        val intent = Intent(activity, TranscriptionService::class.java).apply {
            action = ACTION_START_TRANSCRIPTION
            putExtra(EXTRA_RECORDING_URI, recording.uri.toString())
            putExtra(EXTRA_MODEL_ID, activity.config.transcribeModelId ?: ModelCatalog.DEFAULT.id)
            putExtra(EXTRA_LANGUAGE, activity.config.transcribeLanguage)
        }
        activity.startForegroundService(intent)
        renderBusy(getString(R.string.transcribing), 0, indeterminate = true)
    }

    private fun cancelTranscription() {
        val intent = Intent(activity, TranscriptionService::class.java).apply {
            action = ACTION_CANCEL_TRANSCRIPTION
        }
        activity.startService(intent)
    }

    private fun copySegment(segment: TranscriptSegment) {
        activity.copyToClipboard(segment.text)
    }

    private fun isOurs(uri: android.net.Uri): Boolean = uri == recording.uri

    private fun getString(resId: Int) = activity.getString(resId)

    private fun formatTimestamp(ms: Long): String {
        val totalSec = ms / MS_PER_SECOND
        val mm = totalSec / SEC_PER_MIN
        val ss = totalSec % SEC_PER_MIN
        return String.format(Locale.ROOT, "%02d:%02d", mm, ss)
    }

    private fun formatProcessingTime(ms: Long): String {
        val totalSec = ms / MS_PER_SECOND
        return if (totalSec >= SEC_PER_MIN) {
            val minutes = totalSec / SEC_PER_MIN
            val seconds = totalSec % SEC_PER_MIN
            activity.getString(R.string.transcript_processing_time_minutes, minutes, seconds)
        } else {
            activity.getString(R.string.transcript_processing_time_seconds, ms / MS_PER_SECOND.toFloat())
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTranscriptionStarted(e: Events.TranscriptionStarted) {
        if (!isOurs(e.recordingUri)) return
        renderBusy(getString(R.string.transcribing), 0, indeterminate = true)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTranscriptionProgress(e: Events.TranscriptionProgress) {
        if (!isOurs(e.recordingUri)) return
        val labelRes = when (e.phase) {
            TranscriptionPhase.DOWNLOADING_MODEL -> R.string.downloading_model
            TranscriptionPhase.DECODING -> R.string.decoding_audio
            TranscriptionPhase.TRANSCRIBING -> R.string.transcribing
            TranscriptionPhase.WRITING -> R.string.transcribing
        }
        val pct = (e.fraction * PCT_MAX).toInt().coerceIn(0, PCT_MAX)
        renderBusy("${getString(labelRes)} $pct%", pct, indeterminate = false)
    }

    private companion object {
        const val PCT_MAX = 100
        const val MS_PER_SECOND = 1000L
        const val SEC_PER_MIN = 60L
        const val BYTES_PER_MB = 1_000_000L
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTranscriptionCompleted(e: Events.TranscriptionCompleted) {
        if (!isOurs(e.recordingUri)) return
        ensureBackgroundThread {
            val store = TranscriptStore(activity, activity.config.saveRecordingsFolder)
            val transcript = store.read(recording)
            activity.runOnUiThread {
                if (transcript != null) renderReady(transcript) else renderIdle()
            }
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTranscriptionFailed(e: Events.TranscriptionFailed) {
        if (!isOurs(e.recordingUri)) return
        activity.toast(activity.getString(R.string.transcript_failed, e.cause.message ?: "?"))
        renderInitialState(currentTranscript)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onTranscriptionCancelled(e: Events.TranscriptionCancelled) {
        if (!isOurs(e.recordingUri)) return
        activity.toast(R.string.transcript_cancelled)
        renderInitialState(currentTranscript)
    }
}
