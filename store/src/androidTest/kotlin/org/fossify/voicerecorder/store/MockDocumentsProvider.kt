package org.fossify.voicerecorder.store

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import java.io.File
import java.net.URLConnection

class MockDocumentsProvider() : DocumentsProvider() {
    companion object {
        const val ROOT = "root"
        private const val TAG = "MockDocumentsProvider"

    }

    var root: File? = null

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>): Cursor {
        Log.d(TAG, "queryRoots")
        throw NotImplementedError()
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val root = requireNotNull(root)
        val parent = File(root, parentDocumentId)

        val projection = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val result = MatrixCursor(projection)

        for (file in parent.listFiles() ?: emptyArray<File>()) {
            val row = result.newRow()
            val documentId = file.relativeTo(root).path

            if (projection.contains(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) {
                row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            }

            if (projection.contains(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) {
                row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            }
        }

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val root = requireNotNull(root)
        val file = File(root, documentId)

        val projection = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val result = MatrixCursor(projection)
        val row = result.newRow()

        if (projection.contains(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) {
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
        }

        if (projection.contains(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) {
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
        }

        if (projection.contains(DocumentsContract.Document.COLUMN_MIME_TYPE)) {
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (file.isDirectory()) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                URLConnection.guessContentTypeFromName(file.name)
            })
        }

        if (projection.contains(DocumentsContract.Document.COLUMN_SIZE)) {
            row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
        }

        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = File(documentId).parent == parentDocumentId

    override fun openDocument(documentId: String, mode: String, cancellationSignal: CancellationSignal?): ParcelFileDescriptor {
        val root = requireNotNull(root)
        val path = File(root, documentId)

        return ParcelFileDescriptor.open(path, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val root = requireNotNull(root)
        val documentId = "$parentDocumentId/$displayName"
        val file = File(root, documentId)

        file.parentFile?.mkdirs()

        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            file.mkdir()
        } else {
            file.createNewFile()
        }

        return documentId
    }
}
