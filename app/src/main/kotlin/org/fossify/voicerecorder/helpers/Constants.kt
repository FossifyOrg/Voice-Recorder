package org.fossify.voicerecorder.helpers

import android.annotation.SuppressLint
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Media
import org.fossify.commons.helpers.isQPlus

const val RECORDER_RUNNING_NOTIF_ID = 10000

private const val PATH = "com.fossify.voicerecorder.action."
const val GET_RECORDER_INFO = PATH + "GET_RECORDER_INFO"
const val STOP_AMPLITUDE_UPDATE = PATH + "STOP_AMPLITUDE_UPDATE"
const val TOGGLE_PAUSE = PATH + "TOGGLE_PAUSE"

const val EXTENSION_M4A = 0
const val EXTENSION_MP3 = 1
const val EXTENSION_OGG = 2

val BITRATES = arrayListOf(32000, 64000, 96000, 128000, 160000, 192000, 256000, 320000)
const val DEFAULT_BITRATE = 192000
const val SAMPLE_RATE = 44100

const val RECORDING_RUNNING = 0
const val RECORDING_STOPPED = 1
const val RECORDING_PAUSED = 2

const val IS_RECORDING = "is_recording"
const val TOGGLE_WIDGET_UI = "toggle_widget_ui"

// shared preferences
const val HIDE_NOTIFICATION = "hide_notification"
const val SAVE_RECORDINGS = "save_recordings"
const val EXTENSION = "extension"
const val AUDIO_SOURCE = "audio_source"
const val BITRATE = "bitrate"
const val RECORD_AFTER_LAUNCH = "record_after_launch"
const val USE_RECYCLE_BIN = "use_recycle_bin"
const val LAST_RECYCLE_BIN_CHECK = "last_recycle_bin_check"
const val KEEP_SCREEN_ON = "keep_screen_on"

@SuppressLint("InlinedApi")
fun getAudioFileContentUri(id: Long): Uri {
    val baseUri = if (isQPlus()) {
        Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        Media.EXTERNAL_CONTENT_URI
    }

    return ContentUris.withAppendedId(baseUri, id)
}
