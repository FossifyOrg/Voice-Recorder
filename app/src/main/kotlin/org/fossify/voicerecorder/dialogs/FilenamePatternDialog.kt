package org.fossify.voicerecorder.dialogs

import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.viewBinding
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.databinding.DialogFilenamePatternBinding
import org.fossify.voicerecorder.extensions.config

class FilenamePatternDialog(private val activity: SimpleActivity, private val callback: (String) -> Unit) {
    private val binding by activity.viewBinding(DialogFilenamePatternBinding::inflate)
    private val config = activity.config
    private var dialog: AlertDialog? = null

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

            filenamePatternValue.doAfterTextChanged { validatePattern(it?.toString().orEmpty().trim()) }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.filename_pattern) { alertDialog ->
                    dialog = alertDialog
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newPattern = binding.filenamePatternValue.text.toString().trim()
                        config.filenamePattern = newPattern
                        callback(newPattern)
                        alertDialog.dismiss()
                    }
                }
            }
    }

    private fun validatePattern(pattern: String) {
        binding.filenamePatternHint.error = when {
            pattern.isEmpty() -> activity.getString(org.fossify.commons.R.string.filename_cannot_be_empty)
            !pattern.isAValidFilename() -> activity.getString(org.fossify.commons.R.string.invalid_name)
            !containsAllDateTimePlaceholders(pattern) -> activity.getString(R.string.filename_pattern_must_contain_all)
            else -> null
        }
        val isValid = binding.filenamePatternHint.error.isNullOrEmpty()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = isValid
    }

    private fun containsAllDateTimePlaceholders(pattern: String): Boolean {
        return pattern.contains("%Y") && pattern.contains("%M") && pattern.contains("%D") &&
                pattern.contains("%h") && pattern.contains("%m") && pattern.contains("%s")
    }
}
