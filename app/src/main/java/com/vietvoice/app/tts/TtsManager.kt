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
    }

    var onSpeakStart: (() -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null

    /** Gọi khi tiếng Việt TTS chưa được cài — dùng để nhắc user cài TTS data */
    var onLanguageUnavailable: (() -> Unit)? = null

    val isSpeaking = AtomicBoolean(false)
    private var isReady = false

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val ready = setupVietnamese()
                if (!ready) {
                    Log.e(TAG, "Vietnamese TTS not available — notifying user")
                    onLanguageUnavailable?.invoke()
                }
                tts.setSpeechRate(1.1f)
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
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
     * Thử set ngôn ngữ tiếng Việt theo thứ tự ưu tiên:
     *   1. vi-VN  2. vi  → trả về true nếu set được
     */
    private fun setupVietnamese(): Boolean {
        val locales = listOf(Locale("vi", "VN"), Locale("vi"))
        for (locale in locales) {
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.d(TAG, "Vietnamese TTS ready with locale: $locale")
                isReady = true
                return true
            }
        }
        return false
    }

    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
    }

    fun stop() {
        tts.stop()
        isSpeaking.set(false)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        /**
         * Mở màn hình cài đặt TTS data của hệ thống.
         * Gọi khi nhận được onLanguageUnavailable để user cài tiếng Việt.
         */
        fun openTtsSettings(context: Context) {
            try {
                val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("TtsManager", "Cannot open TTS install screen", e)
            }
        }
    }
}
