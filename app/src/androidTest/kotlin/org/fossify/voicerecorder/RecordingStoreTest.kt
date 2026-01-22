package org.fossify.voicerecorder

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.fossify.voicerecorder.helpers.RecordingStore
import org.fossify.voicerecorder.models.RecordingFormat
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch

class RecordingStoreTest {
    companion object {
        private const val MOCK_PROVIDER_AUTHORITY = "org.fossify.voicerecorder.mockprovider"
        private val DEFAULT_MEDIA_URI = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        private const val TAG = "RecordingStoreTest"
    }

    @Before
    fun setup() {
        deleteTestMedia()
    }

    @After
    fun teardown() {
        deleteTestMedia()
    }

//    @Test
//    fun createRecording_SAF() {
//    }

    @Test
    fun createRecording_MediaStore() {
        val store = RecordingStore(context, DEFAULT_MEDIA_URI)

        val name = makeTestMediaName("sample")
        val uri = store.createRecording(name, RecordingFormat.OGG)

        val recordings = store.getAll()
        val recording = recordings.find { it.uri == uri }
        assertNotNull(recording)

        val size = getSize(uri)
        assertTrue(size > 0)
    }

    @Test
    fun trashRecording_MediaStore() {
        val store = RecordingStore(context, DEFAULT_MEDIA_URI)

        val name = makeTestMediaName("sample")
        val uri = store.createRecording(name, RecordingFormat.OGG)

        val recording = store.getAll().find { it.uri == uri }!!

        assertFalse(store.getAll(trashed = true).any { it.title == recording.title })

        store.trash(listOf(recording))

        assertFalse(store.getAll(trashed = false).any { it.title == recording.title })
        assertTrue(store.getAll(trashed = true).any { it.title == recording.title })
    }

    @Test
    fun restoreRecording_MediaStore() {
        val store = RecordingStore(context, DEFAULT_MEDIA_URI)

        val uri = store.createRecording(makeTestMediaName("sample"), RecordingFormat.OGG)
        val recording = store.getAll(trashed = false).find { it.uri == uri }!!

        store.trash(listOf(recording))
        val trashedRecording = store.getAll(trashed = true).find { it.title == recording.title }!!

        store.restore(listOf(trashedRecording))
        assertTrue(store.getAll(trashed = false).any { it.title == recording.title })
        assertFalse(store.getAll(trashed = true).any { it.title == recording.title })
    }

    private val context: Context
        get() = instrumentation.targetContext

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun makeTestMediaName(name: String): String = "$name$testMediaSuffix.${System.currentTimeMillis()}"

    private fun deleteTestMedia() {
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
        get() = ".${context.packageName}.test"

    private val contentObserverHandler = Handler(HandlerThread("contentObserver").apply { start() }.looper)

    private fun RecordingStore.createRecording(name: String, format: RecordingFormat): Uri {
        val inputFd = when (format) {
            RecordingFormat.M4A -> TODO()
            RecordingFormat.MP3 -> TODO()
            RecordingFormat.OGG -> instrumentation.context.assets.openFd("sample.ogg")
        }

        val inputSize = inputFd.length
        val input = inputFd.createInputStream()

        val uri = createWriter(name, format).run {
            input.use { input ->
                FileOutputStream(fileDescriptor.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }

            commit()
        }

        // HACK: Wait until the recording reaches the expected size. This is because sometimes the recording has not been fully written yet at this point for
        // some reason. This prevents some subsequent operations on the recording (e.g., move to trash) to fail.
        waitUntilSize(uri, inputSize)

        return uri
    }

    // Waits until the document/media at the given URI reaches the expected size
    private fun waitUntilSize(uri: Uri, expectedSize: Long) {
        val latch = CountDownLatch(1)
        val observer = object : ContentObserver(contentObserverHandler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)

                if (getSize(uri) >= expectedSize) {
                    latch.countDown()
                }
            }
        }

        context.contentResolver.registerContentObserver(uri, false, observer)

        if (getSize(uri) < expectedSize) {
            latch.await()
        }

        context.contentResolver.unregisterContentObserver(observer)
    }

    private fun getSize(uri: Uri): Long = when (uri.authority) {
        MediaStore.AUTHORITY -> {
            val projection = arrayOf(MediaStore.Audio.Media.SIZE)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val iSize = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                if (cursor.moveToNext()) {
                    cursor.getLong(iSize)
                } else {
                    null
                }
            } ?: 0
        }
        else -> TODO()
    }
}
