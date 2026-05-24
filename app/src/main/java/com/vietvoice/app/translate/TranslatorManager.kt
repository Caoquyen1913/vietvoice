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

    /** true = model đã sẵn sàng dịch; false = fallback (chỉ hiện text gốc) */
    var isReady = false
        private set

    fun downloadModelIfNeeded(onReady: () -> Unit, onError: (Exception) -> Unit) {
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                isReady = true
                Log.d(TAG, "ML Kit ZH→VI model ready")
                onReady()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download failed (no Google services or network blocked?)", e)
                // KHÔNG gọi onError — thay vào đó gọi onReady với isReady=false
                // App vẫn chạy STT, chỉ thiếu bản dịch
                onReady()
            }
    }

    /**
     * Dịch text. Nếu model chưa sẵn sàng (download thất bại) → gọi onResult
     * với text gốc kèm chú thích "[chưa dịch được]" để overlay vẫn hiện.
     */
    fun translate(text: String, onResult: (String) -> Unit) {
        if (text.isBlank()) return
        if (!isReady) {
            // Fallback: hiện lại text Trung gốc
            onResult("[Cần mạng để tải model dịch] $text")
            return
        }
        translator.translate(text)
            .addOnSuccessListener { onResult(it) }
            .addOnFailureListener { e ->
                Log.e(TAG, "Translation failed: $text", e)
                onResult("[lỗi dịch] $text")   // fallback nếu dịch thất bại lúc runtime
            }
    }

    fun close() {
        translator.close()
    }
}
