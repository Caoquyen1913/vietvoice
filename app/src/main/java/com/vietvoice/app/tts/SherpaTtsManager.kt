package com.vietvoice.app.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TTS engine dùng sherpa-onnx + Piper model tiếng Việt.
 * Không cần Google TTS — chạy hoàn toàn offline.
 *
 * API public giống TtsManager cũ để TranslationService gần như không đổi.
 */
class SherpaTtsManager(modelDir: String) {

    companion object {
        private const val TAG = "SherpaTtsManager"
    }

    var onSpeakStart: (() -> Unit)? = null
    var onSpeakDone: (() -> Unit)? = null
    val isSpeaking = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val tts: OfflineTts? by lazy { initTts(modelDir) }
    private val isModelReady: Boolean = File(modelDir).let { dir ->
        dir.exists() && dir.walk().any { it.extension == "onnx" }
    }

    @Volatile private var currentTrack: AudioTrack? = null

    private fun initTts(dir: String): OfflineTts? {
        return try {
            val onnxFile = File(dir).walk().firstOrNull { it.extension == "onnx" }
                ?: return null.also { Log.e(TAG, "No .onnx file found in $dir") }
            val tokensFile    = File(dir, "tokens.txt")
            val espeakDataDir = File(dir, "espeak-ng-data")

            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model    = onnxFile.absolutePath,
                        tokens   = tokensFile.absolutePath,
                        dataDir  = espeakDataDir.absolutePath,
                        lexicon  = ""
                    ),
                    numThreads = 2
                )
            )
            OfflineTts(config = cfg).also { Log.d(TAG, "sherpa-onnx TTS loaded OK") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init sherpa TTS", e)
            null
        }
    }

    fun speak(text: String) {
        if (!isModelReady || text.isBlank()) return
        if (!isSpeaking.compareAndSet(false, true)) return   // đang đọc → bỏ qua câu mới

        scope.launch {
            try {
                val engine = tts ?: run { isSpeaking.set(false); return@launch }
                val audio  = engine.generate(text = text, sid = 0, speed = 1.0f)
                onSpeakStart?.invoke()  // gate STT trước khi phát
                playAudio(audio.samples, audio.sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "speak() failed", e)
            } finally {
                isSpeaking.set(false)
                onSpeakDone?.invoke()   // ungate STT
            }
        }
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE) // chống vòng lặp
            .build()

        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        currentTrack = track
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.play()

        val durationMs = samples.size.toLong() * 1000L / sampleRate
        Thread.sleep(durationMs + 150L)

        track.stop()
        track.release()
        currentTrack = null
    }

    fun stop() {
        currentTrack?.stop()
        isSpeaking.set(false)
    }

    fun shutdown() {
        stop()
        scope.cancel()
        try { tts?.release() } catch (_: Exception) {}
    }
}
