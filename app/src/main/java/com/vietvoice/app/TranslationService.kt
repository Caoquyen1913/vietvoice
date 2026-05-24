package com.vietvoice.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vietvoice.app.audio.AudioCaptureManager
import com.vietvoice.app.model.ModelDownloader
import com.vietvoice.app.overlay.OverlayController
import com.vietvoice.app.stt.VoskTranscriber
import com.vietvoice.app.translate.TranslatorManager
import com.vietvoice.app.tts.TtsManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslationService : Service() {

    companion object {
        private const val TAG = "TranslationService"
        private const val CHANNEL_ID = "vietvoice_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.vietvoice.app.START"
        const val ACTION_STOP = "com.vietvoice.app.STOP"
        const val ACTION_SHOW_TRANSCRIPT = "com.vietvoice.app.SHOW_TRANSCRIPT"
        const val ACTION_HIDE_TRANSCRIPT = "com.vietvoice.app.HIDE_TRANSCRIPT"
        const val ACTION_TRANSCRIPT = "com.vietvoice.app.TRANSCRIPT"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioCaptureManager: AudioCaptureManager? = null
    private var voskTranscriber: VoskTranscriber? = null
    private var translatorManager: TranslatorManager? = null
    private var ttsManager: TtsManager? = null
    private var overlayController: OverlayController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != -1 && resultData != null) {
                    startForegroundCompat()
                    startPipeline(resultCode, resultData)
                }
            }
            ACTION_STOP -> stopPipeline()
            ACTION_SHOW_TRANSCRIPT -> overlayController?.showTranscript(true)
            ACTION_HIDE_TRANSCRIPT -> overlayController?.showTranscript(false)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startPipeline(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        ttsManager = TtsManager(this)
        translatorManager = TranslatorManager()
        voskTranscriber = VoskTranscriber(ModelDownloader.getModelPath(this))

        val capture = AudioCaptureManager(mediaProjection)
        audioCaptureManager = capture

        ttsManager!!.onSpeakStart = { capture.setGated(true) }
        ttsManager!!.onSpeakDone = { capture.setGated(false) }

        translatorManager!!.downloadModelIfNeeded(
            onReady = {
                serviceScope.launch {
                    val loaded = withContext(Dispatchers.IO) {
                        voskTranscriber!!.load()
                    }
                    if (!loaded) {
                        Log.e(TAG, "Failed to load Vosk model")
                        return@launch
                    }

                    voskTranscriber!!.listener = object : VoskTranscriber.Listener {
                        override fun onPartialResult(text: String) {
                            mainHandler.post { broadcastTranscript(text, "...") }
                        }
                        override fun onFinalResult(text: String) {
                            translatorManager?.translate(text) { viText ->
                                // ML Kit callbacks run on main thread
                                broadcastTranscript(text, viText)
                                overlayController?.addTranscript(text, viText)
                                ttsManager?.speak(viText)
                            }
                        }
                    }

                    capture.listener = AudioCaptureManager.Listener { buffer, bytesRead ->
                        voskTranscriber?.feedAudio(buffer, bytesRead)
                    }
                    capture.start()

                    val overlay = OverlayController(applicationContext)
                    overlay.listener = object : OverlayController.Listener {
                        override fun onStartStop() {
                            if (overlay.isRunning) {
                                capture.setGated(true)
                                overlay.isRunning = false
                            } else {
                                capture.setGated(false)
                                overlay.isRunning = true
                            }
                        }
                        override fun onToggleTranscript() {
                            overlay.showTranscript(!overlay.transcriptVisible)
                        }
                    }
                    overlay.isRunning = true
                    overlay.show()
                    overlayController = overlay
                }
            },
            onError = { Log.e(TAG, "ML Kit model download failed", it) }
        )
    }

    private fun broadcastTranscript(original: String, translated: String) {
        val intent = Intent(ACTION_TRANSCRIPT).apply {
            putExtra("original", original)
            putExtra("translated", translated)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun stopPipeline() {
        audioCaptureManager?.stop()
        voskTranscriber?.release()
        translatorManager?.close()
        ttsManager?.shutdown()
        overlayController?.hide()

        audioCaptureManager = null
        voskTranscriber = null
        translatorManager = null
        ttsManager = null
        overlayController = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopPipeline()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("VietVoice đang chạy")
        .setContentText("Đang dịch tiếng Trung → tiếng Việt")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VietVoice Translation",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Dịch giọng nói tiếng Trung trong game" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
