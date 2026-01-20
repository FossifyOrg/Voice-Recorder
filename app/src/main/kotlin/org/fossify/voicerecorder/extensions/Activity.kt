package org.fossify.voicerecorder.extensions

import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.helpers.buildParentDocumentUri
import org.fossify.voicerecorder.models.Recording

fun Activity.setKeepScreenAwake(keepScreenOn: Boolean) {
    if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

fun BaseSimpleActivity.deleteRecordings(
    recordingsToRemove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        val resolver = contentResolver
        recordingsToRemove.forEach {
            DocumentsContract.deleteDocument(resolver, it.path.toUri())
        }

        callback(true)
    }
}

fun BaseSimpleActivity.trashRecordings(
    recordingsToMove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToMove,
    sourceParent = config.saveRecordingsFolder?.let(::buildParentDocumentUri)!!,
    targetParent = getOrCreateTrashFolder()!!,
    callback = callback
)

fun BaseSimpleActivity.restoreRecordings(
    recordingsToRestore: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToRestore,
    sourceParent = getOrCreateTrashFolder()!!,
    targetParent = config.saveRecordingsFolder?.let(::buildParentDocumentUri)!!,
    callback = callback
)

fun BaseSimpleActivity.moveRecordings(
    recordingsToMove: Collection<Recording>,
    sourceParent: Uri,
    targetParent: Uri,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        val contentResolver = contentResolver

        if (sourceParent.authority == targetParent.authority) {
            for (recording in recordingsToMove) {
                try {
                    DocumentsContract.moveDocument(
                        contentResolver,
                        recording.path.toUri(),
                        sourceParent,
                        targetParent
                    )
                } catch (e: IllegalStateException) {
                    moveDocumentFallback(recording.path.toUri(), sourceParent, targetParent)
                }
            }
        } else {
            for (recording in recordingsToMove) {
                moveDocumentFallback(recording.path.toUri(), sourceParent, targetParent)
            }
        }

        callback(true)
    }
}

// Copy source to target, then delete source. Use as fallback when `DocumentsContract.moveDocument` can't used (e.g., when moving between different authorities)
private fun BaseSimpleActivity.moveDocumentFallback(
    sourceUri: Uri,
    sourceParent: Uri,
    targetParent: Uri,
) {
    val sourceFile = DocumentFile.fromSingleUri(this, sourceUri)!!
    val sourceName = requireNotNull(sourceFile.name)
    val sourceType = requireNotNull(sourceFile.type)

    val targetUri = requireNotNull(DocumentsContract.createDocument(
        contentResolver,
        targetParent,
        sourceType,
        sourceName
    ))

    contentResolver.openInputStream(sourceUri)?.use { inputStream ->
        contentResolver.openOutputStream(targetUri)?.use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    }

    DocumentsContract.deleteDocument(contentResolver, sourceUri)
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
