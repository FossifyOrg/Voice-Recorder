package org.fossify.voicerecorder.store

import android.database.Cursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsProvider
import android.util.Log
import java.io.File

class MockDocumentsProvider(): DocumentsProvider() {
    companion object {
        const val ROOT = "root"
        private const val TAG = "MockDocumentsProvider"
    }

    private var root: File? = null

    override fun onCreate(): Boolean {
        Log.d(TAG, "onCreate")
        return true
    }

    override fun queryRoots(projection: Array<out String>): Cursor {
        Log.d(TAG, "queryRoots")
        throw NotImplementedError()
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>, sortOrder: String): Cursor {
        Log.d(TAG, "queryChildDocuments")
        throw NotImplementedError()
    }

    override fun queryDocument(documentId: String, projection: Array<out String>): Cursor {
        Log.d(TAG, "queryDocument")
        throw NotImplementedError()
    }

    override fun openDocument(documentId: String?, mode: String?, cancellationSignal: CancellationSignal?): ParcelFileDescriptor {
        Log.d(TAG, "openDocument")
        throw NotImplementedError()
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        Log.d(TAG, "createDocument($parentDocumentId, $mimeType, $displayName")
        throw NotImplementedError()
    }
}
