package org.fossify.voicerecorder.store

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import java.io.File
import java.io.FileOutputStream

class RecordingStoreTest {
    companion object {
        private const val MOCK_PROVIDER_AUTHORITY = "org.fossify.voicerecorder.store.mock.provider"
        private val DEFAULT_DOCUMENTS_URI = DocumentsContract.buildTreeDocumentUri(MOCK_PROVIDER_AUTHORITY, "Recordings")
        private const val TAG = "RecordingStoreTest"
    }

    @get:Rule
    val permissionRule: TestRule = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        GrantPermissionRule.grant(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    } else {
        RuleChain.emptyRuleChain()
    }

    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = File(instrumentation.context.cacheDir, "temp-${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val mockDocumentsProvider = context.contentResolver.acquireContentProviderClient(MOCK_PROVIDER_AUTHORITY)?.localContentProvider as MockDocumentsProvider
        mockDocumentsProvider.root = tempDir
    }

    @After
    fun teardown() {
        deleteTestFiles()
        tempDir.deleteRecursively()
    }

    @Test
    fun createRecording_MediaStore() = createRecording(DEFAULT_MEDIA_URI)

    @Test
    fun createRecording_SAF() = createRecording(DEFAULT_DOCUMENTS_URI)

    private fun createRecording(uri: Uri) {
        val store = RecordingStore(context, uri)

        val name = makeTestName("sample.ogg")
        val uri = store.createRecording(name)

        val recording = store.all().find { it.uri == uri }
        assertNotNull(recording)

        val size = getSize(uri)
        assertTrue(size > 0)
    }

    @Test
    fun trashRecording_MediaStore() = trashRecording(DEFAULT_MEDIA_URI)

    @Test
    fun trashRecording_SAF() = trashRecording(DEFAULT_DOCUMENTS_URI)

    private fun trashRecording(uri: Uri) {
        val store = RecordingStore(context, uri)

        val name = makeTestName("sample.ogg")
        val uri = store.createRecording(name)

        val recording = store.all().find { it.uri == uri }!!

        assertFalse(store.all(trashed = true).any { it.title == recording.title })

        store.trash(listOf(recording))

        assertFalse(store.all(trashed = false).any { it.title == recording.title })
        assertTrue(store.all(trashed = true).any { it.title == recording.title })
    }

    @Test
    fun restoreRecording_MediaStore() = restoreRecording(DEFAULT_MEDIA_URI)

    @Test
    fun restoreRecording_SAF() = restoreRecording(DEFAULT_DOCUMENTS_URI)

    private fun restoreRecording(uri: Uri) {
        val store = RecordingStore(context, uri)

        val uri = store.createRecording(makeTestName("sample.ogg"))
        val recording = store.all(trashed = false).find { it.uri == uri }!!

        store.trash(listOf(recording))
        val trashedRecording = store.all(trashed = true).find { it.title == recording.title }!!

        store.restore(listOf(trashedRecording))
        assertTrue(store.all(trashed = false).any { it.title == recording.title })
        assertFalse(store.all(trashed = true).any { it.title == recording.title })
    }

    @Test
    fun deleteNormalRecording_MediaStore() = deleteRecording(DEFAULT_MEDIA_URI, trashed = false)

    @Test
    fun deleteNormalRecording_SAF() = deleteRecording(DEFAULT_DOCUMENTS_URI, trashed = false)

    @Test
    fun deleteTrashedRecording_MediaStore() = deleteRecording(DEFAULT_MEDIA_URI, trashed = true)

    @Test
    fun deleteTrashedRecording_SAF() = deleteRecording(DEFAULT_DOCUMENTS_URI, trashed = true)

    private fun deleteRecording(uri: Uri, trashed: Boolean) {
        val store = RecordingStore(context, uri)

        val name = makeTestName("sample.ogg")
        val uri = store.createRecording(name)

        var recording = store.all().find { it.uri == uri }!!

        if (trashed) {
            store.trash(listOf(recording))
            recording = store.all(trashed = true).find { it.title == recording.title }!!
        }

        store.delete(listOf(recording))

        assertFalse(store.all(trashed = false).any { it.title == recording.title })
        assertFalse(store.all(trashed = true).any { it.title == recording.title })
    }

    @Test
    fun migrate_SAF_to_SAF() = migrate(
        DocumentsContract.buildTreeDocumentUri(MOCK_PROVIDER_AUTHORITY, "Old audio"),
        DocumentsContract.buildTreeDocumentUri(MOCK_PROVIDER_AUTHORITY, "New audio"),
    )

    @Test
    fun migrate_SAF_to_MediaStore() = migrate(DEFAULT_DOCUMENTS_URI, DEFAULT_MEDIA_URI)

    @Test
    fun migrate_MediaStore_to_SAF() = migrate(DEFAULT_MEDIA_URI, DEFAULT_DOCUMENTS_URI)

    private fun migrate(srcUri: Uri, dstUri: Uri) {
        val srcStore = RecordingStore(context, srcUri)
        val dstStore = RecordingStore(context, dstUri)

        val normalRecording = srcStore.createRecording(makeTestName("one.ogg")).let { uri ->
            srcStore.all().find { it.uri == uri }!!
        }

        val trashedRecording = srcStore.createRecording(makeTestName("two.ogg")).let { uri ->
            val recording = srcStore.all().find { it.uri == uri }!!
            srcStore.trash(listOf(recording))
            srcStore.all(trashed = true).find { it.title == recording.title }!!
        }

        srcStore.migrate(dstUri)

        assertFalse(srcStore.all(trashed = false).any { it.title == normalRecording.title })
        assertFalse(srcStore.all(trashed = true).any { it.title == trashedRecording.title })

        assertTrue(dstStore.all(trashed = false).any { it.title == normalRecording.title })
        assertTrue(dstStore.all(trashed = true).any { it.title == trashedRecording.title })
    }

    private val context: Context
        get() = instrumentation.targetContext

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun makeTestName(name: String): String {
        val suffix = "$testMediaSuffix.${System.currentTimeMillis()}"
        val lastDot = name.lastIndexOf('.')

        return if (lastDot >= 0) {
            "${name.take(lastDot)}$suffix.${name.substring(lastDot + 1)}"
        } else {
            "$name$suffix"
        }
    }

    private fun deleteTestFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%$testMediaSuffix%"))
            }

            context.contentResolver.delete(DEFAULT_MEDIA_URI, queryArgs)
        } else {
            context.contentResolver.delete(DEFAULT_MEDIA_URI, "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?", arrayOf("%$testMediaSuffix%"))
        }
    }

    private val testMediaSuffix
        get() = ".${context.packageName}"

    private fun RecordingStore.createRecording(name: String): Uri {
        val inputFd = when (MimeTypeMap.getFileExtensionFromUrl(name)) {
            "ogg", "oga" -> instrumentation.context.assets.openFd("sample.ogg")
            else -> throw NotImplementedError()
        }

        val input = inputFd.createInputStream()

        val uri = createWriter(name).run {
            input.use { input ->
                FileOutputStream(fileDescriptor.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }

            commit()
        }

        return uri
    }

    private fun getSize(uri: Uri): Long = when (uri.authority) {
        MediaStore.AUTHORITY -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                queryLong(uri, MediaStore.Audio.Media.SIZE)
            } else {
                // On SDK 28 or lower, querying for SIZE seems to always return 0, but on those SDKs we can get the actual media file and get
                // its size directly.
                context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToNext()) {
                        cursor.getString(0)
                    } else {
                        null
                    }
                }?.let { File(it).length() } ?: 0
            }
        }

        else -> queryLong(uri, DocumentsContract.Document.COLUMN_SIZE)
    }

    private fun queryLong(uri: Uri, column: String): Long = context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        if (cursor.moveToNext()) {
            cursor.getLong(0)
        } else {
            null
        }
    } ?: 0
}




