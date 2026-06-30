package org.fossify.voicerecorder.transcribe.model

/**
 * Curated set of sherpa-onnx Whisper models we offer to the user.
 * Keep the list short — every entry is a distinct download path the user might trigger.
 */
object ModelCatalog {

    val WHISPER_TINY = ModelSpec(
        id = "whisper-tiny",
        displayName = "Whisper tiny (multilingual, int8)",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
        archiveSizeBytes = 116_204_861L,
        archiveRootDir = "sherpa-onnx-whisper-tiny/",
        encoderFile = "tiny-encoder.int8.onnx",
        decoderFile = "tiny-decoder.int8.onnx",
        tokensFile = "tiny-tokens.txt",
        approxExtractedBytes = 120_000_000L,
        isMultilingual = true,
    )

    val WHISPER_TINY_EN = ModelSpec(
        id = "whisper-tiny-en",
        displayName = "Whisper tiny (English, int8)",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
        archiveSizeBytes = 118_071_777L,
        archiveRootDir = "sherpa-onnx-whisper-tiny.en/",
        encoderFile = "tiny.en-encoder.int8.onnx",
        decoderFile = "tiny.en-decoder.int8.onnx",
        tokensFile = "tiny.en-tokens.txt",
        approxExtractedBytes = 120_000_000L,
        isMultilingual = false,
    )

    val WHISPER_BASE = ModelSpec(
        id = "whisper-base",
        displayName = "Whisper base (multilingual, int8)",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
        archiveSizeBytes = 207_557_382L,
        archiveRootDir = "sherpa-onnx-whisper-base/",
        encoderFile = "base-encoder.int8.onnx",
        decoderFile = "base-decoder.int8.onnx",
        tokensFile = "base-tokens.txt",
        approxExtractedBytes = 220_000_000L,
        isMultilingual = true,
    )

    val ALL: List<ModelSpec> = listOf(WHISPER_TINY, WHISPER_TINY_EN, WHISPER_BASE)
    val DEFAULT: ModelSpec = WHISPER_TINY

    fun byId(id: String): ModelSpec? = ALL.firstOrNull { it.id == id }
}
