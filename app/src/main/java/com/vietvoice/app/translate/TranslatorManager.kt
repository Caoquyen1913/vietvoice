package com.vietvoice.app.translate

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class TranslatorManager {

    companion object {
        private const val TAG = "TranslatorManager"
    }

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.CHINESE)
        .setTargetLanguage(TranslateLanguage.VIETNAMESE)
        .build()

    private val translator = Translation.getClient(options)
    private var isReady = false

    fun downloadModelIfNeeded(onReady: () -> Unit, onError: (Exception) -> Unit) {
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                isReady = true
                onReady()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download failed", e)
                onError(e)
            }
    }

    fun translate(text: String, onResult: (String) -> Unit) {
        if (!isReady || text.isBlank()) return
        translator.translate(text)
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { Log.e(TAG, "Translation failed: $text", it) }
    }

    fun close() {
        translator.close()
    }
}
