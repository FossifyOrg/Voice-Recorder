package org.fossify.voicerecorder.models

data class Recording(val id: Int, val title: String, val extension: String, val path: String, val timestamp: Int, val duration: Int, val size: Int) {
    val titleWithExtension: String
        get() = "$title.$extension"
}
