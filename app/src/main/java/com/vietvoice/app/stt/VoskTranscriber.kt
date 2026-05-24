package com.vietvoice.app.stt

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoskTranscriber(private val modelPath: String) {

    interface Listener {
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
    }

    companion object {
        private const val TAG = "VoskTranscriber"
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    var listener: Listener? = null

    fun load(): Boolean {
        return try {
            model = Model(modelPath)
            recognizer = Recognizer(model, 16000.0f)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model", e)
            false
        }
    }

    fun feedAudio(buffer: ByteArray, bytesRead: Int) {
        val rec = recognizer ?: return
        try {
            if (rec.acceptWaveForm(buffer, bytesRead)) {
                val text = parseText(rec.result, "text")
                if (text.isNotBlank()) listener?.onFinalResult(text)
            } else {
                val partial = parseText(rec.partialResult, "partial")
                if (partial.isNotBlank()) listener?.onPartialResult(partial)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding audio", e)
        }
    }

    private fun parseText(json: String, key: String): String {
        return try {
            JSONObject(json).optString(key, "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun release() {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
    }
}
