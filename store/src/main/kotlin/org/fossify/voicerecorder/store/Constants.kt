package org.fossify.voicerecorder.store

import android.os.Build
import android.os.Environment

val DEFAULT_RECORDINGS_FOLDER = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    Environment.DIRECTORY_RECORDINGS
} else {
    "Recordings"
}
