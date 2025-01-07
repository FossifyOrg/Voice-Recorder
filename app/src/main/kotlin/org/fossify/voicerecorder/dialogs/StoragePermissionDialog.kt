package org.fossify.voicerecorder.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.databinding.DialogMessageBinding
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.voicerecorder.R

class StoragePermissionDialog(
    private val activity: BaseSimpleActivity,
    private val callback: (result: Boolean) -> Unit
) {
    private var dialog: AlertDialog? = null

    init {
        val view = DialogMessageBinding.inflate(activity.layoutInflater, null, false)
        view.message.text = activity.getString(R.string.confirm_recording_folder)

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                callback(true)
            }
            .apply {
                activity.setupDialogStuff(
                    view = view.root,
                    dialog = this,
                    cancelOnTouchOutside = false,
                ) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }
}
