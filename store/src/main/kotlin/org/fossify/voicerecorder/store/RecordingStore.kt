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
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import kotlin.math.roundToInt

val DEFAULT_MEDIA_URI: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

val DEFAULT_MEDIA_DIRECTORY: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    Environment.DIRECTORY_RECORDINGS
} else {
    Environment.DIRECTORY_MUSIC
}

/**
 * Utility to manage stored recordings
 *
 * Provides unified API on top of [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider) and
 * [Media Store](https://developer.android.com/training/data-storage/shared/media).
 */
class RecordingStore(private val context: Context, val uri: Uri) {
    companion object {

        internal const val TAG = "RecordingStore"
    }

    init {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Invalid URI '$uri' - must have 'content' scheme" }
    }

    /**
     * Short, human-readable name of this store
     */
    val shortName: String
        get() = when (backend) {
            Backend.DOCUMENT -> {
                val documentId = DocumentsContract.getTreeDocumentId(uri)
                documentId.substringAfter(":").trimEnd('/')
            }

            Backend.MEDIA -> DEFAULT_MEDIA_DIRECTORY
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
     * Returns all recordings in this store as [Sequence].
     */
    fun all(trashed: Boolean = false): Sequence<Recording> = when (backend) {
        Backend.DOCUMENT -> allDocuments(trashed)
        Backend.MEDIA -> allMedia(trashed)
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

                    if (!isMimeAudio(mimeType) || displayName.startsWith(".")) {
                        continue
                    }

                    val duration = getDuration(MetadataSource.Uri(context, uri))

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
            MediaStore.Audio.Media.DATA,
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
                val iData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iDateModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val iDisplayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iSize = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iMimeType = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(iId)
                    val rowUri = ContentUris.withAppendedId(uri, id)
                    val mimeType = cursor.getString(iMimeType)

                    val title = cursor.getString(iDisplayName).let {
                        if (trashed) {
                            it.removePrefix(TRASHED_PREFIX)
                        } else {
                            it
                        }
                    }


                    // Note: On SDK 28 and lower, the value of `DATE_MODIFIED`, `DURATION` and `SIZE` columns seem to be always zero for some reason. To
                    // work around it, we retrieve them from the media file directly (which is still allowed on those SDKs)
                    val timestamp: Long
                    val duration: Int
                    val size: Int

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        timestamp = cursor.getInt(iDateModified).toLong() * 1000
                        duration = cursor.getLong(iDuration).toSeconds()
                        size = cursor.getInt(iSize)
                    } else {
                        val file = File(cursor.getString(iData))
                        timestamp = file.lastModified()
                        duration = getDuration(MetadataSource.Path(file.path))
                        size = file.length().toInt()
                    }

                    yield(
                        Recording(
                            id = id.toInt(), title = title, uri = rowUri, timestamp = timestamp, duration = duration, size = size, mimeType = mimeType
                        )
                    )
                }
            }
        }
    }

    /**
     * Move the given recordings to trash.
     */
    fun trash(recordings: Collection<Recording>) = move(recordings, toTrash = true)

    /**
     * Restore the given trashed recordings
     */
    fun restore(recordings: Collection<Recording>) = move(recordings, fromTrash = true)

    /**
     * Permanently delete all trashed recordings.
     */
    fun deleteTrashed() = delete(all(trashed = true).toList())

    /**
     * Move all recordings in this store (including the trashed ones) into a new store at the given URI.
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

        when (backend) {
            Backend.DOCUMENT -> when (Backend.of(dstUri)) {
                Backend.DOCUMENT -> moveDocumentsToDocuments(recordings, dstUri, fromTrash, toTrash)
                Backend.MEDIA -> moveDocumentsToMedia(recordings, dstUri, toTrash)
            }

            Backend.MEDIA -> when (Backend.of(dstUri)) {
                Backend.DOCUMENT -> moveMediaToDocuments(recordings, dstUri, toTrash)
                Backend.MEDIA -> moveMediaToMedia(recordings, dstUri, toTrash)
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
            getOrCreateTrashFolder(buildParentDocumentUri(dstUri))
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
                } catch (_: UnsupportedOperationException) {
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
        val dstUri = createDocument(
            context, dstParentUri, src.title, src.mimeType
        )
        copyFile(contentResolver, src.uri, dstUri)
        DocumentsContract.deleteDocument(contentResolver, src.uri)
    }

    private fun moveDocumentsToMedia(recordings: Collection<Recording>, dstUri: Uri, toTrash: Boolean) {
        for (recording in recordings) {
            val dstUri = createMedia(context, dstUri, recording.title, recording.mimeType)
            copyFile(context.contentResolver, recording.uri, dstUri)
            DocumentsContract.deleteDocument(context.contentResolver, recording.uri)
            completeMedia(context.contentResolver, dstUri)

            if (toTrash) {
                updateMediaTrashed(dstUri, recording.title, trash = true)
            }
        }
    }

    private fun moveMediaToDocuments(recordings: Collection<Recording>, dstUri: Uri, toTrash: Boolean) {
        val contentResolver = context.contentResolver
        val dstParentUri = if (toTrash) {
            getOrCreateTrashFolder(buildParentDocumentUri(dstUri))
        } else {
            buildParentDocumentUri(dstUri)
        }

        for (recording in recordings) {
            val dstUri = createDocument(context, dstParentUri, recording.title, recording.mimeType)
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


    /**
     * Permanently delete (skipping the trash) the given recordings.
     */
    fun delete(recordings: Collection<Recording>) {
        val resolver = context.contentResolver

        recordings.forEach {
            deleteFile(resolver, it.uri)
        }
    }

    /**
     * Create a [RecordingStore.Writer] for writing a new recording with the given name. The name should contain the file extension (ogg, mp3, ...) which is
     * used to
     * select the format the recording will be stored in.
     */
    fun createWriter(name: String): Writer {
        val extension = MimeTypeMap.getFileExtensionFromUrl(name)
        val mimeType = extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: "application/octet-stream"

        val direct = Writer.DIRECT_EXTENSIONS.contains(extension) or Writer.DIRECT_AUTHORITIES.contains(uri.authority)

        if (direct) {
            val fileUri = createFile(context, uri, name, mimeType)
            val fileDescriptor = context.contentResolver.openFileDescriptor(fileUri, "w") ?: throw FileNotFoundException("$fileUri not found")

            return DirectWriter(fileUri, fileDescriptor)
        } else {
            if (backend == Backend.DOCUMENT) {
                checkDocumentAccess(context, buildParentDocumentUri(uri))
            }

            return WorkaroundWriter(name, mimeType)
        }
    }

    private fun getOrCreateTrashFolder(parentUri: Uri): Uri {
        val uri = findChildDocument(context.contentResolver, parentUri, TRASH_FOLDER_NAME)
        if (uri != null) return uri

        return createDocument(context, parentUri, TRASH_FOLDER_NAME, DocumentsContract.Document.MIME_TYPE_DIR)
    }

    private val backend: Backend = Backend.of(uri)

    private val trashFolder: Uri?
        get() = findChildDocument(context.contentResolver, uri, TRASH_FOLDER_NAME)

    /**
     * Helper class to write recordings to the device.
     *
     * Note: Why not use [DocumentsContract.createDocument] directly? Because there is currently a [bug in `MediaRecorder`](https://issuetracker.google.com/issues/479420499)
     * which causes crash when writing to some [android.provider.DocumentsProvider]s. Using this class works around the bug.
     */
    sealed class Writer {
        companion object {
            // Mime types not affected by the MediaStore bug
            internal val DIRECT_EXTENSIONS = arrayOf("mp3")

            // Document providers not affected by the MediaStore bug
            internal val DIRECT_AUTHORITIES = arrayOf("com.android.externalstorage.documents", MediaStore.AUTHORITY)
        }

        /**
         * File descriptor to write the recording data to.
         */
        abstract val fileDescriptor: ParcelFileDescriptor

        abstract fun commit(): Uri

        abstract fun cancel()
    }

    // Writes directly to the document at the given URI.
    private inner class DirectWriter(private val uri: Uri, override val fileDescriptor: ParcelFileDescriptor) : Writer() {
        override fun commit(): Uri {
            fileDescriptor.close()

            if (uri.authority == MediaStore.AUTHORITY) {
                completeMedia(context.contentResolver, uri)
            }

            return uri
        }

        override fun cancel() {
            fileDescriptor.close()
            deleteFile(context.contentResolver, uri)
        }
    }

    // Writes to a temporary file first, then copies it into the destination document.
    private inner class WorkaroundWriter(private val name: String, private val mimeType: String) : Writer() {
        private val tempFile: File = File(context.cacheDir, "$name.tmp")

        override val fileDescriptor: ParcelFileDescriptor
            get() = ParcelFileDescriptor.open(
                tempFile, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            )

        override fun commit(): Uri {
            val dstUri = createFile(context, uri, name, mimeType)
            val dst = context.contentResolver.openOutputStream(dstUri) ?: throw FileNotFoundException("$dstUri not found")

            val src = FileInputStream(tempFile)

            src.use { src ->
                dst.use { dst ->
                    src.copyTo(dst)
                }
            }

            tempFile.delete()

            return dstUri
        }

        override fun cancel() {
            tempFile.delete()
        }
    }
}

private const val TRASH_FOLDER_NAME = ".trash"
private const val TRASHED_PREFIX = ".trashed-"

// Storage backend: Storage Access Framework or Media Store
private enum class Backend {
    DOCUMENT, MEDIA;

    companion object {
        fun of(uri: Uri): Backend = if (uri.authority == MediaStore.AUTHORITY) {
            MEDIA
        } else {
            DOCUMENT
        }
    }
}

private sealed class MetadataSource {
    data class Uri(val context: Context, val uri: android.net.Uri) : MetadataSource()
    data class Path(val path: String) : MetadataSource()
}

private fun getDuration(source: MetadataSource): Int = MediaMetadataRetriever().run {
    try {
        when (source) {
            is MetadataSource.Uri -> setDataSource(source.context, source.uri)
            is MetadataSource.Path -> setDataSource(source.path)
        }
        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?.toSeconds() ?: 0
    } catch (_: Exception) {
        0
    } finally {
        release()
    }
}

private fun createFile(context: Context, parentUri: Uri, name: String, mimeType: String): Uri = if (parentUri.authority == MediaStore.AUTHORITY) {
    createMedia(context, parentUri, name, mimeType)
} else {
    createDocument(context, buildParentDocumentUri(parentUri), name, mimeType)
}

private fun createDocument(context: Context, parentUri: Uri, name: String, mimeType: String): Uri {
    val uri = DocumentsContract.createDocument(
        context.contentResolver,
        parentUri,
        mimeType,
        name,
    )

    if (uri != null) {
        return uri
    }

    checkDocumentAccess(context, parentUri)

    throw FileNotFoundException("$parentUri not found")
}

private fun checkDocumentAccess(context: Context, uri: Uri) {
    val authority = requireNotNull(uri.authority) { "invalid URI: $uri" }
    context.packageManager.resolveContentProvider(authority, 0) ?: throw FileNotFoundException("content provider $authority not found")

    val projection = arrayOf(DocumentsContract.Document.COLUMN_FLAGS)
    val cursor = context.contentResolver.query(uri, projection, null, null, null) ?: throw FileNotFoundException("$uri not found")
    cursor.use { cursor ->
        if (!cursor.moveToNext()) {
            throw FileNotFoundException("$uri not found")
        }

        if (cursor.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
            throw UnsupportedOperationException("$uri is not writable")
        }
    }
}

private fun createMedia(context: Context, parentUri: Uri, name: String, mimeType: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Audio.Media.RELATIVE_PATH, DEFAULT_MEDIA_DIRECTORY)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        } else {
            Environment.getExternalStoragePublicDirectory(DEFAULT_MEDIA_DIRECTORY)?.let { dir ->
                put(MediaStore.Audio.Media.DATA, File(dir, name).toString())
            }
        }
    }

    return context.contentResolver.insert(parentUri, values) ?: throw FileNotFoundException("$parentUri not found")
}

private fun completeMedia(contentResolver: ContentResolver, uri: Uri) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return
    }

    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.IS_PENDING, 0)
    }

    contentResolver.update(uri, values, null, null)
}

private fun deleteFile(contentResolver: ContentResolver, uri: Uri) = when (Backend.of(uri)) {
    Backend.MEDIA -> contentResolver.delete(uri, null, null)
    Backend.DOCUMENT -> DocumentsContract.deleteDocument(contentResolver, uri)
}

private fun copyFile(contentResolver: ContentResolver, srcUri: Uri, dstUri: Uri) {
    val src = contentResolver.openInputStream(srcUri) ?: throw FileNotFoundException("failed to open $srcUri for reading")
    val dst = contentResolver.openOutputStream(dstUri) ?: throw FileNotFoundException("failed to open $dstUri for writing")

    src.use { inputStream ->
        dst.use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    }
}

private fun Long.toSeconds(): Int = (this / 1000.toDouble()).roundToInt()

// HACK: On SDK 26, 'ogg' is sometimes identified as 'application/ogg' instead of 'audio/ogg'
private fun isMimeAudio(mime: String): Boolean = mime.startsWith("audio/") || mime == "application/ogg"

