package org.fossify.voicerecorder.store

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.net.URLConnection

class MockDocumentsProvider() : DocumentsProvider() {
    companion object {
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val DEFAULT_ROOT_PROJECTION = arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID)

        const val ROOT_ID = "main"

        private const val TAG = "MockDocumentsProvider"

    }

    var root: File? = null

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION).apply {
        newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "")
        }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val root = requireNotNull(root)
        val parent = File(root, parentDocumentId)

        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            for (file in parent.listFiles() ?: emptyArray<File>()) {
                val documentId = file.relativeTo(root).path
                addFile(documentId, file)
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val root = requireNotNull(root)
        val file = File(root, documentId)

        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            addFile(documentId, file)
        }
    }

    private fun MatrixCursor.addFile(documentId: String, file: File) {
        val row = newRow()

        if (columnNames.contains(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) {
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
        }

        if (columnNames.contains(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) {
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
        }

        if (columnNames.contains(DocumentsContract.Document.COLUMN_MIME_TYPE)) {
            row.add(
                DocumentsContract.Document.COLUMN_MIME_TYPE, if (file.isDirectory()) {
                    DocumentsContract.Document.MIME_TYPE_DIR
                } else {
                    URLConnection.guessContentTypeFromName(file.name)
                }
            )
        }

        if (columnNames.contains(DocumentsContract.Document.COLUMN_SIZE)) {
            row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
        }

    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentId.startsWith("$parentDocumentId/")

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

    override fun moveDocument(
        sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String
    ): String {
        val root = requireNotNull(root)
        val srcFile = File(root, sourceDocumentId)
        val dstFile = File(root, "$targetParentDocumentId/${srcFile.name}")

        dstFile.parentFile?.mkdirs()
        srcFile.renameTo(dstFile)

        return dstFile.relativeTo(root).path
    }

    override fun deleteDocument(documentId: String) {
        val root = requireNotNull(root)
        val file = File(root, documentId)
        file.deleteRecursively()
    }
}
