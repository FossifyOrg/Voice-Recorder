package org.fossify.voicerecorder.extensions

import android.net.Uri
import android.provider.DocumentsContract

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_VOLUME_ID = "primary"

/**
 * Returns the UUID of the removable storage volume (USB drive, SD card) holding this folder tree,
 * or null if the folder is on internal storage or isn't provided by the system's external storage
 * provider.
 */
val Uri.removableVolumeId: String?
    get() {
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null

        val documentId = try {
            DocumentsContract.getTreeDocumentId(this)
        } catch (_: IllegalArgumentException) {
            return null
        }

        return documentId.substringBefore(':', "")
            .takeIf { it.isNotEmpty() && !it.equals(PRIMARY_VOLUME_ID, ignoreCase = true) }
    }
