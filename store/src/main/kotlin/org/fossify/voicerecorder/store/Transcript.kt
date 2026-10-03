package org.fossify.voicerecorder.store

/** Current sidecar JSON schema version. Bump when the on-disk format changes. */
const val TRANSCRIPT_SCHEMA_VERSION: Int = 1

/**
 * Per-recording transcription result. Persisted as a JSON sidecar file next to the
 * audio file (see [TranscriptStore]).
 */
data class Transcript(
    val schemaVersion: Int,
    val recordingUri: String,
    val recordingName: String,
    val engine: String,
    val engineVersion: String,
    val model: String,
    val modelSha256: String?,
    val language: String,
    val languageAutoDetected: Boolean,
    val createdAtIso: String,
    val durationMs: Long,
    val processingMs: Long?,
    val segments: List<TranscriptSegment>,
)

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
