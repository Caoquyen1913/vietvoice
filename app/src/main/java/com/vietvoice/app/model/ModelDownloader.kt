package com.vietvoice.app.model

import android.content.Context
import android.util.Log
import com.vietvoice.app.config.TranslationDirection
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

    // --- STT (Vosk) ---

    fun getSttModelPath(context: Context, dir: TranslationDirection): String =
        File(context.filesDir, "models/${dir.sttModelDir}").absolutePath

    fun isSttModelDownloaded(context: Context, dir: TranslationDirection): Boolean =
        File(context.filesDir, "models/${dir.sttModelDir}").exists()

    fun downloadStt(
        context: Context,
        dir: TranslationDirection,
        onProgress: (progress: Int, error: Exception?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
                val zipFile = File(context.filesDir, "models_cache/${dir.sttModelDir}.zip")
                zipFile.parentFile?.mkdirs()

                val assetPath = "models/${dir.sttModelDir}.zip"
                if (isAssetAvailable(context, assetPath)) {
                    Log.i(TAG, "Extracting STT from bundled asset: $assetPath")
                    copyAssetToFile(context, assetPath, zipFile) { p ->
                        CoroutineScope(Dispatchers.Main).launch { onProgress(p, null) }
                    }
                } else {
                    Log.i(TAG, "Downloading STT from network: ${dir.sttModelUrl}")
                    downloadFile(dir.sttModelUrl, zipFile) { p ->
                        CoroutineScope(Dispatchers.Main).launch { onProgress(p, null) }
                    }
                }
                extractZip(zipFile, modelsDir)
                zipFile.delete()
                withContext(Dispatchers.Main) { onProgress(100, null) }
            } catch (e: Exception) {
                Log.e(TAG, "STT install failed (${dir.sttModelDir})", e)
                withContext(Dispatchers.Main) { onProgress(0, e) }
            }
        }
    }

    // --- TTS (sherpa-onnx) ---

    fun getTtsModelPath(context: Context, dir: TranslationDirection): String =
        File(context.filesDir, "models/${dir.ttsModelDir}").absolutePath

    fun isTtsModelDownloaded(context: Context, dir: TranslationDirection): Boolean {
        val d = File(context.filesDir, "models/${dir.ttsModelDir}")
        return d.exists() && d.walk().any { it.extension == "onnx" }
    }

    fun downloadTts(
        context: Context,
        dir: TranslationDirection,
        onProgress: (progress: Int, error: Exception?) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
                val archiveFile = File(context.filesDir, "models_cache/${dir.ttsModelDir}.tar.bz2")
                archiveFile.parentFile?.mkdirs()

                val assetPath = "models/${dir.ttsModelDir}.tar.bz2"
                if (isAssetAvailable(context, assetPath)) {
                    Log.i(TAG, "Extracting TTS from bundled asset: $assetPath")
                    copyAssetToFile(context, assetPath, archiveFile) { p ->
                        CoroutineScope(Dispatchers.Main).launch { onProgress(p, null) }
                    }
                } else {
                    Log.i(TAG, "Downloading TTS from network: ${dir.ttsModelUrl}")
                    downloadFile(dir.ttsModelUrl, archiveFile) { p ->
                        CoroutineScope(Dispatchers.Main).launch { onProgress(p, null) }
                    }
                }
                withContext(Dispatchers.Main) { onProgress(97, null) }
                extractTarBz2(archiveFile, modelsDir)
                archiveFile.delete()
                withContext(Dispatchers.Main) { onProgress(100, null) }
            } catch (e: Exception) {
                Log.e(TAG, "TTS install failed (${dir.ttsModelDir})", e)
                withContext(Dispatchers.Main) { onProgress(0, e) }
            }
        }
    }

    // --- Asset helpers ---
    // Assets are stored uncompressed (aaptOptions { noCompress "zip","bz2" }), so
    // openFd() works and gives the real file size for progress tracking.

    private fun isAssetAvailable(ctx: Context, path: String): Boolean =
        try { ctx.assets.open(path).close(); true } catch (_: Exception) { false }

    private fun copyAssetToFile(
        ctx: Context,
        assetPath: String,
        dest: File,
        onProgress: (Int) -> Unit
    ) {
        val afd = ctx.assets.openFd(assetPath)
        val totalBytes = afd.length
        afd.createInputStream().use { inp ->
            dest.outputStream().use { out ->
                val buf = ByteArray(8192)
                var copied = 0L
                var n = inp.read(buf)
                while (n >= 0) {
                    out.write(buf, 0, n)
                    copied += n
                    if (totalBytes > 0) onProgress((copied * 95 / totalBytes).toInt())
                    n = inp.read(buf)
                }
            }
        }
        afd.close()
    }

    // --- Network helpers ---

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

    // --- Extraction helpers ---

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
