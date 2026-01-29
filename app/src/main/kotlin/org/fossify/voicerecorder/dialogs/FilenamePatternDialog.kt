package org.fossify.voicerecorder.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.databinding.DialogFilenamePatternBinding
import org.fossify.voicerecorder.extensions.config

class FilenamePatternDialog(private val activity: SimpleActivity, private val callback: (String) -> Unit) {
    private val binding by activity.viewBinding(DialogFilenamePatternBinding::inflate)
    private val config = activity.config

    init {
        binding.apply {
            filenamePatternValue.setText(config.filenamePattern)
            filenamePatternHint.setEndIconOnClickListener {
                DateTimePatternInfoDialog(activity)
            }
            filenamePatternHint.setEndIconOnLongClickListener {
                DateTimePatternInfoDialog(activity)
                true
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.filename_pattern) { dialog ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newPattern = binding.filenamePatternValue.text.toString()
                        if (newPattern.isEmpty()) {
                            activity.toast(org.fossify.commons.R.string.empty_name)
                            return@setOnClickListener
                        }

                        config.filenamePattern = newPattern
                        callback(newPattern)
                        dialog.dismiss()
                    }
                }
            }
    }
}
