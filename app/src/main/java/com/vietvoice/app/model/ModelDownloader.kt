package com.vietvoice.app.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val MODEL_NAME = "vosk-model-small-cn-0.22"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

    fun getModelPath(context: Context): String =
        File(context.filesDir, "models/$MODEL_NAME").absolutePath

    fun isModelDownloaded(context: Context): Boolean =
        File(context.filesDir, "models/$MODEL_NAME").exists()

    fun download(context: Context, onProgress: (progress: Int, error: Exception?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelsDir = File(context.filesDir, "models")
                modelsDir.mkdirs()
                val zipFile = File(context.filesDir, "models_cache/${MODEL_NAME}.zip")
                zipFile.parentFile?.mkdirs()

                val url = URL(MODEL_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                val fileLength = connection.contentLength
                var downloaded = 0L

                FileOutputStream(zipFile).use { output ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        var bytes = input.read(buffer)
                        while (bytes >= 0) {
                            output.write(buffer, 0, bytes)
                            downloaded += bytes
                            if (fileLength > 0) {
                                val progress = (downloaded * 95 / fileLength).toInt()
                                withContext(Dispatchers.Main) { onProgress(progress, null) }
                            }
                            bytes = input.read(buffer)
                        }
                    }
                }
                connection.disconnect()

                extractZip(zipFile, modelsDir)
                zipFile.delete()

                withContext(Dispatchers.Main) { onProgress(100, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) { onProgress(0, e) }
            }
        }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8192)
                        var len = zip.read(buffer)
                        while (len > 0) {
                            output.write(buffer, 0, len)
                            len = zip.read(buffer)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
