package org.fossify.voicerecorder.extensions

import androidx.documentfile.provider.DocumentFile

fun DocumentFile.isAudioRecording(): Boolean {
    return type.isAudioMimeType() && !name.isNullOrEmpty() && !name!!.startsWith(".")
}

fun DocumentFile.isTrashedMediaStoreRecording(): Boolean {
    return type.isAudioMimeType() && !name.isNullOrEmpty() && name!!.startsWith(".trashed-")
}
