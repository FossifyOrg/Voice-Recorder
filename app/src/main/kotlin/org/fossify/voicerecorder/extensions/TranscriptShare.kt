package org.fossify.voicerecorder.extensions

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.store.Recording
import org.fossify.voicerecorder.store.Transcript
import java.util.Locale

private const val MS_PER_SECOND = 1000L
private const val SEC_PER_MIN = 60L

/**
 * Plain-text rendering of a transcript suitable for clipboard / share-as-text.
 * Header line names the recording, followed by `[mm:ss] segment text` lines.
 */
fun Transcript.toShareableText(recording: Recording): String {
    val header = "Transcript of ${recording.title}"
    val lang = language.ifBlank { "?" }
    val durationLabel = formatTimestamp(durationMs)
    val subheader = "Duration: $durationLabel · Language: $lang"
    val body = segments.joinToString(separator = "\n") { seg ->
        "[${formatTimestamp(seg.startMs)}] ${seg.text}"
    }
    return "$header\n$subheader\n\n$body"
}

private fun formatTimestamp(ms: Long): String {
    val totalSec = ms / MS_PER_SECOND
    val mm = totalSec / SEC_PER_MIN
    val ss = totalSec % SEC_PER_MIN
    return String.format(Locale.ROOT, "%02d:%02d", mm, ss)
}

fun Context.buildShareTranscriptTextIntent(text: String, subject: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    return Intent.createChooser(send, getString(R.string.share_transcript))
}

fun Context.buildShareTranscriptJsonIntent(uri: Uri, subject: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, getString(R.string.share_transcript_json))
}
