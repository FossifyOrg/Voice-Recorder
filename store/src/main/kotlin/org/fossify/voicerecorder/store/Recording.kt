package org.fossify.voicerecorder.store

import android.content.Context
import android.net.Uri

data class Recording(
    val id: Int, val title: String, val uri: Uri, val timestamp: Long, val duration: Int, val size: Int, val mimeType: String
)

enum class RecordingFormat(val value: Int) {
    M4A(0), MP3(1), OGG(2);

    companion object {
        fun fromInt(value: Int): RecordingFormat? = when (value) {
            M4A.value -> M4A
            MP3.value -> MP3
            OGG.value -> OGG
            else -> null
        }
    }

    fun getDescription(context: Context): String = context.getString(
        when (this) {
            M4A -> R.string.m4a
            MP3 -> R.string.mp3_experimental
            OGG -> R.string.ogg_opus
        }
    )

    fun getExtension(context: Context): String = context.getString(
        when (this) {
            M4A -> R.string.m4a
            MP3 -> R.string.mp3
            OGG -> R.string.ogg
        }
    )
}
