package org.fossify.voicerecorder.store

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Given a tree URI of some directory (such as obtained with `ACTION_OPEN_DOCUMENT_TREE` intent),
 * returns a corresponding parent URI that can be used to create child documents in it.
 */
internal fun buildParentDocumentUri(treeUri: Uri): Uri {
    val parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
}

/**
 * Finds the child document with the given name
 */
internal fun findChildDocument(contentResolver: ContentResolver, treeUri: Uri, displayName: String): Uri? {
    val parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)

    contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == displayName) {
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
            }
        }
    }

    return null
}

/**
 * Returns the child document with the given name or creates it if it doesn't exists.
 */
fun getOrCreateDocument(contentResolver: ContentResolver, treeUri: Uri, mimeType: String, displayName: String): Uri? {
    val uri = findChildDocument(contentResolver, treeUri, displayName)
    if (uri != null) return uri

    val parentDocumentUri = buildParentDocumentUri(treeUri)
    return DocumentsContract.createDocument(contentResolver, parentDocumentUri, mimeType, displayName)
}
