package org.fossify.voicerecorder.activities

import android.content.Intent
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.voicerecorder.helpers.TOGGLE_RECORD
import org.fossify.voicerecorder.services.RecorderService

class ToggleRecordActivity : SimpleActivity() {
    override fun onResume() {
        super.onResume()
        if (intent.action == TOGGLE_RECORD) {
            handleNotificationPermission { granted ->
                if (granted) {
                    Intent(this@ToggleRecordActivity, RecorderService::class.java).apply {
                        try {
                            if (RecorderService.isRunning) {
                                stopService(this)
                            } else {
                                startService(this)
                            }
                        } catch (ignored: Exception) {
                        }
                    }
                } else {
                    PermissionRequiredDialog(
                        activity = this,
                        textId = org.fossify.commons.R.string.allow_notifications_voice_recorder,
                        positiveActionCallback = { openNotificationSettings() }
                    )
                }
            }
        }
        moveTaskToBack(true)
        finish()
    }
}
