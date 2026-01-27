package org.fossify.voicerecorder.extensions

import android.app.Activity
import android.view.WindowManager
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread

fun Activity.setKeepScreenAwake(keepScreenOn: Boolean) {
    if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

fun BaseSimpleActivity.deleteExpiredTrashedRecordings() {
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
