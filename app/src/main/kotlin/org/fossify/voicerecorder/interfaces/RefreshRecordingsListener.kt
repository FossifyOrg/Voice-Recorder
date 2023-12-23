package org.fossify.voicerecorder.interfaces

import org.fossify.voicerecorder.models.Recording

interface RefreshRecordingsListener {
    fun refreshRecordings()

    fun playRecording(recording: Recording, playOnPrepared: Boolean)
}
