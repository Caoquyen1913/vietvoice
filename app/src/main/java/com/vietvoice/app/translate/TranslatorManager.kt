package com.vietvoice.app.translate

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class TranslatorManager(source: String = TranslateLanguage.CHINESE,
                        target: String = TranslateLanguage.VIETNAMESE) {

    companion object {
        private const val TAG = "TranslatorManager"
    }

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(source)
        .setTargetLanguage(target)
        .build()

    private val translator = Translation.getClient(options)

    var isReady = false
        private set

    fun downloadModelIfNeeded(onReady: () -> Unit, onError: (Exception) -> Unit) {
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                isReady = true
                Log.d(TAG, "ML Kit translation model ready ($options)")
                onReady()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download failed (no Google services or network blocked?)", e)
                onReady()
            }
    }

    fun translate(text: String, onResult: (String) -> Unit) {
        if (text.isBlank()) return
        if (!isReady) {
            onResult("[Cần mạng để tải model dịch] $text")
            return
        }
        translator.translate(text)
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { e ->
                Log.e(TAG, "Translation failed: $text", e)
                onResult("[lỗi dịch] $text")
            }
    }

    fun close() {
        translator.close()
    }
}
