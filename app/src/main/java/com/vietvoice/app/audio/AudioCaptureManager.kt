package com.vietvoice.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.AudioPlaybackCaptureConfiguration
import android.util.Log

class AudioCaptureManager(private val mediaProjection: MediaProjection) {

    fun interface Listener {
        fun onAudioData(buffer: ByteArray, bytesRead: Int)
    }

    companion object {
        private const val TAG = "AudioCaptureManager"
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
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(BUFFER_SIZE * 4)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
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
