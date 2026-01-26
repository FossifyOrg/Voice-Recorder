package org.fossify.voicerecorder.store

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap

data class Recording(
    val id: Int,
    val title: String,
    val uri: Uri,
    val timestamp: Long,
    val duration: Int,
    val size: Int
)

enum class RecordingFormat(val value: Int) {
    M4A(0),
    MP3(1),
    OGG(2);

    companion object {
        fun fromInt(value: Int): RecordingFormat? = when (value) {
            M4A.value -> M4A
            MP3.value -> MP3
            OGG.value -> OGG
            else -> null
        }

        /**
         * Return formats that are available on the current platform
         */
        val available: List<RecordingFormat> = arrayListOf(M4A, MP3).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(OGG)
        }
    }

    fun getDescription(context: Context): String = context.getString(
        when (this) {
            RecordingFormat.M4A -> R.string.m4a
            RecordingFormat.MP3 -> R.string.mp3_experimental
            OGG -> R.string.ogg_opus
        }
    )

    fun getExtension(context: Context): String = context.getString(
        when (this) {
            RecordingFormat.M4A -> R.string.m4a
            RecordingFormat.MP3 -> R.string.mp3
            OGG -> R.string.ogg
        }
    )

    fun getMimeType(context: Context): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(getExtension(context))!!
}
