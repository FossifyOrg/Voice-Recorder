package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.extensions.config
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class WavRecorder(val context: Context) : Recorder {
    private var audioRecord: AudioRecord? = null
    private var recordFile: File? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var bufferSize = 0
    private var isPaused = AtomicBoolean(false)
    private var isStopped = AtomicBoolean(false)
    private var isRecording = AtomicBoolean(false)
    private var amplitude = AtomicInteger(0)
    private var recordingThread: Thread? = null

    companion object {
        private const val RECORDER_BPP = 16 // bits per sample
    }

    override fun setOutputFile(path: String) {
        recordFile = File(path)
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        fileDescriptor = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
    }

    override fun prepare() {
        val sampleRate = context.config.samplingRate
        val channelConfig = AudioFormat.CHANNEL_IN_MONO

        bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw RuntimeException("Invalid buffer size")
        }

        @SuppressLint("MissingPermission")
        audioRecord = AudioRecord(
            context.config.microphoneMode,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw RuntimeException("AudioRecord initialization failed")
        }
    }

    override fun start() {
        audioRecord?.startRecording()
        isRecording.set(true)
        isStopped.set(false)
        isPaused.set(false)

        recordingThread = Thread({
            writeAudioDataToFile()
        }, "WavRecorder Thread")
        recordingThread?.start()
    }

    override fun stop() {
        isStopped.set(true)
        isRecording.set(false)
        audioRecord?.stop()
    }

    override fun pause() {
        isPaused.set(true)
    }

    override fun resume() {
        isPaused.set(false)
    }

    override fun release() {
        audioRecord?.release()
        audioRecord = null
        fileDescriptor?.close()
        fileDescriptor = null
    }

    override fun getMaxAmplitude(): Int {
        return amplitude.get()
    }

    private fun writeAudioDataToFile() {
        val data = ByteArray(bufferSize)
        val fos: FileOutputStream? = try {
            if (fileDescriptor != null) {
                FileOutputStream(fileDescriptor!!.fileDescriptor)
            } else {
                FileOutputStream(recordFile!!)
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            null
        }

        if (fos != null) {
            writeEmptyHeader(fos)
            var chunksCount = 0
            val shortBuffer = ByteBuffer.allocate(2)
            shortBuffer.order(ByteOrder.LITTLE_ENDIAN)

            while (isRecording.get()) {
                if (!isPaused.get()) {
                    val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                    if (read > 0) {
                        chunksCount += read
                        
                        // Calculate amplitude
                        var sum = 0L
                        var i = 0
                        while (i < bufferSize) {
                            shortBuffer.put(data[i])
                            shortBuffer.put(data[i + 1])
                            sum += abs(shortBuffer.getShort(0).toInt())
                            shortBuffer.clear()
                            i += 2
                        }
                        amplitude.set((sum / (bufferSize / 16)).toInt())

                        try {
                            fos.write(data)
                        } catch (e: IOException) {
                            e.printStackTrace()
                            break
                        }
                    }
                }
            }

            try {
                fos.flush()
                fos.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            // Write the WAV header with correct file size
            val file = if (fileDescriptor != null) {
                // For file descriptor, we need to get the file from the path
                recordFile
            } else {
                recordFile
            }
            file?.let { setWaveFileHeader(it, 1) } // mono channel
        }
    }

    private fun writeEmptyHeader(fos: FileOutputStream) {
        try {
            val header = ByteArray(44)
            fos.write(header)
            fos.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun setWaveFileHeader(file: File, channels: Int) {
        val fileSize = file.length() - 44
        val totalSize = fileSize + 36
        val sampleRate = context.config.samplingRate.toLong()
        val byteRate = sampleRate * channels * (RECORDER_BPP / 8)

        try {
            val wavFile = RandomAccessFile(file, "rw")
            wavFile.seek(0)
            wavFile.write(generateHeader(fileSize, totalSize, sampleRate, channels, byteRate))
            wavFile.close()
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun generateHeader(
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Long,
        channels: Int,
        byteRate: Long
    ): ByteArray {
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()  // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()  // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16  // 16 for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1  // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * (RECORDER_BPP / 8)).toByte()  // block align
        header[33] = 0
        header[34] = RECORDER_BPP.toByte()  // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        return header
    }
}
