package org.fossify.voicerecorder.extensions

import android.provider.DocumentsContract
import androidx.core.net.toUri
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.createDocumentUriUsingFirstParentTreeUri
import org.fossify.commons.extensions.createSAFFileSdk30
import org.fossify.commons.extensions.deleteFile
import org.fossify.commons.extensions.hasProperStoredFirstParentUri
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import org.fossify.voicerecorder.dialogs.StoragePermissionDialog
import org.fossify.voicerecorder.models.Recording
import java.io.File

fun BaseSimpleActivity.ensureStoragePermission(callback: (result: Boolean) -> Unit) {
    if (isRPlus() && !hasProperStoredFirstParentUri(config.saveRecordingsFolder)) {
        StoragePermissionDialog(this) {
            launchFolderPicker(config.saveRecordingsFolder) { newPath ->
                if (!newPath.isNullOrEmpty()) {
                    config.saveRecordingsFolder = newPath
                    callback(true)
                } else {
                    callback(false)
                }
            }
        }
    } else {
        callback(true)
    }
}

fun BaseSimpleActivity.launchFolderPicker(
    currentPath: String,
    callback: (newPath: String?) -> Unit
) {
    FilePickerDialog(
        activity = this,
        currPath = currentPath,
        pickFile = false,
        showFAB = true,
        showRationale = false
    ) { path ->
        handleSAFDialog(path) { grantedSAF ->
            if (!grantedSAF) {
                callback(null)
                return@handleSAFDialog
            }

            handleSAFDialogSdk30(path, showRationale = false) { grantedSAF30 ->
                if (!grantedSAF30) {
                    callback(null)
                    return@handleSAFDialogSdk30
                }

                callback(path)
            }
        }
    }
}

fun BaseSimpleActivity.deleteRecordings(
    recordingsToRemove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        if (isRPlus()) {
            val resolver = contentResolver
            recordingsToRemove.forEach {
                DocumentsContract.deleteDocument(resolver, it.path.toUri())
            }
        } else {
            recordingsToRemove.forEach {
                val fileDirItem = File(it.path).toFileDirItem(this)
                deleteFile(fileDirItem)
            }
        }

        callback(true)
    }
}

fun BaseSimpleActivity.trashRecordings(
    recordingsToMove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToMove,
    sourceParent = config.saveRecordingsFolder,
    destinationParent = getOrCreateTrashFolder(),
    callback = callback
)

fun BaseSimpleActivity.restoreRecordings(
    recordingsToRestore: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToRestore,
    sourceParent = getOrCreateTrashFolder(),
    destinationParent = config.saveRecordingsFolder,
    callback = callback
)

fun BaseSimpleActivity.moveRecordings(
    recordingsToMove: Collection<Recording>,
    sourceParent: String,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    if (isRPlus()) {
        moveRecordingsSAF(
            recordings = recordingsToMove,
            sourceParent = sourceParent,
            destinationParent = destinationParent,
            callback = callback
        )
    } else {
        moveRecordingsLegacy(
            recordings = recordingsToMove,
            sourceParent = sourceParent,
            destinationParent = destinationParent,
            callback = callback
        )
    }
}

private fun BaseSimpleActivity.moveRecordingsSAF(
    recordings: Collection<Recording>,
    sourceParent: String,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        val contentResolver = contentResolver
        val sourceParentDocumentUri = createDocumentUriUsingFirstParentTreeUri(sourceParent)
        val destinationParentDocumentUri =
            createDocumentUriUsingFirstParentTreeUri(destinationParent)
        recordings.forEach { recording ->
            DocumentsContract.moveDocument(
                contentResolver,
                recording.path.toUri(),
                sourceParentDocumentUri,
                destinationParentDocumentUri
            )
        }

        callback(true)
    }
}

private fun BaseSimpleActivity.moveRecordingsLegacy(
    recordings: Collection<Recording>,
    sourceParent: String,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    copyMoveFilesTo(
        fileDirItems = recordings
            .map { File(it.path).toFileDirItem(this) }
            .toMutableList() as ArrayList<FileDirItem>,
        source = sourceParent,
        destination = destinationParent,
        isCopyOperation = false,
        copyPhotoVideoOnly = false,
        copyHidden = false
    ) {
        callback(true)
    }
}

fun BaseSimpleActivity.deleteTrashedRecordings() {
    deleteRecordings(getAllRecordings(trashed = true)) {}
}

fun BaseSimpleActivity.deleteExpiredTrashedRecordings() {
    if (
        config.useRecycleBin &&
        config.lastRecycleBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000
    ) {
        config.lastRecycleBinCheck = System.currentTimeMillis()
        ensureBackgroundThread {
            try {
                val recordingsToRemove = getAllRecordings(trashed = true)
                    .filter { it.timestamp < System.currentTimeMillis() - MONTH_SECONDS * 1000L }
                if (recordingsToRemove.isNotEmpty()) {
                    deleteRecordings(recordingsToRemove) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
