package org.fossify.voicerecorder.models

import android.net.Uri

class Events {
    class RecordingDuration internal constructor(val duration: Int)
    class RecordingStatus internal constructor(val status: Int)
    class RecordingAmplitude internal constructor(val amplitude: Int)
    class RecordingCompleted internal constructor()
    class RecordingTrashUpdated internal constructor()
    class RecordingSaved internal constructor(val uri: Uri)
    class RecordingFailed internal constructor(val exception: Exception)

    class TranscriptionStarted internal constructor(val recordingUri: Uri)
    class TranscriptionProgress internal constructor(
        val recordingUri: Uri,
        val phase: TranscriptionPhase,
        val fraction: Float,
    )
    class TranscriptionCompleted internal constructor(val recordingUri: Uri)
    class TranscriptionFailed internal constructor(val recordingUri: Uri, val cause: Throwable)
    class TranscriptionCancelled internal constructor(val recordingUri: Uri)
    class TranscriptDeleted internal constructor(val recordingUri: Uri)

    class ModelDownloadStarted internal constructor(val modelId: String)
    class ModelDownloadProgress internal constructor(val modelId: String, val fraction: Float)
    class ModelDownloadCompleted internal constructor(val modelId: String)
    class ModelDownloadFailed internal constructor(val modelId: String, val cause: Throwable)
    class ModelDownloadCancelled internal constructor(val modelId: String)
}

enum class TranscriptionPhase { DOWNLOADING_MODEL, DECODING, TRANSCRIBING, WRITING }

