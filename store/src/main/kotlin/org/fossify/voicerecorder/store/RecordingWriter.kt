package org.fossify.voicerecorder.store

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream

/**
 * Helper class to write recordings to the device.
 *
 * Note: Why not use [DocumentsContract.createDocument] directly? Because there is currently a bug in [MediaStore] (TODO: link to the
 * bugreport) which causes crash when writing to some [android.provider.DocumentsProvider]s. Using this class works around the bug.
 */
sealed class RecordingWriter {
    companion object {
        fun create(context: Context, parentUri: Uri, name: String): RecordingWriter {
            val extension = MimeTypeMap.getFileExtensionFromUrl(name)
            val mimeType = extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: "application/octet-stream"

            val direct = DIRECT_EXTENSIONS.contains(extension) or DIRECT_AUTHORITIES.contains(parentUri.authority)

            if (direct) {
                val uri = createFile(context, parentUri, name, mimeType)
                val fileDescriptor = checkNotNull(context.contentResolver.openFileDescriptor(uri, "w")) {
                    "failed to open file descriptor at $uri"
                }

                return Direct(context.contentResolver, uri, fileDescriptor)
            } else {
                return Workaround(context, parentUri, name, mimeType)
            }
        }


        // Mime types not affected by the MediaStore bug
        private val DIRECT_EXTENSIONS = arrayOf("mp3")

        // Document providers not affected by the MediaStore bug
        private val DIRECT_AUTHORITIES = arrayOf("com.android.externalstorage.documents", MediaStore.AUTHORITY)

        private const val TAG = "RecordingWriter"
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
            deleteFile(contentResolver, uri)
        }
    }

    // Writes to a temporary file first, then copies it into the destination document.
    class Workaround internal constructor(
        private val context: Context,
        private val parentTreeUri: Uri,
        private val name: String,
        private val mimeType: String
    ) : RecordingWriter() {
        private val tempFile: File = File(context.cacheDir, "$name.tmp")

        override val fileDescriptor: ParcelFileDescriptor
            get() = ParcelFileDescriptor.open(
                tempFile,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            )

        override fun commit(): Uri {
            val dstUri = createFile(context, parentTreeUri, name, mimeType)
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

private fun createFile(context: Context, parentUri: Uri, name: String, mimeType: String): Uri {
    val uri = if (parentUri.authority == MediaStore.AUTHORITY) {
        createMedia(context.contentResolver, parentUri, name, mimeType)
    } else {
        createDocument(context, parentUri, name, mimeType)
    }

    return requireNotNull(uri) {
        "failed to create file '$name' in $parentUri"
    }
}
