package org.fossify.voicerecorder.dialogs

import android.content.ContentValues
import android.provider.MediaStore.Audio.Media
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.databinding.DialogRenameRecordingBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.getAudioFileContentUri
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.greenrobot.eventbus.EventBus
import java.io.File

class RenameRecordingDialog(val activity: BaseSimpleActivity, val recording: Recording, val callback: () -> Unit) {
    init {
        val binding = DialogRenameRecordingBinding.inflate(activity.layoutInflater).apply {
            renameRecordingTitle.setText(recording.title.substringBeforeLast('.'))
        }
        val view = binding.root

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(view, this, org.fossify.commons.R.string.rename) { alertDialog ->
                    alertDialog.showKeyboard(binding.renameRecordingTitle)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newTitle = binding.renameRecordingTitle.value
                        if (newTitle.isEmpty()) {
                            activity.toast(org.fossify.commons.R.string.empty_name)
                            return@setOnClickListener
                        }

                        if (!newTitle.isAValidFilename()) {
                            activity.toast(org.fossify.commons.R.string.invalid_name)
                            return@setOnClickListener
                        }

                        ensureBackgroundThread {
                            if (isRPlus()) {
                                updateMediaStoreTitle(recording, newTitle)
                            } else {
                                updateLegacyFilename(recording, newTitle)
                            }

                            activity.runOnUiThread {
                                callback()
                                alertDialog.dismiss()
                            }
                        }
                    }
                }
            }
    }

    private fun updateMediaStoreTitle(recording: Recording, newTitle: String) {
        val oldExtension = recording.title.getFilenameExtension()
        val newDisplayName = "${newTitle.removeSuffix(".$oldExtension")}.$oldExtension"

        val values = ContentValues().apply {
            put(Media.TITLE, newTitle.substringAfterLast('.'))
            put(Media.DISPLAY_NAME, newDisplayName)
        }

        // if the old way of renaming fails, try the new SDK 30 one on Android 11+
        try {
            activity.contentResolver.update(getAudioFileContentUri(recording.id.toLong()), values, null, null)
        } catch (e: Exception) {
            try {
                val path = "${activity.config.saveRecordingsFolder}/${recording.title}"
                val newPath = "${path.getParentPath()}/$newDisplayName"
                activity.handleSAFDialogSdk30(path) {
                    val success = activity.renameDocumentSdk30(path, newPath)
                    if (success) {
                        EventBus.getDefault().post(Events.RecordingCompleted())
                    }
                }
            } catch (e: Exception) {
                activity.showErrorToast(e)
            }
        }
    }

    private fun updateLegacyFilename(recording: Recording, newTitle: String) {
        val oldExtension = recording.title.getFilenameExtension()
        val oldPath = recording.path
        val newFilename = "${newTitle.removeSuffix(".$oldExtension")}.$oldExtension"
        val newPath = File(oldPath.getParentPath(), newFilename).absolutePath
        activity.renameFile(oldPath, newPath, false)
    }
}
