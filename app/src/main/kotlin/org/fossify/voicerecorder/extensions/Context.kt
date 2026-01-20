package org.fossify.voicerecorder.extensions

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.graphics.createBitmap
import androidx.documentfile.provider.DocumentFile
import org.fossify.voicerecorder.helpers.*
import org.fossify.voicerecorder.models.Recording
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToLong

val Context.config: Config get() = Config.newInstance(applicationContext)

private const val TRASH_FOLDER_NAME = ".trash"

/**
 * Returns the URI of the trash folder as a sub-folder of the save recordings folder. The trash folder itself might not yet exists. Returns null if the save
 * recordings folder is not defined.
 */
val Context.trashFolder: Uri?
    get() = config.saveRecordingsFolder?.let {
        findChildDocument(contentResolver, it, TRASH_FOLDER_NAME)
    }

fun Context.drawableToBitmap(drawable: Drawable): Bitmap {
    val size = (60 * resources.displayMetrics.density).toInt()
    val mutableBitmap = createBitmap(size, size)
    val canvas = Canvas(mutableBitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return mutableBitmap
}

fun Context.updateWidgets(isRecording: Boolean) {
    val widgetIDs = AppWidgetManager.getInstance(applicationContext)
        ?.getAppWidgetIds(
            ComponentName(
                applicationContext,
                MyWidgetRecordDisplayProvider::class.java
            )
        ) ?: return

    if (widgetIDs.isNotEmpty()) {
        Intent(applicationContext, MyWidgetRecordDisplayProvider::class.java).apply {
            action = TOGGLE_WIDGET_UI
            putExtra(IS_RECORDING, isRecording)
            sendBroadcast(this)
        }
    }
}

/**
 * Returns the URI of the trash folder. Creates the folder if it doesn't yet exist. Returns null if the save recording folder is not defined or if the trash
 * folder creation failed.
 *
 * @see [trashFolder]
 */
fun Context.getOrCreateTrashFolder(): Uri? = config.saveRecordingsFolder?.let {
    getOrCreateDocument(contentResolver, it,DocumentsContract.Document.MIME_TYPE_DIR, TRASH_FOLDER_NAME)
}

fun Context.hasRecordings(): Boolean = config.saveRecordingsFolder?.let { uri ->
    DocumentFile.fromTreeUri(this, uri)?.listFiles()?.any { it.isAudioRecording() }
} == true

fun Context.getAllRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = arrayListOf<Recording>()

    recordings.addAll(getRecordings(trashed))

    if (trashed) {
        // Return recordings trashed using MediaStore, this won't be needed in the future
        @Suppress("DEPRECATION")
        recordings.addAll(getMediaStoreTrashedRecordings())
    }

    return recordings
}

private fun Context.getRecordings(trashed: Boolean = false): List<Recording> {
    val uri = if (trashed) trashFolder else config.saveRecordingsFolder
    val folder = uri?.let { DocumentFile.fromTreeUri(this, it) }

    return folder
        ?.listFiles()
        ?.filter { it.isAudioRecording() }
        ?.map { readRecordingFromFile(it) }
        ?.toList()
        ?: emptyList()
}

@Deprecated(
    message = "Use getRecordings instead. This method is only here for backward compatibility.",
    replaceWith = ReplaceWith("getRecordings(trashed = true)")
)
private fun Context.getMediaStoreTrashedRecordings(): List<Recording> {
    val trashedRegex = "^\\.trashed-\\d+-".toRegex()

    return config
        .saveRecordingsFolder
        ?.let { DocumentFile.fromTreeUri(this, it) }
        ?.listFiles()
        ?.filter { it.isTrashedMediaStoreRecording() }
        ?.map {
            readRecordingFromFile(it).copy(title = trashedRegex.replace(it.name!!, ""))
        }
        ?.toList()
        ?: emptyList()
}

private fun Context.readRecordingFromFile(file: DocumentFile): Recording {
    val id = file.hashCode()
    val title = file.name!!
    val path = file.uri.toString()
    val timestamp = file.lastModified()
    val duration = getDurationFromUri(file.uri)
    val size = file.length().toInt()
    return Recording(
        id = id,
        title = title,
        path = path,
        timestamp = timestamp,
        duration = duration.toInt(),
        size = size
    )
}

private fun Context.getDurationFromUri(uri: Uri): Long {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, uri)
        val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
        (time.toLong() / 1000.toDouble()).roundToLong()
    } catch (_: Exception) {
        0L
    }
}

// move to commons in the future
fun Context.getFormattedFilename(): String {
    val pattern = config.filenamePattern
    val calendar = Calendar.getInstance()

    val year = calendar.get(Calendar.YEAR).toString()
    val month = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MONTH) + 1)
    val day = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.DAY_OF_MONTH))
    val hour = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.HOUR_OF_DAY))
    val minute = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MINUTE))
    val second = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.SECOND))

    return pattern
        .replace("%Y", year, false)
        .replace("%M", month, false)
        .replace("%D", day, false)
        .replace("%h", hour, false)
        .replace("%m", minute, false)
        .replace("%s", second, false)
}
