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
    fun isEmpty(): Boolean = !isNotEmpty()

    /**
     *  Are there any recordings in this store?
     */
    fun isNotEmpty(): Boolean = all().any()

    /**
     * Returns all recordings in this store as sequence.
     */
    fun all(trashed: Boolean = false): Sequence<Recording> = when (kind) {
        Kind.DOCUMENT -> allDocuments(trashed)
        Kind.MEDIA -> allMedia(trashed)
    }

    private fun allDocuments(trashed: Boolean): Sequence<Recording> {
        val treeUri = if (trashed) {
            trashFolder
        } else {
            uri
        }

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )

        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childDocumentsUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)

        val contentResolver = context.contentResolver

        return sequence {
            contentResolver.query(childDocumentsUri, projection, null, null, null)?.use { cursor ->
                val iDocumentId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iDisplayName = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val iMimeType = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val iLastModified = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val iSize = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(iDocumentId)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    val mimeType = cursor.getString(iMimeType)
                    val displayName = cursor.getString(iDisplayName)

                    if (mimeType?.startsWith("audio") != true || displayName.startsWith(".")) {
                        continue
                    }

                    val duration = getDurationFromUri(uri).toInt()

                    yield(
                        Recording(
                            id = documentId.hashCode(),
                            title = displayName,
                            uri = uri,
                            timestamp = cursor.getLong(iLastModified),
                            duration = duration,
                            size = cursor.getInt(iSize),
                        )
                    )
                }
            }
        }
    }

    private fun allMedia(trashed: Boolean): Sequence<Recording> {
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

        return sequence {
            cursor?.use { cursor ->
                val iId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iDateModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val iDisplayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iSize = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(iId)
                    val name = cursor.getString(iDisplayName)
                    val size = cursor.getInt(iSize)
                    val timestamp = cursor.getLong(iDateModified)
                    val duration = cursor.getInt(iDuration)

                    val rowUri = ContentUris.withAppendedId(uri, id)

                    yield(
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
        }
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
    ) = ensureBackgroundThread { callback(delete(all(trashed = true).toList())) }

    fun move(recordings: Collection<Recording>, sourceParent: Uri, targetParent: Uri): Boolean {
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
            deleteFile(resolver, it.uri)
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

private enum class Kind {
    DOCUMENT, MEDIA;

    companion object {
        fun of(uri: Uri): Kind = if (uri.authority == MediaStore.AUTHORITY) {
            MEDIA
        } else {
            DOCUMENT
        }
    }
}

internal fun createFile(context: Context, parentUri: Uri, name: String, format: RecordingFormat): Uri {
    val uri = if (parentUri.authority == MediaStore.AUTHORITY) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, format.getMimeType(context))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, DEFAULT_RECORDINGS_FOLDER)
            }
        }

        context.contentResolver.insert(parentUri, values)
    } else {
        val parentDocumentUri = buildParentDocumentUri(parentUri)
        val displayName = "$name.${format.getExtension(context)}"

        DocumentsContract.createDocument(
            context.contentResolver,
            parentDocumentUri,
            format.getMimeType(context),
            displayName,
        )
    }

    return requireNotNull(uri) {
        "failed to create file '$name' in $parentUri"
    }
}

internal fun deleteFile(contentResolver: ContentResolver, uri: Uri) = when (Kind.of(uri)) {
    Kind.MEDIA -> contentResolver.delete(uri, null, null)
    Kind.DOCUMENT -> DocumentsContract.deleteDocument(contentResolver, uri)
}

private fun ensureParentDocumentUri(context: Context, uri: Uri): Uri = when {
    DocumentsContract.isDocumentUri(context, uri) -> uri
    DocumentsContract.isTreeUri(uri) -> buildParentDocumentUri(uri)
    else -> error("invalid URI, must be document or tree: $uri")
}

