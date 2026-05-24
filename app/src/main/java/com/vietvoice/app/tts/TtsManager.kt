package com.vietvoice.app.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class TtsManager(context: Context) {

    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_ID = "vietvoice_tts"
    }

    var onSpeakStart: (() -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null
    val isSpeaking = AtomicBoolean(false)

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale("vi", "VN"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Vietnamese TTS not supported, trying system default")
                    tts.setLanguage(Locale.getDefault())
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

    fun speak(text: String) {
        if (text.isBlank()) return
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
}
