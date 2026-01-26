package org.fossify.voicerecorder.extensions

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.graphics.createBitmap
import org.fossify.voicerecorder.helpers.*
import org.fossify.voicerecorder.store.RecordingStore

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.recordingStore: RecordingStore get() = recordingStoreFor(config.saveRecordingsFolder)

fun Context.recordingStoreFor(uri: Uri): RecordingStore = RecordingStore(this, uri)

fun Context.drawableToBitmap(drawable: Drawable): Bitmap {
    val size = (60 * resources.displayMetrics.density).toInt()
    val mutableBitmap = createBitmap(size, size)
    val canvas = Canvas(mutableBitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return mutableBitmap
}

fun Context.updateWidgets(isRecording: Boolean) {
    val widgetIDs = AppWidgetManager.getInstance(applicationContext)?.getAppWidgetIds(
            ComponentName(
                applicationContext, MyWidgetRecordDisplayProvider::class.java
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
