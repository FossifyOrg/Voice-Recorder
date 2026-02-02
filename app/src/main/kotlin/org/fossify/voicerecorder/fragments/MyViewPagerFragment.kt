package org.fossify.voicerecorder.fragments

import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.activities.ExternalStoragePermission
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.extensions.recordingStore
import org.fossify.voicerecorder.store.Recording

abstract class MyViewPagerFragment(context: Context, attributeSet: AttributeSet) : ConstraintLayout(context, attributeSet) {
    abstract fun onResume()

    abstract fun onDestroy()

    open fun onLoadingStart() {}

    open fun onLoadingEnd(recordings: ArrayList<Recording>) {}

    open fun loadRecordings(trashed: Boolean = false) {
        onLoadingStart()

        (context as? SimpleActivity)?.apply {
            handleExternalStoragePermission(ExternalStoragePermission.READ) { granted ->
                if (granted == true) {
                    ensureBackgroundThread {
                        val recordings = try {
                            recordingStore.all(trashed).sortedByDescending { it.timestamp }.toCollection(ArrayList())
                        } catch (_: SecurityException) {
                            // The access to the store has been revoked.
                            // TODO: show an error dialog
                            ArrayList()
                        }

                        runOnUiThread {
                            onLoadingEnd(recordings)
                        }
                    }
                } else {
                    onLoadingEnd(ArrayList())
                }
            }
        }
    }
}

