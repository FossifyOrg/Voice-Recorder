package org.fossify.voicerecorder.helpers

import android.content.Context
import android.content.pm.ProviderInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.extensions.isAudioRecording
import org.fossify.voicerecorder.models.Recording
import org.fossify.voicerecorder.models.RecordingFormat
import kotlin.math.roundToLong

/**
 * Utility to manage stored recordings
 */
class RecordingStore(private val context: Context, val uri: Uri) {
    companion object {
        private const val TAG = "RecordingStore"
    }

    enum class Kind {
        DOCUMENT, MEDIA;

        companion object {
            fun of(uri: Uri): Kind = if (uri.authority == MediaStore.AUTHORITY) {
                Kind.MEDIA
            } else {
                Kind.DOCUMENT
            }
        }
    }

    /**
     * Short, human-readable name of this store
     */
    val shortName: String
        get() = when (kind) {
            Kind.DOCUMENT -> {
                val documentId = DocumentsContract.getTreeDocumentId(uri)
                documentId.substringAfter(":").trimEnd('/')
            }

            Kind.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Environment.DIRECTORY_RECORDINGS
                } else {
                    DEFAULT_RECORDINGS_FOLDER
                }
            }
        }

    /**
     * Get the [ProviderInfo] for the content provider backing this store
     */
    val providerInfo: ProviderInfo? = uri.authority?.let {
        context.packageManager.resolveContentProvider(it, 0)
    }

    /**
     *  Are there any recordings in this store?
     */
    fun isNotEmpty(): Boolean = when (kind) {
        Kind.DOCUMENT -> DocumentFile.fromTreeUri(context, uri)?.listFiles()?.any { it.isAudioRecording() } == true
        Kind.MEDIA -> false // TODO
    }

    /**
     * Returns all recordings in this store.
     */
    fun getAll(trashed: Boolean = false): List<Recording> = when (kind) {
        Kind.DOCUMENT -> {
            val parentUri = if (trashed) {
                trashFolder
            } else {
                uri
            }

            parentUri?.let { DocumentFile.fromTreeUri(context, it) }?.listFiles()?.filter { it.isAudioRecording() }?.map { readRecordingFromFile(it) }?.toList()
                ?: emptyList()
        }

        Kind.MEDIA -> {
            // TODO
            emptyList()
        }
    }

    fun trash(
        recordings: Collection<Recording>, callback: (success: Boolean) -> Unit
    ) = move(
        recordings, uri, getOrCreateTrashFolder()!!, callback
    )

    fun restore(
        recordings: Collection<Recording>, callback: (success: Boolean) -> Unit
    ) {
        val sourceParent = trashFolder
        if (sourceParent == null) {
            callback(true)
            return
        }

        move(
            recordings, sourceParent, uri, callback
        )
    }

    fun deleteTrashed(
        callback: (success: Boolean) -> Unit = {}
    ) = delete(getAll(trashed = true), callback)

    fun move(
        recordings: Collection<Recording>, sourceParent: Uri, targetParent: Uri, callback: (success: Boolean) -> Unit
    ) = ensureBackgroundThread {
        val contentResolver = context.contentResolver
        val sourceParentDocumentUri = ensureParentDocumentUri(context, sourceParent)
        val targetParentDocumentUri = ensureParentDocumentUri(context, targetParent)

        Log.d(TAG, "move src: $sourceParent -> $sourceParentDocumentUri, dst: $targetParent -> $targetParentDocumentUri")

        if (sourceParent.authority == targetParent.authority) {

            for (recording in recordings) {
                try {
                    // TODO: convert to document URI only if not already document URI

                    DocumentsContract.moveDocument(
                        contentResolver, recording.uri, sourceParentDocumentUri, targetParentDocumentUri
                    )
                } catch (@Suppress("SwallowedException") e: IllegalStateException) {
                    moveFallback(recording.uri, targetParentDocumentUri)
                }
            }
        } else {
            for (recording in recordings) {
                moveFallback(recording.uri, targetParentDocumentUri)
            }
        }

        callback(true)
    }

    fun delete(
        recordings: Collection<Recording>, callback: (success: Boolean) -> Unit = {}
    ) = ensureBackgroundThread {
        when (kind) {
            Kind.DOCUMENT -> {
                val resolver = context.contentResolver
                recordings.forEach {
                    DocumentsContract.deleteDocument(resolver, it.uri)
                }
            }

            Kind.MEDIA -> {
                TODO()
            }
        }

        callback(true)
    }

    fun createWriter(name: String, format: RecordingFormat): RecordingWriter = RecordingWriter.create(context, uri, name, format)

    private val kind: Kind = Kind.of(uri)

    private val trashFolder: Uri?
        get() = findChildDocument(context.contentResolver, uri, TRASH_FOLDER_NAME)

    private fun getOrCreateTrashFolder(): Uri? = when (kind) {
        Kind.DOCUMENT -> getOrCreateDocument(
            context.contentResolver, uri, DocumentsContract.Document.MIME_TYPE_DIR, TRASH_FOLDER_NAME
        )

        Kind.MEDIA -> null
    }

    private fun readRecordingFromFile(file: DocumentFile): Recording = Recording(
        id = file.hashCode(),
        title = file.name!!,
        uri = file.uri,
        timestamp = file.lastModified(),
        duration = getDurationFromUri(file.uri).toInt(),
        size = file.length().toInt()
    )

    private fun getDurationFromUri(uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
            (time.toLong() / 1000.toDouble()).roundToLong()
        } catch (_: Exception) {
            0L
        }
    }

    // Copy source to target, then delete source. Use as fallback when `DocumentsContract.moveDocument` can't used (e.g., when moving between different authorities)
    private fun moveFallback(
        sourceUri: Uri,
        targetParentUri: Uri,
    ) {
        Log.d(TAG, "moveFallback: src:$sourceUri dst:$targetParentUri")

        val contentResolver = context.contentResolver

        // TODO: media

        val sourceFile = DocumentFile.fromSingleUri(context, sourceUri)!!
        val sourceName = requireNotNull(sourceFile.name)
        val sourceType = requireNotNull(sourceFile.type)

        val targetUri = requireNotNull(
            DocumentsContract.createDocument(
                contentResolver, targetParentUri, sourceType, sourceName
            )
        )

        contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        DocumentsContract.deleteDocument(contentResolver, sourceUri)
    }
}

private const val TRASH_FOLDER_NAME = ".trash"

private fun ensureParentDocumentUri(context: Context, uri: Uri): Uri = when {
    DocumentsContract.isDocumentUri(context, uri) -> uri
    DocumentsContract.isTreeUri(uri) -> buildParentDocumentUri(uri)
    else -> error("invalid URI, must be document or tree: $uri")
}


//@Deprecated(
//    message = "Use getRecordings instead. This method is only here for backward compatibility.", replaceWith = ReplaceWith("getRecordings(trashed = true)")
//)
//private fun Context.getMediaStoreTrashedRecordings(): List<Recording> {
//    val trashedRegex = "^\\.trashed-\\d+-".toRegex()
//
//    return config.saveRecordingsFolder?.let { DocumentFile.fromTreeUri(this, it) }?.listFiles()?.filter { it.isTrashedMediaStoreRecording() }?.map {
//            readRecordingFromFile(it).copy(title = trashedRegex.replace(it.name!!, ""))
//        }?.toList() ?: emptyList()
//}
