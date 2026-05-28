package com.vietvoice.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class MicCaptureManager {

    fun interface Listener {
        fun onAudioData(buffer: ByteArray, bytesRead: Int)
    }

    companion object {
        private const val TAG = "MicCaptureManager"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false
    @Volatile private var isGated = false

    var listener: Listener? = null

    fun start() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
            BUFFER_SIZE * 4
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord (mic) failed to initialize")
            return
        }

        audioRecord!!.startRecording()
        isCapturing = true

        captureThread = Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isCapturing) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0 && !isGated) {
                    listener?.onAudioData(buffer, bytesRead)
                }
            }
        }.also { it.start() }
    }

    fun stop() {
        isCapturing = false
        captureThread?.join(1000)
        captureThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun setGated(gated: Boolean) {
        isGated = gated
    }
}
