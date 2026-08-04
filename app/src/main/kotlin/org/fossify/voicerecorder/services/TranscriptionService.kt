package org.fossify.voicerecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fossify.commons.helpers.isQPlus
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.recordingStore
import org.fossify.voicerecorder.helpers.ACTION_CANCEL_MODEL_DOWNLOAD
import org.fossify.voicerecorder.helpers.ACTION_CANCEL_TRANSCRIPTION
import org.fossify.voicerecorder.helpers.ACTION_DOWNLOAD_MODEL
import org.fossify.voicerecorder.helpers.EXTRA_LANGUAGE
import org.fossify.voicerecorder.helpers.EXTRA_MODEL_ID
import org.fossify.voicerecorder.helpers.EXTRA_RECORDING_URI
import org.fossify.voicerecorder.helpers.TRANSCRIPTION_NOTIF_ID
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.models.TranscriptionPhase
import org.fossify.voicerecorder.store.Recording
import org.fossify.voicerecorder.store.TRANSCRIPT_SCHEMA_VERSION
import org.fossify.voicerecorder.store.Transcript
import org.fossify.voicerecorder.store.TranscriptSegment
import org.fossify.voicerecorder.store.TranscriptStore
import org.fossify.voicerecorder.transcribe.audio.AudioDecoder
import org.fossify.voicerecorder.transcribe.engine.SherpaTranscriber
import org.fossify.voicerecorder.transcribe.model.ModelCatalog
import org.fossify.voicerecorder.transcribe.model.ModelManager
import org.greenrobot.eventbus.EventBus
import org.fossify.voicerecorder.transcribe.audio.PcmChunk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Foreground service that runs Whisper transcription end-to-end:
 * download model (if needed) → decode audio → run inference → write sidecar JSON.
 * Posts progress on EventBus and shows a cancellable notification.
 *
 * v1: single-job; concurrent start requests are rejected while a job is active.
 */
@Suppress("TooManyFunctions")
class TranscriptionService : Service() {

    companion object {
        var isRunning: Boolean = false
            private set
        var downloadingModelId: String? = null
            private set
        /** Wall-clock ms when the current transcription started, or null if none is running. */
        var transcriptionStartMs: Long? = null
            private set

        private const val TAG = "TranscriptionService"
        private const val CHANNEL_ID = "voice_recorder_transcription"
        private const val ENGINE_NAME = "sherpa-onnx"
        private const val ENGINE_VERSION = "1.12.40"
        private const val PCT_MAX = 100
        private const val MS_PER_SECOND = 1000L
        private const val CHUNK_QUEUE_CAPACITY = 2
        private const val QUEUE_OFFER_TIMEOUT_MS = 100L
        private val EOF_SENTINEL = Any()

        /** Smooth-progress ticker cadence; the bar updates this often during a chunk. */
        private const val PROGRESS_TICK_MS = 400L

        /** Cap interpolation inside a chunk so the bar can't reach the chunk's end fraction
         *  before the chunk has actually finished — that would cause a visible reversal. */
        private const val INTERP_MAX_RATIO = 0.95f

        /** Seed for the rolling avg until the first chunk completes. ~Whisper-tiny on a
         *  midrange phone; the EMA converges to the real value within 1–2 chunks. */
        private const val INITIAL_CHUNK_WALL_MS = 6_000L

        /** Higher = faster adaptation, more jitter; lower = smoother but slower to track. */
        private const val EMA_ALPHA = 0.4f
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isCancelled = AtomicBoolean(false)
    private var currentJob: Job? = null
    private var currentRecordingUri: Uri? = null
    private var currentDownloadModelId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CANCEL_TRANSCRIPTION, ACTION_CANCEL_MODEL_DOWNLOAD -> handleCancel()
            ACTION_DOWNLOAD_MODEL -> handleDownloadModel(intent)
            else -> handleStart(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        isRunning = false
    }

    private fun handleStart(intent: Intent?) {
        intent ?: return
        if (currentJob?.isActive == true) {
            Log.w(TAG, "transcription already in progress; ignoring new start")
            return
        }
        val uriStr = intent.getStringExtra(EXTRA_RECORDING_URI) ?: run {
            stopSelf(); return
        }
        val recordingUri = uriStr.toUri()
        val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
            ?: config.transcribeModelId
            ?: ModelCatalog.DEFAULT.id
        val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: config.transcribeLanguage

        currentRecordingUri = recordingUri
        isCancelled.set(false)
        isRunning = true
        transcriptionStartMs = System.currentTimeMillis()
        startForegroundCompat(buildNotification(getString(R.string.transcribing), 0, indeterminate = true))
        EventBus.getDefault().post(Events.TranscriptionStarted(recordingUri))

        currentJob = scope.launch {
            try {
                runPipeline(recordingUri, modelId, language)
                EventBus.getDefault().post(Events.TranscriptionCompleted(recordingUri))
            } catch (@Suppress("SwallowedException") _: TranscriptionCancelledException) {
                Log.i(TAG, "transcription cancelled for $recordingUri")
                EventBus.getDefault().post(Events.TranscriptionCancelled(recordingUri))
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                // Fail-safe: any failure in the pipeline must surface as TranscriptionFailed
                // so the UI can leave the busy state. Specific causes are logged below.
                Log.e(TAG, "transcription failed for $recordingUri", t)
                EventBus.getDefault().post(Events.TranscriptionFailed(recordingUri, t))
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isRunning = false
                transcriptionStartMs = null
                currentRecordingUri = null
                stopSelf()
            }
        }
    }

    private fun handleCancel() {
        isCancelled.set(true)
        currentJob?.cancel()
    }

    private fun handleDownloadModel(intent: Intent) {
        if (currentJob?.isActive == true) {
            Log.w(TAG, "another job is running; ignoring model download request")
            return
        }
        val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: run {
            stopSelf(); return
        }
        val spec = ModelCatalog.byId(modelId) ?: run {
            stopSelf(); return
        }

        currentDownloadModelId = modelId
        downloadingModelId = modelId
        isCancelled.set(false)
        isRunning = true
        startForegroundCompat(buildNotification(getString(R.string.downloading_model), 0, indeterminate = true))
        EventBus.getDefault().post(Events.ModelDownloadStarted(modelId))

        currentJob = scope.launch {
            try {
                val modelManager = ModelManager(this@TranscriptionService)
                modelManager.downloadModel(spec, isCancelled) { downloaded, total ->
                    throwIfCancelled()
                    val frac = if (total > 0L) downloaded.toFloat() / total else 0f
                    EventBus.getDefault().post(Events.ModelDownloadProgress(modelId, frac))
                    val pct = (frac * PCT_MAX).toInt().coerceIn(0, PCT_MAX)
                    startForegroundCompat(
                        buildNotification(getString(R.string.downloading_model), pct, indeterminate = false)
                    )
                }
                EventBus.getDefault().post(Events.ModelDownloadCompleted(modelId))
            } catch (@Suppress("SwallowedException") _: TranscriptionCancelledException) {
                Log.i(TAG, "model download cancelled for $modelId")
                EventBus.getDefault().post(Events.ModelDownloadCancelled(modelId))
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                // Fail-safe: any failure must surface so UI can leave the busy state.
                // ModelManager throws IOException("cancelled") on cancel — treat that as Cancelled, not Failed.
                if (isCancelled.get()) {
                    Log.i(TAG, "model download cancelled for $modelId")
                    EventBus.getDefault().post(Events.ModelDownloadCancelled(modelId))
                } else {
                    Log.e(TAG, "model download failed for $modelId", t)
                    EventBus.getDefault().post(Events.ModelDownloadFailed(modelId, t))
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isRunning = false
                downloadingModelId = null
                currentDownloadModelId = null
                stopSelf()
            }
        }
    }

    private fun runPipeline(recordingUri: Uri, modelId: String, language: String) {
        val pipelineStartMs = System.currentTimeMillis()
        val spec = ModelCatalog.byId(modelId) ?: ModelCatalog.DEFAULT
        val modelManager = ModelManager(this)

        if (!modelManager.isModelInstalled(spec)) {
            postProgress(recordingUri, TranscriptionPhase.DOWNLOADING_MODEL, 0f, R.string.downloading_model)
            modelManager.downloadModel(spec, isCancelled) { downloaded, total ->
                throwIfCancelled()
                val frac = if (total > 0L) downloaded.toFloat() / total else 0f
                postProgress(recordingUri, TranscriptionPhase.DOWNLOADING_MODEL, frac, R.string.downloading_model)
            }
        }
        throwIfCancelled()

        val recording = findRecording(recordingUri)
            ?: error("recording not found for uri $recordingUri")
        val transcriptStore = TranscriptStore(this, config.saveRecordingsFolder)
        val decoder = AudioDecoder(this, recordingUri)

        val transcriber = SherpaTranscriber(
            encoderPath = modelManager.getEncoderPath(spec),
            decoderPath = modelManager.getDecoderPath(spec),
            tokensPath = modelManager.getTokensPath(spec),
            language = language,
        )

        val segments = mutableListOf<TranscriptSegment>()
        var detectedLanguage = language
        val languageWasAuto = language.isBlank()

        try {
            postProgress(recordingUri, TranscriptionPhase.DECODING, 0f, R.string.transcribing)
            val expectedDurationMs = recording.duration.toLong() * MS_PER_SECOND
            val pipelineResult = runPipelinedTranscribe(
                decoder = decoder,
                transcriber = transcriber,
                recordingUri = recordingUri,
                expectedDurationMs = expectedDurationMs,
                segments = segments,
            ) { language ->
                if (detectedLanguage.isBlank() && language.isNotBlank()) detectedLanguage = language
            }
            val totalDurationMs = pipelineResult

            throwIfCancelled()
            postProgress(recordingUri, TranscriptionPhase.WRITING, 1f, R.string.transcribing)
            val transcript = Transcript(
                schemaVersion = TRANSCRIPT_SCHEMA_VERSION,
                recordingUri = recordingUri.toString(),
                recordingName = recording.title,
                engine = ENGINE_NAME,
                engineVersion = ENGINE_VERSION,
                model = spec.id,
                modelSha256 = spec.expectedSha256,
                language = detectedLanguage.ifBlank { "" },
                languageAutoDetected = languageWasAuto,
                createdAtIso = nowIso(),
                durationMs = totalDurationMs,
                processingMs = System.currentTimeMillis() - pipelineStartMs,
                segments = segments,
            )
            transcriptStore.write(recording, transcript)
        } finally {
            transcriber.release()
        }
    }

    /**
     * Runs the decoder on a worker thread and the recognizer on this coroutine, connected by a
     * small bounded queue. The decoder hides its I/O / MediaCodec wait behind whatever the
     * recognizer is currently doing on a previously emitted chunk.
     *
     * Progress is posted from the *consumer* side (chunkEndMs / expectedDurationMs) so the bar
     * reflects audio actually transcribed, not audio merely decoded ahead.
     *
     * Returns the total decoded duration in ms (from MediaExtractor metadata).
     */
    private fun runPipelinedTranscribe(
        decoder: AudioDecoder,
        transcriber: SherpaTranscriber,
        recordingUri: Uri,
        expectedDurationMs: Long,
        segments: MutableList<TranscriptSegment>,
        onLanguageDetected: (String) -> Unit,
    ): Long {
        val queue = ArrayBlockingQueue<Any>(CHUNK_QUEUE_CAPACITY)
        val decoderError = AtomicReference<Throwable?>()
        val totalDurationMsRef = AtomicLong()
        val progress = ChunkProgress()
        val tickerStop = AtomicBoolean(false)

        val tickerThread = thread(
            start = true, name = "TranscriptionProgressTicker", isDaemon = true,
        ) {
            runProgressTicker(recordingUri, progress, tickerStop)
        }

        val decoderThread = thread(
            start = true, name = "TranscriptionDecoder", isDaemon = true,
        ) {
            try {
                val total = decoder.decodeChunks(isCancelled = isCancelled) { chunk ->
                    pollOfferUntilCancelled(queue, chunk)
                }
                totalDurationMsRef.set(total)
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                decoderError.set(t)
            } finally {
                runCatching { queue.put(EOF_SENTINEL) }
            }
        }

        try {
            drainAndTranscribe(
                queue = queue,
                transcriber = transcriber,
                expectedDurationMs = expectedDurationMs,
                segments = segments,
                onLanguageDetected = onLanguageDetected,
                progress = progress,
            )
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            isCancelled.set(true)
            throw t
        } finally {
            tickerStop.set(true)
            tickerThread.interrupt()
            decoderThread.join()
            tickerThread.join()
        }

        decoderError.get()?.let { throw it }
        return totalDurationMsRef.get()
    }

    /**
     * Small bag of state shared between [drainAndTranscribe] (writer) and the progress
     * ticker (reader). All fields are atomic so the reader sees consistent snapshots.
     */
    private class ChunkProgress {
        val chunkStartFraction = AtomicReference(0f)
        val chunkEndFraction = AtomicReference(0f)
        val chunkStartedAtMs = AtomicLong(0L)
        val avgChunkWallMs = AtomicLong(INITIAL_CHUNK_WALL_MS)
        val transcribing = AtomicBoolean(false)
    }

    private fun runProgressTicker(
        recordingUri: Uri,
        progress: ChunkProgress,
        stop: AtomicBoolean,
    ) {
        var lastPostedPct = -1
        var keepRunning = true
        while (keepRunning && !stop.get() && !isCancelled.get()) {
            keepRunning = sleepInterruptibly(PROGRESS_TICK_MS)
            if (keepRunning && !stop.get() && !isCancelled.get()) {
                lastPostedPct = postInterpolatedProgress(recordingUri, progress, lastPostedPct)
            }
        }
    }

    private fun sleepInterruptibly(durationMs: Long): Boolean {
        return try {
            Thread.sleep(durationMs)
            true
        } catch (@Suppress("SwallowedException") _: InterruptedException) {
            false
        }
    }

    private fun postInterpolatedProgress(
        recordingUri: Uri,
        progress: ChunkProgress,
        lastPostedPct: Int,
    ): Int {
        val fraction = computeInterpolatedFraction(progress).coerceIn(0f, 1f)
        val pct = (fraction * PCT_MAX).toInt().coerceIn(0, PCT_MAX)
        // Always emit the EventBus event so the activity's bar moves smoothly,
        // but only refresh the foreground notification when the rounded % changes —
        // calling startForeground multiple times per second is needlessly expensive.
        EventBus.getDefault().post(
            Events.TranscriptionProgress(recordingUri, TranscriptionPhase.TRANSCRIBING, fraction)
        )
        if (pct == lastPostedPct) return lastPostedPct
        startForegroundCompat(
            buildNotification(getString(R.string.transcribing), pct, indeterminate = false)
        )
        return pct
    }

    private fun computeInterpolatedFraction(progress: ChunkProgress): Float {
        val startF = progress.chunkStartFraction.get()
        val endF = progress.chunkEndFraction.get()
        if (!progress.transcribing.get()) return endF
        val startedAt = progress.chunkStartedAtMs.get()
        if (startedAt <= 0L) return startF
        val avgMs = progress.avgChunkWallMs.get().coerceAtLeast(1L)
        val elapsed = System.currentTimeMillis() - startedAt
        val ratio = (elapsed.toFloat() / avgMs).coerceIn(0f, INTERP_MAX_RATIO)
        return startF + ratio * (endF - startF)
    }

    private fun pollOfferUntilCancelled(queue: ArrayBlockingQueue<Any>, chunk: PcmChunk): Boolean {
        while (!isCancelled.get()) {
            if (queue.offer(chunk, QUEUE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun drainAndTranscribe(
        queue: ArrayBlockingQueue<Any>,
        transcriber: SherpaTranscriber,
        expectedDurationMs: Long,
        segments: MutableList<TranscriptSegment>,
        onLanguageDetected: (String) -> Unit,
        progress: ChunkProgress,
    ) {
        while (true) {
            val item = queue.take()
            if (item === EOF_SENTINEL) return
            if (isCancelled.get()) return
            val chunk = item as PcmChunk

            if (expectedDurationMs > 0L) {
                progress.chunkStartFraction.set(
                    (chunk.startMs.toFloat() / expectedDurationMs).coerceIn(0f, 1f)
                )
                progress.chunkEndFraction.set(
                    (chunk.endMs.toFloat() / expectedDurationMs).coerceIn(0f, 1f)
                )
            }
            progress.chunkStartedAtMs.set(System.currentTimeMillis())
            progress.transcribing.set(true)

            val result = transcriber.transcribeChunk(chunk)
            onLanguageDetected(result.language)
            segments += result.segments

            val wallMs = System.currentTimeMillis() - progress.chunkStartedAtMs.get()
            val prevAvg = progress.avgChunkWallMs.get()
            val newAvg = (EMA_ALPHA * wallMs + (1f - EMA_ALPHA) * prevAvg).toLong().coerceAtLeast(1L)
            progress.avgChunkWallMs.set(newAvg)
            progress.transcribing.set(false)
        }
    }

    private fun findRecording(uri: Uri): Recording? {
        val store = recordingStore
        return store.all().firstOrNull { it.uri == uri }
            ?: store.all(trashed = true).firstOrNull { it.uri == uri }
    }

    private fun throwIfCancelled() {
        if (isCancelled.get()) throw TranscriptionCancelledException()
    }

    private fun postProgress(uri: Uri, phase: TranscriptionPhase, fraction: Float, statusRes: Int) {
        EventBus.getDefault().post(Events.TranscriptionProgress(uri, phase, fraction))
        val pct = (fraction * PCT_MAX).toInt().coerceIn(0, PCT_MAX)
        startForegroundCompat(buildNotification(getString(statusRes), pct, indeterminate = false))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (isQPlus()) {
            startForeground(
                TRANSCRIPTION_NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(TRANSCRIPTION_NOTIF_ID, notification)
        }
    }

    private fun buildNotification(statusText: String, progress: Int, indeterminate: Boolean): Notification {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transcribe_settings),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null) }
        )

        val cancelIntent = Intent(this, TranscriptionService::class.java).apply {
            action = ACTION_CANCEL_TRANSCRIPTION
        }
        val cancelPi = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_graphic_eq_vector)
            .setProgress(PCT_MAX, progress, indeterminate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setOngoing(true)
            .addAction(0, getString(R.string.cancel_transcription), cancelPi)
            .build()
    }

    private fun nowIso(): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return df.format(Date())
    }

    private class TranscriptionCancelledException : RuntimeException()
}
