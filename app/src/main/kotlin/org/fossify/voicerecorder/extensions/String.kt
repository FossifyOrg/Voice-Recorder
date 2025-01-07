package org.fossify.voicerecorder.extensions

fun String?.isAudioMimeType(): Boolean {
    return this?.startsWith("audio") == true
}
