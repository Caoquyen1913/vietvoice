package com.vietvoice.app.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_ID = "vietvoice_tts"

        /** Mở màn hình cài TTS data — gọi khi onLanguageUnavailable */
        fun openTtsSettings(context: Context) {
            try {
                context.startActivity(
                    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open TTS install screen", e)
            }
        }
    }

    var onSpeakStart: (() -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null

    /** Gọi khi không tìm được engine nào đọc được tiếng Việt */
    var onLanguageUnavailable: (() -> Unit)? = null

    val isSpeaking = AtomicBoolean(false)
    private var isReady = false

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                if (setupVietnamese()) {
                    tts.setSpeechRate(1.1f)
                } else {
                    Log.e(TAG, "No Vietnamese TTS found on this device")
                    onLanguageUnavailable?.invoke()
                }
            } else {
                Log.e(TAG, "TTS init failed: $status")
                onLanguageUnavailable?.invoke()
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking.set(true)
                onSpeakStart?.invoke()
            }
            override fun onDone(utteranceId: String?) {
                isSpeaking.set(false)
                onSpeakDone?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeaking.set(false)
                onSpeakDone?.invoke()
            }
        })
    }

    /**
     * Thử set ngôn ngữ tiếng Việt — vi-VN trước, rồi vi.
     * Trả về true nếu thành công.
     */
    private fun setupVietnamese(): Boolean {
        val locales = listOf(Locale("vi", "VN"), Locale("vi"))
        for (locale in locales) {
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.d(TAG, "Vietnamese TTS OK: engine=${tts.defaultEngine} locale=$locale")
                isReady = true
                return true
            }
            Log.w(TAG, "setLanguage($locale) result=$result")
        }
        // Log tất cả engine có trên máy để debug
        Log.w(TAG, "Available TTS engines: ${tts.engines.joinToString { it.name }}")
        return false
    }

    /** Đọc text. Nếu TTS tiếng Việt không có thì bỏ qua — vẫn hiển thị overlay. */
    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
    }

    fun stop() {
        if (::tts.isInitialized) {
            tts.stop()
            isSpeaking.set(false)
        }
    }

    fun shutdown() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}
