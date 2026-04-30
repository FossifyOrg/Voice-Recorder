package org.fossify.voicerecorder.dialogs

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.DialogTranscriptionModelsBinding
import org.fossify.voicerecorder.databinding.ItemTranscriptionModelBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.ACTION_CANCEL_MODEL_DOWNLOAD
import org.fossify.voicerecorder.helpers.ACTION_DOWNLOAD_MODEL
import org.fossify.voicerecorder.helpers.EXTRA_MODEL_ID
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.services.TranscriptionService
import org.fossify.voicerecorder.transcribe.model.ModelCatalog
import org.fossify.voicerecorder.transcribe.model.ModelManager
import org.fossify.voicerecorder.transcribe.model.ModelSpec
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Lets the user choose the active transcription model and manage downloads
 * (download / delete / cancel).
 */
@Suppress("TooManyFunctions")
class TranscriptionModelsDialog(
    private val activity: BaseSimpleActivity,
    private val onActiveModelChanged: () -> Unit = {},
) {
    private val binding: DialogTranscriptionModelsBinding =
        DialogTranscriptionModelsBinding.inflate(LayoutInflater.from(activity))
    private val rowBindings = mutableMapOf<String, ItemTranscriptionModelBinding>()
    private val modelManager = ModelManager(activity)

    init {
        EventBus.getDefault().register(this)

        for (spec in ModelCatalog.ALL) {
            val itemBinding = ItemTranscriptionModelBinding.inflate(
                LayoutInflater.from(activity), binding.transcriptionModelsContainer, false
            )
            itemBinding.modelName.text = spec.displayName
            itemBinding.root.setOnClickListener { setActive(spec) }
            itemBinding.modelRadio.setOnClickListener { setActive(spec) }
            itemBinding.modelActionBtn.setOnClickListener { onActionClicked(spec) }
            binding.transcriptionModelsContainer.addView(itemBinding.root)
            rowBindings[spec.id] = itemBinding
            renderRow(spec)
        }

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.close, null)
            .setOnDismissListener { EventBus.getDefault().unregister(this) }
            .apply {
                activity.setupDialogStuff(
                    view = binding.root,
                    dialog = this,
                    titleId = R.string.manage_models,
                ) { _: AlertDialog -> }
            }
    }

    private fun setActive(spec: ModelSpec) {
        if (activity.config.transcribeModelId == spec.id) return
        activity.config.transcribeModelId = spec.id
        for (s in ModelCatalog.ALL) renderRow(s)
        activity.toast(activity.getString(R.string.model_active_set, spec.displayName))
        onActiveModelChanged()
    }

    private fun onActionClicked(spec: ModelSpec) {
        val downloadingId = TranscriptionService.downloadingModelId
        when {
            downloadingId == spec.id -> cancelDownload()
            modelManager.isModelInstalled(spec) -> confirmDelete(spec)
            downloadingId != null -> activity.toast(R.string.transcribing)
            else -> startDownload(spec)
        }
    }

    private fun startDownload(spec: ModelSpec) {
        if (TranscriptionService.isRunning) {
            activity.toast(R.string.transcribing)
            return
        }
        val intent = Intent(activity, TranscriptionService::class.java).apply {
            action = ACTION_DOWNLOAD_MODEL
            putExtra(EXTRA_MODEL_ID, spec.id)
        }
        activity.startForegroundService(intent)
        renderRow(spec, downloadingFraction = 0f)
    }

    private fun cancelDownload() {
        val intent = Intent(activity, TranscriptionService::class.java).apply {
            action = ACTION_CANCEL_MODEL_DOWNLOAD
        }
        activity.startService(intent)
    }

    private fun confirmDelete(spec: ModelSpec) {
        ConfirmationDialog(
            activity = activity,
            message = activity.getString(R.string.model_delete_confirmation),
            positive = org.fossify.commons.R.string.yes,
            negative = org.fossify.commons.R.string.no,
        ) {
            modelManager.deleteModel(spec)
            renderRow(spec)
        }
    }

    private fun renderRow(spec: ModelSpec, downloadingFraction: Float? = null) {
        val item = rowBindings[spec.id] ?: return
        val isInstalled = modelManager.isModelInstalled(spec)
        val isActive = (activity.config.transcribeModelId ?: ModelCatalog.DEFAULT.id) == spec.id
        val sizeMb = spec.archiveSizeBytes / BYTES_PER_MB

        item.modelRadio.isChecked = isActive

        val downloadingId = TranscriptionService.downloadingModelId
        val isDownloading = downloadingFraction != null || downloadingId == spec.id

        when {
            isDownloading -> {
                val pct = ((downloadingFraction ?: 0f) * PCT_MAX).toInt().coerceIn(0, PCT_MAX)
                item.modelSubtitle.text =
                    activity.getString(R.string.model_download_in_progress, pct)
                item.modelProgress.visibility = View.VISIBLE
                item.modelProgress.setProgressCompat(pct, true)
                item.modelActionBtn.text = activity.getString(R.string.cancel_transcription)
            }
            isInstalled -> {
                val state = activity.getString(R.string.model_installed)
                item.modelSubtitle.text = "~$sizeMb MB · $state"
                item.modelProgress.visibility = View.GONE
                item.modelActionBtn.text = activity.getString(R.string.delete_downloaded_model)
            }
            else -> {
                val state = activity.getString(R.string.model_not_installed)
                item.modelSubtitle.text = "~$sizeMb MB · $state"
                item.modelProgress.visibility = View.GONE
                item.modelActionBtn.text = activity.getString(R.string.download_model)
            }
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onDownloadProgress(e: Events.ModelDownloadProgress) {
        val spec = ModelCatalog.byId(e.modelId) ?: return
        renderRow(spec, downloadingFraction = e.fraction)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onDownloadCompleted(e: Events.ModelDownloadCompleted) {
        val spec = ModelCatalog.byId(e.modelId) ?: return
        renderRow(spec)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onDownloadFailed(e: Events.ModelDownloadFailed) {
        val spec = ModelCatalog.byId(e.modelId) ?: return
        activity.toast(activity.getString(R.string.transcript_failed, e.cause.message ?: "?"))
        renderRow(spec)
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onDownloadCancelled(e: Events.ModelDownloadCancelled) {
        val spec = ModelCatalog.byId(e.modelId) ?: return
        renderRow(spec)
    }

    private companion object {
        const val PCT_MAX = 100
        const val BYTES_PER_MB = 1_000_000L
    }
}
