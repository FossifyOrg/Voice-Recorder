package org.fossify.voicerecorder.store

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import java.io.FileNotFoundException

/**
 * Reads and writes per-recording transcript JSON sidecar files in the same directory as
 * the audio file. Mirrors [RecordingStore]'s SAF / MediaStore branching.
 *
 * Filename convention: `<recording-base-name>.transcript.json` (visible to the user — same
 * directory as the audio).
 *
 * One [TranscriptStore] is bound to one [storeUri] (the recordings tree URI or
 * `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`), matching how [RecordingStore] is constructed.
 */
class TranscriptStore(
    private val context: Context,
    private val storeUri: Uri,
) {
    private val resolver = context.contentResolver
    private val cache = LruCache<String, Transcript>(20)

    fun hasTranscript(recording: Recording): Boolean = locateSidecar(recording) != null

    /** Public accessor for the sidecar JSON URI (e.g. for `ACTION_SEND` sharing). */
    fun sidecarUri(recording: Recording): Uri? = locateSidecar(recording)

    fun read(recording: Recording): Transcript? {
        cache.get(recording.uri.toString())?.let { return it }
        val uri = locateSidecar(recording) ?: return null
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                val json = stream.bufferedReader().readText()
                TranscriptCodec.decode(json).also { cache.put(recording.uri.toString(), it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun write(recording: Recording, transcript: Transcript) {
        val name = sidecarFilename(recording)
        // Replace any existing file deterministically.
        delete(recording)
        val target = createSidecar(name) ?: throw FileNotFoundException(
            "could not create sidecar file '$name' under $storeUri"
        )
        resolver.openOutputStream(target)?.use { out ->
            out.write(TranscriptCodec.encode(transcript).toByteArray())
        } ?: throw FileNotFoundException("openOutputStream($target)")
        finalizeMediaIfNeeded(target)
        cache.put(recording.uri.toString(), transcript)
    }

    fun delete(recording: Recording) {
        cache.remove(recording.uri.toString())
        val uri = locateSidecar(recording) ?: return
        if (storeUri.authority == MediaStore.AUTHORITY) {
            resolver.delete(uri, null, null)
        } else {
            DocumentsContract.deleteDocument(resolver, uri)
        }
    }

    // ---- helpers ----

    private fun sidecarFilename(recording: Recording): String {
        val base = recording.title.substringBeforeLast('.', recording.title)
        return "$base$SIDECAR_SUFFIX"
    }

    private fun locateSidecar(recording: Recording): Uri? {
        val name = sidecarFilename(recording)
        return if (storeUri.authority == MediaStore.AUTHORITY) {
            findInMediaStore(name, recording.uri)
        } else {
            findChildDocument(resolver, storeUri, name)
        }
    }

    private fun createSidecar(name: String): Uri? {
        return if (storeUri.authority == MediaStore.AUTHORITY) {
            createInMediaStore(name)
        } else {
            DocumentsContract.createDocument(
                resolver, buildParentDocumentUri(storeUri), MIME_TYPE, name
            )
        }
    }

    private fun findInMediaStore(name: String, recordingUri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-Q MediaStore lookups for non-audio files require legacy storage.
            // Skip — caller treats null as "no transcript".
            return null
        }
        val collection = MediaStore.Files.getContentUri(MEDIA_VOLUME)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val relativePath = recordingRelativePath(recordingUri) ?: DEFAULT_RELATIVE_PATH
        val selection =
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(name, relativePath)
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    private fun createInMediaStore(name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Files.getContentUri(MEDIA_VOLUME)
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
            put(MediaStore.Files.FileColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, DEFAULT_RELATIVE_PATH)
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values)
    }

    private fun finalizeMediaIfNeeded(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (uri.authority != MediaStore.AUTHORITY) return
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.IS_PENDING, 0)
        }
        resolver.update(uri, values, null, null)
    }

    private fun recordingRelativePath(recordingUri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val projection = arrayOf(MediaStore.Files.FileColumns.RELATIVE_PATH)
        return resolver.query(recordingUri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    companion object {
        const val SIDECAR_SUFFIX = ".transcript.json"
        const val MIME_TYPE = "application/json"
        private const val MEDIA_VOLUME = "external"
        private val DEFAULT_RELATIVE_PATH: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "${android.os.Environment.DIRECTORY_RECORDINGS}/"
            } else {
                "${android.os.Environment.DIRECTORY_MUSIC}/"
            }
    }
}
