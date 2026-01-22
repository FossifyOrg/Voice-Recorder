package org.fossify.voicerecorder.helpers

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.fossify.voicerecorder.models.RecordingFormat
import java.io.File
import java.io.FileInputStream

/**
 * Helper class to write recordings to the device.
 *
 * Note: Why not use [DocumentsContract.createDocument] directly? Because there is currently a bug in [android.provider.MediaStore] (TODO: link to the
 * bugreport) which causes crash when writing to some [android.provider.DocumentsProvider]s. Using this class works around the bug.
 */
sealed class RecordingWriter {
    companion object {
        fun create(context: Context, parentUri: Uri, name: String, format: RecordingFormat): RecordingWriter {
            val direct = DIRECT_FORMATS.contains(format) or DIRECT_AUTHORITIES.contains(parentUri.authority)

            if (direct) {
                val uri = createDocument(context, parentUri, name, format)
                val fileDescriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "w")) {
                    "failed to open file descriptor at $uri"
                }

                return Direct(context.contentResolver, uri, fileDescriptor)
            } else {
                return Workaround(context, parentUri, name, format)
            }
        }


        // Formats not affected by the MediaStore bug
        private val DIRECT_FORMATS = arrayOf(RecordingFormat.MP3)

        // Document providers not affected by the MediaStore bug
        private val DIRECT_AUTHORITIES = arrayOf("com.android.externalstorage.documents", MediaStore.AUTHORITY)
    }

    /**
     * File descriptor to write the recording data to.
     */
    abstract val fileDescriptor: ParcelFileDescriptor

    abstract fun commit(): Uri

    abstract fun cancel()

    // Writes directly to the document at the given URI.
    class Direct internal constructor(private val contentResolver: ContentResolver, private val uri: Uri, override val fileDescriptor: ParcelFileDescriptor) :
        RecordingWriter() {
        override fun commit(): Uri {
            fileDescriptor.close()
            return uri
        }

        override fun cancel() {
            fileDescriptor.close()
            DocumentsContract.deleteDocument(contentResolver, uri)
        }
    }

    // Writes to a temporary file first, then copies it into the destination document.
    class Workaround internal constructor(
        private val context: Context,
        private val parentTreeUri: Uri,
        private val name: String,
        private val format: RecordingFormat
    ) : RecordingWriter() {
        private val tempFile: File = File(context.cacheDir, "$name.${format.getExtension(context)}.tmp")

        override val fileDescriptor: ParcelFileDescriptor
            get() = ParcelFileDescriptor.open(
                tempFile,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            )

        override fun commit(): Uri {
            val dstUri = createDocument(context, parentTreeUri, name, format)
            val dst = requireNotNull(context.contentResolver.openOutputStream(dstUri)) {
                "failed to open output stream at $dstUri"
            }

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

private fun createDocument(context: Context, parentUri: Uri, name: String, format: RecordingFormat): Uri {
    val displayName = "$name.${format.getExtension(context)}"

    val uri = if (parentUri.authority == MediaStore.AUTHORITY) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, DEFAULT_RECORDINGS_FOLDER)
            }
        }

        context.contentResolver.insert(parentUri, values)
    } else {
        val parentDocumentUri = buildParentDocumentUri(parentUri)
        DocumentsContract.createDocument(
            context.contentResolver,
            parentDocumentUri,
            format.getMimeType(context),
            displayName,
        )
    }

    return requireNotNull(uri) {
        "failed to create document '$displayName' in $parentUri"
    }
}
