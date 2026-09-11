package org.fossify.voicerecorder.transcribe.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.fossify.voicerecorder.store.TranscriptSegment
import org.fossify.voicerecorder.transcribe.audio.AudioDecoder
import org.fossify.voicerecorder.transcribe.audio.PcmChunk

data class ChunkTranscription(
    val language: String,
    val segments: List<TranscriptSegment>,
)

/**
 * Wraps a sherpa-onnx [OfflineRecognizer] configured for Whisper inference.
 *
 * Lifecycle: construct → call [transcribeChunk] for each [PcmChunk] in order →
 * [release]. Not thread-safe; one instance per inference job.
 */
class SherpaTranscriber(
    encoderPath: String,
    decoderPath: String,
    tokensPath: String,
    /** Whisper language code, e.g. "en"/"de". Empty string = auto-detect (multilingual models only). */
    language: String = "",
    numThreads: Int = 2,
) {

    private val recognizer: OfflineRecognizer

    init {
        val whisperConfig = OfflineWhisperModelConfig(
            encoder = encoderPath,
            decoder = decoderPath,
            language = language,
            task = "transcribe",
        )
        val modelConfig = OfflineModelConfig(
            whisper = whisperConfig,
            tokens = tokensPath,
            modelType = "whisper",
            numThreads = numThreads,
            debug = false,
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioDecoder.TARGET_SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    /**
     * Run one Whisper pass on [chunk]. Segment timestamps in the returned
     * [TranscriptSegment]s are absolute (offset by `chunk.startMs`).
     */
    fun transcribeChunk(chunk: PcmChunk): ChunkTranscription {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(chunk.samples, AudioDecoder.TARGET_SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return ChunkTranscription(
                language = result.lang.ifBlank { "" },
                segments = buildSegments(result, chunk),
            )
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer.release()
    }

    /**
     * Whisper without `enableTokenTimestamps`/`enableSegmentTimestamps` returns one big
     * `text` blob per call. We split it on sentence-ending punctuation and distribute the
     * chunk's time range proportionally to character count. Coarse but good enough for
     * tap-to-seek; can be upgraded later if user feedback warrants enabling token-level
     * timestamps (more memory, slower decode).
     */
    private fun buildSegments(result: OfflineRecognizerResult, chunk: PcmChunk): List<TranscriptSegment> {
        val text = result.text.trim()
        if (text.isEmpty() || isHallucinationArtifact(text)) return emptyList()

        val pieces = SENTENCE_SPLIT.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        if (pieces.size <= 1) {
            return listOf(TranscriptSegment(startMs = chunk.startMs, endMs = chunk.endMs, text = text))
        }

        val totalChars = pieces.sumOf { it.length }.coerceAtLeast(1)
        val totalMs = (chunk.endMs - chunk.startMs).coerceAtLeast(1L)
        val out = ArrayList<TranscriptSegment>(pieces.size)
        var elapsedChars = 0
        for (piece in pieces) {
            val startMs = chunk.startMs + totalMs * elapsedChars / totalChars
            elapsedChars += piece.length
            val endMs = chunk.startMs + totalMs * elapsedChars / totalChars
            out += TranscriptSegment(startMs = startMs, endMs = endMs, text = piece)
        }
        return out
    }

    /** Filters out the common Whisper "silence hallucinations" — bracketed annotations. */
    private fun isHallucinationArtifact(text: String): Boolean {
        val t = text.trim()
        return HALLUCINATION_PATTERNS.any { it.matches(t) }
    }

    companion object {
        private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
        private val HALLUCINATION_PATTERNS = listOf(
            Regex("\\[.*?\\]"),
            Regex("\\(.*?\\)"),
            Regex("(?i)you"),
        )
    }
}
