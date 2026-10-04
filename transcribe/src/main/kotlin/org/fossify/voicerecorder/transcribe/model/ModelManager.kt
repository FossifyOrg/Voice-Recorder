package org.fossify.voicerecorder.transcribe.model

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads and manages on-device Whisper model files for sherpa-onnx.
 *
 * Models live under `context.filesDir/sherpa-models/<id>/`. A download streams the
 * remote tar.bz2 directly through bzip2 + tar decoders into a `<id>.partial/` directory,
 * then atomically renames on success. There is no resume in v1: a cancelled or failed
 * download deletes the partial directory and starts over.
 */
class ModelManager(context: Context) {
    private val rootDir: File = File(context.filesDir, ROOT_DIR_NAME).apply { mkdirs() }
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // disabled — downloads can be long
        .build()

    fun getModelDir(spec: ModelSpec): File = File(rootDir, spec.id)
    fun getEncoderPath(spec: ModelSpec): String = File(getModelDir(spec), spec.encoderFile).absolutePath
    fun getDecoderPath(spec: ModelSpec): String = File(getModelDir(spec), spec.decoderFile).absolutePath
    fun getTokensPath(spec: ModelSpec): String = File(getModelDir(spec), spec.tokensFile).absolutePath

    fun isModelInstalled(spec: ModelSpec): Boolean {
        val dir = getModelDir(spec)
        if (!dir.isDirectory) return false
        return File(dir, spec.encoderFile).isFile &&
            File(dir, spec.decoderFile).isFile &&
            File(dir, spec.tokensFile).isFile
    }

    fun listInstalled(): List<ModelSpec> = ModelCatalog.ALL.filter { isModelInstalled(it) }

    fun deleteModel(spec: ModelSpec) {
        getModelDir(spec).deleteRecursively()
    }

    /**
     * Downloads [spec] and extracts the three required files. Idempotent: if already
     * installed, returns immediately.
     *
     * @param onProgress called periodically with (downloadedCompressedBytes, totalCompressedBytes).
     * @throws IOException for network errors, hash mismatch, or missing entries in the archive.
     * @throws kotlinx.coroutines.CancellationException-equivalent: if [isCancelled] flips, throws IOException("cancelled").
     */
    fun downloadModel(
        spec: ModelSpec,
        isCancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        if (isModelInstalled(spec)) {
            onProgress(spec.archiveSizeBytes, spec.archiveSizeBytes)
            return
        }

        val finalDir = getModelDir(spec)
        val partialDir = File(rootDir, "${spec.id}.partial")
        partialDir.deleteRecursively()
        partialDir.mkdirs()

        val request = Request.Builder().url(spec.archiveUrl).build()
        val response = httpClient.newCall(request).execute()
        val body = response.body
        try {
            if (!response.isSuccessful || body == null) {
                throw IOException("HTTP ${response.code} downloading ${spec.archiveUrl}")
            }

            val totalBytes = body.contentLength().takeIf { it > 0 } ?: spec.archiveSizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            val counting = CountingInputStream(DigestInputStream(body.byteStream(), digest)) { read ->
                if (isCancelled.get()) throw IOException("cancelled")
                onProgress(read, totalBytes)
            }

            BZip2CompressorInputStream(counting).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    var got = 0
                    val needed = setOf(spec.encoderFile, spec.decoderFile, spec.tokensFile)
                    while (true) {
                        if (isCancelled.get()) throw IOException("cancelled")
                        val entry = tar.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (!name.startsWith(spec.archiveRootDir)) continue
                        val rel = name.removePrefix(spec.archiveRootDir)
                        if (rel !in needed) continue
                        val outFile = File(partialDir, rel)
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (isCancelled.get()) throw IOException("cancelled")
                                val n = tar.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                        got++
                        if (got == needed.size) break
                    }
                    if (got != needed.size) {
                        throw IOException(
                            "archive missing required files (got $got of ${needed.size}) for ${spec.id}"
                        )
                    }
                }
            }

            spec.expectedSha256?.let { expected ->
                val actual = digest.digest().toHexLowercase()
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IOException(
                        "SHA-256 mismatch for ${spec.id}: expected $expected, got $actual"
                    )
                }
            }

            // Atomic-ish rename. On Android filesDir, both partial and final live on the
            // same filesystem so renameTo is atomic.
            finalDir.deleteRecursively()
            if (!partialDir.renameTo(finalDir)) {
                throw IOException("rename ${partialDir.absolutePath} → ${finalDir.absolutePath} failed")
            }
            onProgress(totalBytes, totalBytes)
        } catch (t: Throwable) {
            partialDir.deleteRecursively()
            throw t
        } finally {
            body?.close()
        }
    }

    private fun ByteArray.toHexLowercase(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format(Locale.ROOT, "%02x", b))
        return sb.toString()
    }

    /**
     * Wraps an InputStream and reports progress, but throttles callbacks so that downstream
     * decoders that read the source byte-by-byte (e.g. bzip2 header parsing) don't drown the
     * caller in thousands of progress events per second.
     */
    private class CountingInputStream(
        wrapped: InputStream,
        private val onRead: (totalBytes: Long) -> Unit,
    ) : FilterInputStream(wrapped) {
        private var total: Long = 0L
        private var lastReportedAtMs: Long = 0L
        private var lastReportedTotal: Long = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                total += 1
                maybeReport()
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                total += n
                maybeReport()
            }
            return n
        }

        private fun maybeReport() {
            val now = System.currentTimeMillis()
            if (total - lastReportedTotal >= REPORT_BYTES_THRESHOLD ||
                now - lastReportedAtMs >= REPORT_MS_THRESHOLD) {
                lastReportedTotal = total
                lastReportedAtMs = now
                onRead(total)
            }
        }
    }

    companion object {
        private const val ROOT_DIR_NAME = "sherpa-models"
        private const val REPORT_BYTES_THRESHOLD = 256L * 1024L
        private const val REPORT_MS_THRESHOLD = 250L
    }
}
