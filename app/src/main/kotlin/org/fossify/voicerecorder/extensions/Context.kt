package org.fossify.voicerecorder.extensions

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.helpers.*
import org.fossify.voicerecorder.models.Recording
import java.io.File
import kotlin.math.roundToLong

val Context.config: Config get() = Config.newInstance(applicationContext)

fun Context.drawableToBitmap(drawable: Drawable): Bitmap {
    val size = (60 * resources.displayMetrics.density).toInt()
    val mutableBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mutableBitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return mutableBitmap
}

fun Context.updateWidgets(isRecording: Boolean) {
    val widgetIDs = AppWidgetManager.getInstance(applicationContext)
        ?.getAppWidgetIds(ComponentName(applicationContext, MyWidgetRecordDisplayProvider::class.java)) ?: return
    if (widgetIDs.isNotEmpty()) {
        Intent(applicationContext, MyWidgetRecordDisplayProvider::class.java).apply {
            action = TOGGLE_WIDGET_UI
            putExtra(IS_RECORDING, isRecording)
            sendBroadcast(this)
        }
    }
}

fun Context.getDefaultRecordingsFolder(): String {
    val defaultPath = getDefaultRecordingsRelativePath()
    return "$internalStoragePath/$defaultPath"
}

fun Context.getDefaultRecordingsRelativePath(): String {
    return if (isQPlus()) {
        "${Environment.DIRECTORY_MUSIC}/Recordings"
    } else {
        getString(R.string.app_name)
    }
}

@SuppressLint("InlinedApi")
fun Context.getNewMediaStoreRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()

    val uri = Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val projection = arrayOf(
        Media._ID,
        Media.DISPLAY_NAME,
        Media.DATE_ADDED,
        Media.DURATION,
        Media.SIZE
    )

    val bundle = Bundle().apply {
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(Media.DATE_ADDED))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${Media.OWNER_PACKAGE_NAME} = ?")
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(packageName))
        if (config.useRecycleBin) {
            val trashedValue = if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, trashedValue)
        }
    }
    queryCursor(uri, projection, bundle, true) { cursor ->
        val recording = readRecordingFromCursor(cursor)
        recordings.add(recording)
    }

    return recordings
}

@SuppressLint("InlinedApi")
fun Context.getMediaStoreRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()

    val uri = Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val projection = arrayOf(
        Media._ID,
        Media.DISPLAY_NAME,
        Media.DATE_ADDED,
        Media.DURATION,
        Media.SIZE
    )

    var selection = "${Media.OWNER_PACKAGE_NAME} = ?"
    var selectionArgs = arrayOf(packageName)
    val sortOrder = "${Media.DATE_ADDED} DESC"

    if (config.useRecycleBin) {
        val trashedValue = if (trashed) 1 else 0
        selection += " AND ${Media.IS_TRASHED} = ?"
        selectionArgs = selectionArgs.plus(trashedValue.toString())
    }

    queryCursor(uri, projection, selection, selectionArgs, sortOrder, true) { cursor ->
        val recording = readRecordingFromCursor(cursor)
        recordings.add(recording)
    }

    return recordings
}

fun Context.getLegacyRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = if (trashed) {
        trashFolder
    } else {
        config.saveRecordingsFolder
    }
    val files = File(folder).listFiles() ?: return recordings

    files.filter { it.isAudioFast() }.forEach {
        val id = it.hashCode()
        val (title, extension) = it.name.split('.', limit = 2)
        val path = it.absolutePath
        val timestamp = (it.lastModified() / 1000).toInt()
        val duration = getDuration(it.absolutePath) ?: 0
        val size = it.length().toInt()
        val recording = Recording(id, title, extension, path, timestamp, duration, size)
        recordings.add(recording)
    }
    return recordings
}

fun Context.getSAFRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = if (trashed) {
        trashFolder
    } else {
        config.saveRecordingsFolder
    }
    val files = getDocumentSdk30(folder)?.listFiles() ?: return recordings

    files.filter { it.type?.startsWith("audio") == true && !it.name.isNullOrEmpty() }.forEach {
        val id = it.hashCode()
        val (title, extension) = it.name!!.split('.', limit = 2)
        val path = it.uri.toString()
        val timestamp = (it.lastModified() / 1000).toInt()
        val duration = getDurationFromUri(it.uri)
        val size = it.length().toInt()
        val recording = Recording(id, title, extension, path, timestamp, duration.toInt(), size)
        recordings.add(recording)
    }

    recordings.sortByDescending { it.timestamp }
    return recordings
}

fun Context.getAllRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    return when {
        isRPlus() -> {
            recordings.addAll(getNewMediaStoreRecordings(trashed))
            recordings.addAll(getSAFRecordings(trashed))
            recordings
        }

        isQPlus() -> {
            recordings.addAll(getMediaStoreRecordings(trashed))
            recordings.addAll(getLegacyRecordings(trashed))
            recordings
        }

        else -> {
            recordings.addAll(getLegacyRecordings(trashed))
            recordings
        }
    }
}

val Context.trashFolder
    get() = "${config.saveRecordingsFolder}/.trash"

fun Context.getOrCreateTrashFolder(): String {
    val folder = File(trashFolder)
    if (!folder.exists()) {
        folder.mkdir()
    }
    return trashFolder
}

private fun Context.readRecordingFromCursor(cursor: Cursor): Recording {
    val id = cursor.getIntValue(Media._ID)
    val (title, extension) = cursor.getStringValue(Media.DISPLAY_NAME).split('.', limit = 2)
    val timestamp = cursor.getIntValue(Media.DATE_ADDED)
    var duration = cursor.getLongValue(Media.DURATION) / 1000
    var size = cursor.getIntValue(Media.SIZE)

    if (duration == 0L) {
        duration = getDurationFromUri(getAudioFileContentUri(id.toLong()))
    }

    if (size == 0) {
        size = getSizeFromUri(id.toLong())
    }

    return Recording(id, title, extension,"", timestamp, duration.toInt(), size)
}

private fun Context.getSizeFromUri(id: Long): Int {
    val recordingUri = getAudioFileContentUri(id)
    return try {
        contentResolver.openInputStream(recordingUri)?.available() ?: 0
    } catch (e: Exception) {
        0
    }
}

private fun Context.getDurationFromUri(uri: Uri): Long {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, uri)
        val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
        (time.toLong() / 1000.toDouble()).roundToLong()
    } catch (e: Exception) {
        0L
    }
}
