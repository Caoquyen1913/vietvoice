package com.vietvoice.app.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelDownloader {

    private const val TAG = "ModelDownloader"

    // --- Vosk STT model (tiếng Trung) ---
    private const val VOSK_MODEL_NAME = "vosk-model-small-cn-0.22"
    private const val VOSK_MODEL_URL  = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

    fun getModelPath(context: Context): String =
        File(context.filesDir, "models/$VOSK_MODEL_NAME").absolutePath

    fun isModelDownloaded(context: Context): Boolean =
        File(context.filesDir, "models/$VOSK_MODEL_NAME").exists()

    fun download(context: Context, onProgress: (progress: Int, error: Exception?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
                val zipFile   = File(context.filesDir, "models_cache/$VOSK_MODEL_NAME.zip")
                zipFile.parentFile?.mkdirs()

                downloadFile(VOSK_MODEL_URL, zipFile) { p ->
                    CoroutineScope(Dispatchers.Main).launch { onProgress(p, null) }
                }
                extractZip(zipFile, modelsDir)
                zipFile.delete()
                withContext(Dispatchers.Main) { onProgress(100, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Vosk download failed", e)
                withContext(Dispatchers.Main) { onProgress(0, e) }
            }
        }
    }

    // --- sherpa-onnx TTS model (giọng tiếng Việt) ---
    private const val TTS_MODEL_DIR_NAME = "vits-piper-vi_VN-vais1000-medium-int8"
    private const val TTS_MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-vi_VN-vais1000-medium-int8.tar.bz2"

    fun getTtsModelPath(context: Context): String =
        File(context.filesDir, "models/$TTS_MODEL_DIR_NAME").absolutePath

    fun isTtsModelDownloaded(context: Context): Boolean {
        val dir = File(context.filesDir, "models/$TTS_MODEL_DIR_NAME")
        return dir.exists() && dir.walk().any { it.extension == "onnx" }
    }

    fun downloadTts(context: Context, onProgress: (progress: Int, error: Exception?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
                val archiveFile = File(context.filesDir, "models_cache/tts_vi.tar.bz2")
                archiveFile.parentFile?.mkdirs()

                downloadFile(TTS_MODEL_URL, archiveFile) { p ->
                    CoroutineScope(Dispatchers.Main).launch { onProgress(p, null) }
                }
                withContext(Dispatchers.Main) { onProgress(97, null) }
                extractTarBz2(archiveFile, modelsDir)
                archiveFile.delete()
                withContext(Dispatchers.Main) { onProgress(100, null) }
            } catch (e: Exception) {
                Log.e(TAG, "TTS model download failed", e)
                withContext(Dispatchers.Main) { onProgress(0, e) }
            }
        }
    }

    // --- Shared helpers ---

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).also { it.connect() }
        val fileLength = connection.contentLength
        var downloaded = 0L
        FileOutputStream(dest).use { out ->
            connection.inputStream.use { inp ->
                val buf = ByteArray(8192)
                var n = inp.read(buf)
                while (n >= 0) {
                    out.write(buf, 0, n)
                    downloaded += n
                    if (fileLength > 0) onProgress((downloaded * 95 / fileLength).toInt())
                    n = inp.read(buf)
                }
            }
        }
        connection.disconnect()
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(targetDir, entry.name)
                if (entry.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); FileOutputStream(out).use { zip.copyTo(it) } }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun extractTarBz2(archiveFile: File, targetDir: File) {
        TarArchiveInputStream(
            BZip2CompressorInputStream(archiveFile.inputStream().buffered())
        ).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val out = File(targetDir, entry.name)
                if (entry.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); FileOutputStream(out).use { tar.copyTo(it) } }
                entry = tar.nextTarEntry
            }
        }
    }
}
