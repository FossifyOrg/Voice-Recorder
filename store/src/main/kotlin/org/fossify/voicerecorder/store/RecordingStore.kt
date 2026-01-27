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
    fun isEmpty(): Boolean = all().none()

    /**
     * Returns all recordings in this store as sequence.
     */
    fun all(trashed: Boolean = false): Sequence<Recording> = when (kind) {
        Kind.DOCUMENT -> allDocuments(trashed)
        Kind.MEDIA -> allMedia(trashed)
    }

    private fun allDocuments(trashed: Boolean): Sequence<Recording> {
        val treeUri = if (trashed) {
            trashFolder ?: return emptySequence()
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

        val parentDocumentId = if (DocumentsContract.isDocumentUri(context, treeUri)) {
            DocumentsContract.getDocumentId(treeUri)
        } else {
            DocumentsContract.getTreeDocumentId(treeUri)
        }
        val childDocumentsUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)

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


                    val mimeType = cursor.getString(iMimeType) ?: continue
                    val displayName = cursor.getString(iDisplayName)

                    if (!mimeType.startsWith("audio") || displayName.startsWith(".")) {
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
                            mimeType = mimeType,
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
            MediaStore.Audio.Media.MIME_TYPE
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
                val iMimeType = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(iId)
                    val rowUri = ContentUris.withAppendedId(uri, id)
                    val mimeType = cursor.getString(iMimeType)

                    yield(
                        Recording(
                            id = id.toInt(),
//                            title = removeExtension(cursor.getString(iDisplayName), mimeType),
                            title = cursor.getString(iDisplayName),
                            uri = rowUri,
                            timestamp = cursor.getLong(iDateModified),
                            duration = cursor.getInt(iDuration),
                            size = cursor.getInt(iSize),
                            mimeType = mimeType
                        )
                    )
                }
            }
        }
    }

    fun trash(recordings: Collection<Recording>) = move(recordings, toTrash = true)

    fun restore(recordings: Collection<Recording>) = move(recordings, fromTrash = true)

    fun deleteTrashed(): Boolean = delete(all(trashed = true).toList())

    /**
     * Move all recordings in this store (including the trashed ones) into the new store.
     */
    fun migrate(dstUri: Uri) {
        if (dstUri == uri) {
            return
        }

        move(all(trashed = false).toList(), dstUri, fromTrash = false, toTrash = false)
        move(all(trashed = true).toList(), dstUri, fromTrash = true, toTrash = true)
    }

    private fun move(recordings: Collection<Recording>, dstUri: Uri? = null, fromTrash: Boolean = false, toTrash: Boolean = false) {
        if (recordings.isEmpty()) {
            return
        }

        val dstUri = dstUri ?: uri

        when (kind) {
            Kind.DOCUMENT -> when (Kind.of(dstUri)) {
                Kind.DOCUMENT -> moveDocumentsToDocuments(recordings, dstUri, fromTrash, toTrash)
                Kind.MEDIA -> moveDocumentsToMedia(recordings, dstUri, toTrash)
            }

            Kind.MEDIA -> when (Kind.of(dstUri)) {
                Kind.DOCUMENT -> moveMediaToDocuments(recordings, dstUri, toTrash)
                Kind.MEDIA -> moveMediaToMedia(recordings, dstUri, toTrash)
            }
        }
    }

    private fun moveDocumentsToDocuments(recordings: Collection<Recording>, dstUri: Uri, fromTrash: Boolean, toTrash: Boolean) {
        val contentResolver = context.contentResolver

        val srcParentUri = if (fromTrash) {
            requireNotNull(trashFolder)
        } else {
            buildParentDocumentUri(uri)
        }

        val dstParentUri = if (toTrash) {
            getOrCreateTrashFolder(contentResolver, dstUri)!!
        } else {
            buildParentDocumentUri(dstUri)
        }

        if (srcParentUri.authority == dstParentUri.authority) {
            for (recording in recordings) {
                try {
                    DocumentsContract.moveDocument(
                        contentResolver, recording.uri, srcParentUri, dstParentUri
                    )
                } catch (@Suppress("SwallowedException") _: IllegalStateException) {
                    moveDocumentFallback(recording, dstParentUri)
                }
            }
        } else {
            for (recording in recordings) {
                moveDocumentFallback(recording, dstParentUri)
            }
        }
    }

    // Copy source to target, then delete source. Use as fallback when `DocumentsContract.moveDocument` can't used (e.g., when moving between different authorities)
    private fun moveDocumentFallback(
        src: Recording,
        dstParentUri: Uri,
    ) {
        val contentResolver = context.contentResolver
        val dstUri = DocumentsContract.createDocument(
            contentResolver, dstParentUri, src.mimeType, src.title
        )!!

        copyFile(contentResolver, src.uri, dstUri)

        DocumentsContract.deleteDocument(contentResolver, src.uri)
    }

    private fun moveDocumentsToMedia(recordings: Collection<Recording>, dstUri: Uri, toTrash: Boolean) {
        val contentResolver = context.contentResolver
        for (recording in recordings) {
            val dstUri = createMedia(contentResolver, dstUri, recording.title, recording.mimeType)!!

            copyFile(contentResolver, recording.uri, dstUri)

            DocumentsContract.deleteDocument(contentResolver, recording.uri)

            completeMedia(contentResolver, dstUri)

            if (toTrash) {
                updateMediaTrashed(dstUri, recording.title, trash = true)
            }
        }
    }

    private fun moveMediaToDocuments(recordings: Collection<Recording>, dstUri: Uri, toTrash: Boolean) {
        val contentResolver = context.contentResolver
        val dstParentUri = if (toTrash) {
            getOrCreateTrashFolder(contentResolver, dstUri)!!
        } else {
            buildParentDocumentUri(dstUri)
        }

        for (recording in recordings) {
            val dstUri = createDocument(context, dstParentUri, recording.title, recording.mimeType)!!
            copyFile(contentResolver, recording.uri, dstUri)
            contentResolver.delete(recording.uri, null, null)
        }
    }

    private fun moveMediaToMedia(recordings: Collection<Recording>, dstUri: Uri, toTrash: Boolean) {
        if (dstUri != uri) {
            throw UnsupportedOperationException("moving recordings between different media stores is not supported")
        }

        for (recording in recordings) {
            updateMediaTrashed(recording.uri, recording.title, trash = toTrash)
        }
    }

    private fun updateMediaTrashed(uri: Uri, title: String, trash: Boolean) {
        val values = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContentValues().apply {
                put(MediaStore.Audio.Media.IS_TRASHED, if (trash) 1 else 0)
            }
        } else {
            val newName = if (trash) {
                "${TRASHED_PREFIX}$title"
            } else {
                title.removePrefix(TRASHED_PREFIX)
            }

            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, newName)
            }
        }

        context.contentResolver.update(uri, values, null, null)
    }


    fun delete(recordings: Collection<Recording>): Boolean {
        val resolver = context.contentResolver

        recordings.forEach {
            deleteFile(resolver, it.uri)
        }

        return true
    }

    fun createWriter(name: String): RecordingWriter = RecordingWriter.create(context, uri, name)

    private val kind: Kind = Kind.of(uri)

    private val trashFolder: Uri?
        get() = findChildDocument(context.contentResolver, uri, TRASH_FOLDER_NAME)

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

internal fun createDocument(context: Context, parentUri: Uri, name: String, mimeType: String): Uri? = DocumentsContract.createDocument(
    context.contentResolver,
    parentUri,
    mimeType,
    name,
)


internal fun createMedia(contentResolver: ContentResolver, parentUri: Uri, name: String, mimeType: String): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Audio.Media.RELATIVE_PATH, DEFAULT_RECORDINGS_FOLDER)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
    }

    return contentResolver.insert(parentUri, values)
}

internal fun completeMedia(contentResolver: ContentResolver, uri: Uri) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return
    }

    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.IS_PENDING, 0)
    }

    contentResolver.update(uri, values, null, null)
}

internal fun deleteFile(contentResolver: ContentResolver, uri: Uri) = when (Kind.of(uri)) {
    Kind.MEDIA -> contentResolver.delete(uri, null, null)
    Kind.DOCUMENT -> DocumentsContract.deleteDocument(contentResolver, uri)
}

private fun copyFile(contentResolver: ContentResolver, srcUri: Uri, dstUri: Uri) = contentResolver.openInputStream(srcUri)?.use { inputStream ->
    contentResolver.openOutputStream(dstUri)?.use { outputStream ->
        inputStream.copyTo(outputStream)
    }
}

private fun getOrCreateTrashFolder(contentResolver: ContentResolver, parentUri: Uri): Uri? = getOrCreateDocument(
    contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, TRASH_FOLDER_NAME
)

