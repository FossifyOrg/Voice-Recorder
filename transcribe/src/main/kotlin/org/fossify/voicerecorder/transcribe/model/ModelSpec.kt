package org.fossify.voicerecorder.transcribe.model

/**
 * Static description of a sherpa-onnx Whisper model. Used by [ModelManager] to download,
 * verify, and resolve on-disk file paths.
 *
 * Archive layout: tar.bz2 produced by k2-fsa with a single top-level directory ([archiveRootDir])
 * containing many files. We selectively extract only [encoderFile], [decoderFile], and
 * [tokensFile] to keep disk footprint small (the archive ships both float32 and int8 weights).
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val archiveUrl: String,
    /** Compressed size in bytes per GitHub release metadata. Used for progress and as a sanity check. */
    val archiveSizeBytes: Long,
    /** Optional SHA-256 of the archive (hex). Verified after download if non-null. */
    val expectedSha256: String? = null,
    /** Top-level directory inside the archive, including trailing slash. */
    val archiveRootDir: String,
    val encoderFile: String,
    val decoderFile: String,
    val tokensFile: String,
    /** Approximate total disk usage after extraction, for the UI. */
    val approxExtractedBytes: Long,
    val isMultilingual: Boolean,
)
