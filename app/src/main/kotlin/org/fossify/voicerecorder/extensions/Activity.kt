package org.fossify.voicerecorder.extensions

import android.provider.DocumentsContract
import androidx.core.net.toUri
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.createDocumentUriUsingFirstParentTreeUri
import org.fossify.commons.extensions.deleteFile
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFilenameExtension
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.hasProperStoredFirstParentUri
import org.fossify.commons.extensions.renameDocumentSdk30
import org.fossify.commons.extensions.renameFile
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import org.fossify.voicerecorder.dialogs.StoragePermissionDialog
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.Recording
import org.greenrobot.eventbus.EventBus
import java.io.File

fun BaseSimpleActivity.ensureStoragePermission(callback: (result: Boolean) -> Unit) {
    if (isRPlus() && !hasProperStoredFirstParentUri(config.saveRecordingsFolder)) {
        StoragePermissionDialog(this) {
            launchFilePickerDialog(callback)
        }
    } else {
        callback(true)
    }
}

fun BaseSimpleActivity.launchFilePickerDialog(callback: (success: Boolean) -> Unit) {
    FilePickerDialog(
        activity = this,
        currPath = config.saveRecordingsFolder,
        pickFile = false,
        showFAB = true
    ) { path ->
        handleSAFDialog(path) { grantedSAF ->
            if (!grantedSAF) {
                callback(false)
                return@handleSAFDialog
            }

            handleSAFDialogSdk30(path) { grantedSAF30 ->
                if (!grantedSAF30) {
                    callback(false)
                    return@handleSAFDialogSdk30
                }

                config.saveRecordingsFolder = path
                callback(true)
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
            if (getDoesFilePathExist(File(destinationParent, recording.title).absolutePath)) {
                renameRecording(recording, recording.title) { success ->
                    if (success) {
                        DocumentsContract.moveDocument(
                            contentResolver,
                            recording.path.toUri(),
                            sourceParentDocumentUri,
                            destinationParentDocumentUri
                        )
                    }
                }
            } else {
                DocumentsContract.moveDocument(
                    contentResolver,
                    recording.path.toUri(),
                    sourceParentDocumentUri,
                    destinationParentDocumentUri
                )
            }
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

private fun BaseSimpleActivity.renameRecording(
    recording: Recording,
    newTitle: String,
    callback: (success: Boolean) -> Unit
) {
    val oldExtension = recording.title.getFilenameExtension()
    val newDisplayName = "${newTitle.removeSuffix(".$oldExtension")}.$oldExtension"

    try {
        val path = "${config.saveRecordingsFolder}/${recording.title}"
        val newPath = "${path.getParentPath()}/$newDisplayName"
        handleSAFDialogSdk30(path) {
            val success = renameDocumentSdk30(path, newPath)
            if (success) {
                EventBus.getDefault().post(Events.RecordingCompleted())
            }
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

private fun BaseSimpleActivity.renameRecordingLegacy(recording: Recording, newTitle: String) {
    val oldExtension = recording.title.getFilenameExtension()
    val oldPath = recording.path
    val newFilename = "${newTitle.removeSuffix(".$oldExtension")}.$oldExtension"
    val newPath = File(oldPath.getParentPath(), newFilename).absolutePath
    renameFile(oldPath, newPath, false)
}