package org.fossify.voicerecorder.interfaces

import org.fossify.voicerecorder.store.Recording


interface RefreshRecordingsListener {
    fun refreshRecordings()

    fun playRecording(recording: Recording, playOnPrepared: Boolean)
}
