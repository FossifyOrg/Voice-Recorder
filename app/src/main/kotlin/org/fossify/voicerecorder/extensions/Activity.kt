package org.fossify.voicerecorder.extensions

import android.content.ContentValues
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.deleteFile
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import org.fossify.voicerecorder.helpers.getAudioFileContentUri
import org.fossify.voicerecorder.models.Recording
import java.io.File

fun BaseSimpleActivity.deleteRecordings(
    recordingsToRemove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) {
    when {
        isRPlus() -> {
            val fileUris = recordingsToRemove.map { recording ->
                getAudioFileContentUri(recording.id.toLong())
            }

            deleteSDK30Uris(fileUris, callback)
        }

        isQPlus() -> {
            recordingsToRemove.forEach {
                val uri = Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val selection = "${Media._ID} = ?"
                val selectionArgs = arrayOf(it.id.toString())
                val result = contentResolver.delete(uri, selection, selectionArgs)

                if (result == 0) {
                    val fileDirItem = File(it.path).toFileDirItem(this)
                    deleteFile(fileDirItem)
                }
            }
            callback(true)
        }

        else -> {
            recordingsToRemove.forEach {
                val fileDirItem = File(it.path).toFileDirItem(this)
                deleteFile(fileDirItem)
            }
            callback(true)
        }
    }
}

fun BaseSimpleActivity.restoreRecordings(
    recordingsToRestore: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) {
    when {
        isRPlus() -> {
            val fileUris = recordingsToRestore.map { recording ->
                getAudioFileContentUri(recording.id.toLong())
            }

            trashSDK30Uris(fileUris, false, callback)
        }

        isQPlus() -> {
            var wait = false
            recordingsToRestore.forEach {
                val uri = Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val selection = "${Media._ID} = ?"
                val selectionArgs = arrayOf(it.id.toString())
                val values = ContentValues().apply {
                    put(Media.IS_TRASHED, 0)
                }
                val result = contentResolver.update(uri, values, selection, selectionArgs)

                if (result == 0) {
                    wait = true
                    copyMoveFilesTo(
                        fileDirItems = arrayListOf(File(it.path).toFileDirItem(this)),
                        source = it.path.getParentPath(),
                        destination = config.saveRecordingsFolder,
                        isCopyOperation = false,
                        copyPhotoVideoOnly = false,
                        copyHidden = false
                    ) {
                        callback(true)
                    }
                }
            }
            if (!wait) {
                callback(true)
            }
        }

        else -> {
            copyMoveFilesTo(
                fileDirItems = recordingsToRestore
                    .map { File(it.path).toFileDirItem(this) }
                    .toMutableList() as ArrayList<FileDirItem>,
                source = recordingsToRestore.first().path.getParentPath(),
                destination = config.saveRecordingsFolder,
                isCopyOperation = false,
                copyPhotoVideoOnly = false,
                copyHidden = false
            ) {
                callback(true)
            }
        }
    }
}

fun BaseSimpleActivity.moveRecordingsToRecycleBin(
    recordingsToMove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) {
    when {
        isRPlus() -> {
            val fileUris = recordingsToMove.map { recording ->
                getAudioFileContentUri(recording.id.toLong())
            }

            trashSDK30Uris(fileUris, true, callback)
        }

        isQPlus() -> {
            var wait = false
            recordingsToMove.forEach {
                val uri = Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val selection = "${Media._ID} = ?"
                val selectionArgs = arrayOf(it.id.toString())
                val values = ContentValues().apply {
                    put(Media.IS_TRASHED, 1)
                }
                val result = contentResolver.update(uri, values, selection, selectionArgs)

                if (result == 0) {
                    wait = true
                    copyMoveFilesTo(
                        fileDirItems = arrayListOf(File(it.path).toFileDirItem(this)),
                        source = it.path.getParentPath(),
                        destination = getOrCreateTrashFolder(),
                        isCopyOperation = false,
                        copyPhotoVideoOnly = false,
                        copyHidden = false
                    ) {
                        callback(true)
                    }
                }
            }
            if (!wait) {
                callback(true)
            }
        }

        else -> {
            copyMoveFilesTo(
                fileDirItems = recordingsToMove
                    .map { File(it.path).toFileDirItem(this) }
                    .toMutableList() as ArrayList<FileDirItem>,
                source = recordingsToMove.first().path.getParentPath(),
                destination = getOrCreateTrashFolder(),
                isCopyOperation = false,
                copyPhotoVideoOnly = false,
                copyHidden = false
            ) {
                callback(true)
            }
        }
    }
}

fun BaseSimpleActivity.checkRecycleBinItems() {
    if (isQPlus()) {
        // System is handling recycle bin on Q+ devices
        return
    }

    if (
        config.useRecycleBin &&
        config.lastRecycleBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000
    ) {
        config.lastRecycleBinCheck = System.currentTimeMillis()
        ensureBackgroundThread {
            try {
                val recordingsToRemove = getLegacyRecordings(trashed = true)
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

fun BaseSimpleActivity.emptyTheRecycleBin() {
    deleteRecordings(getAllRecordings(trashed = true)) {}
}
