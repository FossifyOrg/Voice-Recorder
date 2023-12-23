package org.fossify.voicerecorder.models

import android.net.Uri

class Events {
    class RecordingDuration internal constructor(val duration: Int)
    class RecordingStatus internal constructor(val status: Int)
    class RecordingAmplitude internal constructor(val amplitude: Int)
    class RecordingCompleted internal constructor()
    class RecordingTrashUpdated internal constructor()
    class RecordingSaved internal constructor(val uri: Uri?)
}
