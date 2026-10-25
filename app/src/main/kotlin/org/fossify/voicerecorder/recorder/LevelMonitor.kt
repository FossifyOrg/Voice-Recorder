package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.fossify.voicerecorder.extensions.config
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Reads the microphone without recording, so the input level (with gain applied) can be previewed
 * before a recording starts. Levels are delivered on the main thread in MediaRecorder.getMaxAmplitude units.
 */
class LevelMonitor(
    context: Context,
    audioSource: Int,
    preferredDevice: AudioDeviceInfo?,
    private val onLevel: (Int) -> Unit
) {
    companion object {
        private const val UPDATE_INTERVAL_MS = 50L
    }

    private val sampleRate = context.config.samplingRate
    private val gainProcessor = GainProcessor(sampleRate)
    private val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(
        audioSource,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBufferSize * 2
    ).also {
        if (it.state != AudioRecord.STATE_INITIALIZED) {
            it.release()
            throw IllegalStateException("AudioRecord could not be initialized")
        }
        it.setPreferredDevice(preferredDevice)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isStopped = AtomicBoolean(false)
    private var worker: Thread? = null

    fun setGainDb(gainDb: Float) {
        gainProcessor.setGainDb(gainDb)
    }

    fun start() {
        audioRecord.startRecording()
        worker = thread(name = "LevelMonitor") {
            val pcm = ShortArray(minBufferSize / 2)
            var lastUpdate = 0L
            while (!isStopped.get()) {
                val count = audioRecord.read(pcm, 0, pcm.size)
                if (count > 0) {
                    gainProcessor.process(pcm, count)
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= UPDATE_INTERVAL_MS) {
                    lastUpdate = now
                    val level = gainProcessor.getAndResetPeak()
                    mainHandler.post {
                        if (!isStopped.get()) {
                            onLevel(level)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        isStopped.set(true)
        worker?.join()
        worker = null
        runCatching { audioRecord.stop() }
        audioRecord.release()
    }
}
