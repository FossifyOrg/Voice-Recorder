package org.fossify.voicerecorder.store

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.helpers.ensureBackgroundThread
import kotlin.math.roundToLong

/**
 * Utility to manage stored recordings
 */
class RecordingStore(private val context: Context, val uri: Uri) {
    companion object {
        private const val TAG = "RecordingStore"
    }

    init {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Invalid URI '$uri' - must have 'content' scheme" }
    }

    enum class Kind {
        DOCUMENT, MEDIA;

        companion object {
            fun of(uri: Uri): Kind = if (uri.authority == MediaStore.AUTHORITY) {
                MEDIA
            } else {
                DOCUMENT
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

            Kind.MEDIA -> DEFAULT_RECORDINGS_FOLDER
        }

    /**
     * Get the [ProviderInfo] for the content provider backing this store
     */
    val providerInfo: ProviderInfo? = uri.authority?.let {
        context.packageManager.resolveContentProvider(it, 0)
    }

    /**
     *  Are there no recordings in this store?
     */
    fun isEmpty(): Boolean = when (kind) {
        Kind.DOCUMENT -> DocumentFile.fromTreeUri(context, uri)?.listFiles()?.any { it.isAudioRecording() } != true
        Kind.MEDIA -> true
    }

    /**
     *  Are there any recordings in this store?
     */
    fun isNotEmpty(): Boolean = !isEmpty()

    /**
     * Returns all recordings in this store.
     */
    fun getAll(trashed: Boolean = false): List<Recording> = when (kind) {
        Kind.DOCUMENT -> getAllDocuments(trashed)
        Kind.MEDIA -> getAllMedia(trashed)
    }

    private fun getAllDocuments(trashed: Boolean): List<Recording> {
        val parentUri = if (trashed) {
            trashFolder
        } else {
            uri
        }

        return parentUri?.let { DocumentFile.fromTreeUri(context, it) }?.listFiles()?.filter { it.isAudioRecording() }?.map { readRecordingFromFile(it) }
            ?.toList() ?: emptyList()
    }

    private fun getAllMedia(trashed: Boolean): List<Recording> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )


        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val queryArgs = if (trashed) {
                Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                }
            } else {
                null
            }

            context.contentResolver.query(uri, projection, queryArgs, null)
        } else {
            val selection: String
            val selectionArgs: Array<String>

            if (trashed) {
                selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
                selectionArgs = arrayOf("$TRASHED_PREFIX%")
            } else {
                selection = "${MediaStore.Audio.Media.DISPLAY_NAME} NOT LIKE ?"
                selectionArgs = arrayOf("$TRASHED_PREFIX%")
            }

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        }

        val result = mutableListOf<Recording>()

        cursor?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val timestampIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)
                val size = cursor.getInt(sizeIndex)
                val timestamp = cursor.getLong(timestampIndex)
                val duration = cursor.getInt(durationIndex)

                val rowUri = ContentUris.withAppendedId(uri, id)

                result.add(
                    Recording(
                        id = id.toInt(),
                        title = name,
                        uri = rowUri,
                        timestamp = timestamp,
                        duration = duration,
                        size = size,
                    )
                )
            }
        }

        return result
    }

    fun trash(recordings: Collection<Recording>): Boolean {
        val (documents, media) = recordings.partition { Kind.of(it.uri) == Kind.DOCUMENT }
        var success = true

        if (documents.isNotEmpty()) {
            success = success and moveDocuments(documents, uri, getOrCreateTrashFolder()!!)
        }

        if (media.isNotEmpty()) {
            success = success and updateMediaTrashed(media, trash = true)
        }

        return success
    }

    fun restore(recordings: Collection<Recording>): Boolean {
        val (documents, media) = recordings.partition { Kind.of(it.uri) == Kind.DOCUMENT }
        var success = true

        if (documents.isNotEmpty()) {
            trashFolder?.let { sourceParent ->
                success = success and move(recordings, sourceParent, uri)
            }
        }

        if (media.isNotEmpty()) {
            success = success and updateMediaTrashed(media, trash = false)
        }

        return success
    }

    private fun updateMediaTrashed(recordings: Collection<Recording>, trash: Boolean): Boolean {
        val contentResolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_TRASHED, if (trash) 1 else 0)
            }

            for (recording in recordings) {
                contentResolver.update(recording.uri, values, null, null)
            }
        } else {
            for (recording in recordings) {
                val newName = if (trash) {
                    "${TRASHED_PREFIX}${recording.title}"
                } else {
                    recording.title.removePrefix(TRASHED_PREFIX)
                }

                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, newName)
                }

                contentResolver.update(recording.uri, values, null, null)
            }
        }

        return true
    }

    fun deleteTrashed(
        callback: (success: Boolean) -> Unit = {}
    ) = ensureBackgroundThread { callback(delete(getAll(trashed = true))) }

    fun move(recordings: Collection<Recording>, sourceParent: Uri, targetParent: Uri):Boolean {
        // TODO: handle media
        return moveDocuments(recordings, sourceParent, targetParent)
    }

    private fun moveDocuments(recordings: Collection<Recording>, sourceParent: Uri, targetParent: Uri): Boolean {
        val contentResolver = context.contentResolver
        val sourceParentDocumentUri = ensureParentDocumentUri(context, sourceParent)
        val targetParentDocumentUri = ensureParentDocumentUri(context, targetParent)

        if (sourceParent.authority == targetParent.authority) {

            for (recording in recordings) {
                try {
                    // TODO: convert to document URI only if not already document URI

                    DocumentsContract.moveDocument(
                        contentResolver, recording.uri, sourceParentDocumentUri, targetParentDocumentUri
                    )
                } catch (@Suppress("SwallowedException") _: IllegalStateException) {
                    moveFallback(recording.uri, targetParentDocumentUri)
                }
            }
        } else {
            for (recording in recordings) {
                moveFallback(recording.uri, targetParentDocumentUri)
            }
        }

        return true
    }

    fun delete(recordings: Collection<Recording>): Boolean {
        val resolver = context.contentResolver

        recordings.forEach {
            resolver.delete(it.uri, null, null)
//            when (Kind.of(it.uri)) {
//                Kind.DOCUMENT -> DocumentsContract.deleteDocument(resolver, it.uri)
//                Kind.MEDIA -> resolver.delete(it.uri, null, null)
//            }
        }

        return true
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
private const val TRASHED_PREFIX = ".trashed-"

private fun ensureParentDocumentUri(context: Context, uri: Uri): Uri = when {
    DocumentsContract.isDocumentUri(context, uri) -> uri
    DocumentsContract.isTreeUri(uri) -> buildParentDocumentUri(uri)
    else -> error("invalid URI, must be document or tree: $uri")
}

internal fun DocumentFile.isAudioRecording() = type.let { it != null && it.startsWith("audio") } && name.let { it != null && !it.startsWith(".") }

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
