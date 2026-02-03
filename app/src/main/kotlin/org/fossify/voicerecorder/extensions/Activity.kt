package org.fossify.voicerecorder.extensions

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.WindowManager
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.*
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SettingsActivity

fun Activity.setKeepScreenAwake(keepScreenOn: Boolean) {
    if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

fun BaseSimpleActivity.deleteExpiredTrashedRecordings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        if (!hasPermission(PERMISSION_READ_STORAGE) || !hasPermission(PERMISSION_WRITE_STORAGE)) {
            return
        }
    }

    if (config.useRecycleBin && config.lastRecycleBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000) {
        config.lastRecycleBinCheck = System.currentTimeMillis()
        ensureBackgroundThread {
            try {
                val store = recordingStore
                val recordingsToRemove = store.all(trashed = true).filter { it.timestamp < System.currentTimeMillis() - MONTH_SECONDS * 1000L }.toList()
                if (recordingsToRemove.isNotEmpty()) {
                    store.delete(recordingsToRemove)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

fun BaseSimpleActivity.handleRecordingStoreError(exception: Exception) {
    Log.w(this::class.simpleName, "recording store error", exception)

    // TODO: invoke the intent at [exception.userAction] then handle its result
    // if (exception is AuthenticationRequiredException) {
    //     TODO()
    //     return
    // }

    runOnUiThread {
        getAlertDialogBuilder().setTitle(getString(R.string.recording_store_error_title))
            .setMessage(getString(R.string.recording_store_error_message))
            .setPositiveButton(org.fossify.commons.R.string.go_to_settings) { _, _ ->
                startActivity(Intent(applicationContext, SettingsActivity::class.java).apply {
                    putExtra(SettingsActivity.EXTRA_FOCUS_SAVE_RECORDINGS_FOLDER, true)
                })
            }.setNegativeButton(org.fossify.commons.R.string.cancel, null).create().show()
    }
}
