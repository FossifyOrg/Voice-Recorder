package org.fossify.voicerecorder.dialogs

import android.net.Uri
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.DialogMoveRecordingsBinding
import org.fossify.voicerecorder.extensions.handleRecordingStoreError
import org.fossify.voicerecorder.store.RecordingStore

class MoveRecordingsDialog(
    private val activity: BaseSimpleActivity, private val oldFolder: Uri, private val newFolder: Uri, private val callback: () -> Unit
) {
    private lateinit var dialog: AlertDialog
    private val binding = DialogMoveRecordingsBinding.inflate(activity.layoutInflater).apply {
        message.setText(R.string.move_recordings_to_new_folder_desc)
        progressIndicator.setIndicatorColor(activity.getProperPrimaryColor())
    }

    init {
        activity.getAlertDialogBuilder().setPositiveButton(org.fossify.commons.R.string.yes, null).setNegativeButton(org.fossify.commons.R.string.no, null)
            .apply {
                activity.setupDialogStuff(
                    view = binding.root, dialog = this, titleId = R.string.move_recordings
                ) {
                    dialog = it
                    dialog.setOnCancelListener { callback() }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        callback()
                        dialog.dismiss()
                    }

                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        binding.progressIndicator.show()
                        with(dialog) {
                            setCancelable(false)
                            setCanceledOnTouchOutside(false)
                            arrayOf(
                                binding.message, getButton(AlertDialog.BUTTON_POSITIVE), getButton(AlertDialog.BUTTON_NEGATIVE)
                            ).forEach { button ->
                                button.isEnabled = false
                                button.alpha = MEDIUM_ALPHA
                            }

                            moveAllRecordings()
                        }
                    }
                }
            }
    }

    private fun moveAllRecordings() = ensureBackgroundThread {
        RecordingStore(activity, oldFolder).let { store ->
            try {
                store.migrate(newFolder)
                activity.runOnUiThread { callback() }
            } catch (e: Exception) {
                activity.handleRecordingStoreError(e)
            } finally {
                dialog.dismiss()
            }
        }
    }
}
