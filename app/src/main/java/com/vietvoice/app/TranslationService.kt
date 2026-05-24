package com.vietvoice.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vietvoice.app.audio.AudioCaptureManager
import com.vietvoice.app.model.ModelDownloader
import com.vietvoice.app.overlay.OverlayController
import com.vietvoice.app.stt.VoskTranscriber
import com.vietvoice.app.translate.TranslatorManager
import com.vietvoice.app.tts.TtsManager
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

        const val ACTION_PREPARE          = "com.vietvoice.app.PREPARE"
        const val ACTION_START_PIPELINE   = "com.vietvoice.app.START_PIPELINE"
        const val ACTION_STOP             = "com.vietvoice.app.STOP"
        const val ACTION_SHOW_TRANSCRIPT  = "com.vietvoice.app.SHOW_TRANSCRIPT"
        const val ACTION_HIDE_TRANSCRIPT  = "com.vietvoice.app.HIDE_TRANSCRIPT"
        const val ACTION_TRANSCRIPT       = "com.vietvoice.app.TRANSCRIPT"
        const val ACTION_STATUS           = "com.vietvoice.app.STATUS"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler  = Handler(Looper.getMainLooper())

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
            ACTION_PREPARE -> {
                // Bước 1: vào foreground TRƯỚC khi dialog MediaProjection xuất hiện (Android 14+)
                startForegroundCompat("⏳ Đang chuẩn bị...")
                // Show overlay ngay với trạng thái loading — user biết service đang chạy
                showOverlay()
                sendStatus("⏳ Đang chờ quyền bắt âm thanh...")
            }
            ACTION_START_PIPELINE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != -1 && resultData != null) {
                    startPipeline(resultCode, resultData)
                } else {
                    showToast("Lỗi: không lấy được quyền bắt âm thanh")
                    stopSelf()
                }
            }
            ACTION_STOP             -> tearDown()
            ACTION_SHOW_TRANSCRIPT  -> overlayController?.showTranscript(true)
            ACTION_HIDE_TRANSCRIPT  -> overlayController?.showTranscript(false)
        }
        return START_NOT_STICKY
    }

    // Hiện overlay ngay — user thấy nút nổi xuất hiện trên màn hình
    private fun showOverlay() {
        if (overlayController != null) return
        val overlay = OverlayController(applicationContext)
        overlay.listener = object : OverlayController.Listener {
            override fun onStartStop() {
                val cap = audioCaptureManager ?: return
                overlay.isRunning = !overlay.isRunning
                cap.setGated(!overlay.isRunning)
            }
            override fun onToggleTranscript() {
                overlay.showTranscript(!overlay.transcriptVisible)
            }
        }
        overlay.isRunning = false      // chưa dịch được — disabled state
        overlay.show()
        overlayController = overlay
    }

    private fun startPipeline(resultCode: Int, resultData: Intent) {
        // Android 14+: upgrade foreground type sang MEDIA_PROJECTION (bây giờ mới có token hợp lệ)
        upgradeForegroundForMediaProjection("⬇️ Đang tải model dịch ZH→VI...")
        sendStatus("⬇️ Đang tải model dịch ZH→VI...")

        ttsManager = TtsManager(this)
        translatorManager = TranslatorManager()
        voskTranscriber = VoskTranscriber(ModelDownloader.getModelPath(this))

        translatorManager!!.downloadModelIfNeeded(
            onReady = {
                sendStatus("⬇️ Đang tải model nhận dạng giọng nói...")
                updateNotification("⬇️ Đang load Vosk model...")
                serviceScope.launch {
                    val loaded = withContext(Dispatchers.IO) {
                        voskTranscriber!!.load()
                    }
                    if (!loaded) {
                        val msg = "❌ Không load được model tiếng Trung. Thử tải lại model."
                        sendStatus(msg)
                        showToast(msg)
                        return@launch
                    }

                    // Tất cả sẵn sàng — bắt đầu capture
                    val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                    val capture = AudioCaptureManager(mediaProjection)
                    audioCaptureManager = capture

                    ttsManager!!.onSpeakStart = { capture.setGated(true) }
                    ttsManager!!.onSpeakDone  = { capture.setGated(false) }

                    voskTranscriber!!.listener = object : VoskTranscriber.Listener {
                        override fun onPartialResult(text: String) {
                            mainHandler.post { sendStatus("🎙️ $text") }
                        }
                        override fun onFinalResult(text: String) {
                            translatorManager?.translate(text) { viText ->
                                broadcastTranscript(text, viText)
                                overlayController?.addTranscript(text, viText)
                                ttsManager?.speak(viText)
                                sendStatus("✅ $text  →  $viText")
                            }
                        }
                    }

                    capture.listener = AudioCaptureManager.Listener { buffer, bytesRead ->
                        voskTranscriber?.feedAudio(buffer, bytesRead)
                    }

                    try {
                        capture.start()
                        overlayController?.isRunning = true
                        val readyMsg = "✅ Sẵn sàng! Đang dịch tiếng Trung..."
                        sendStatus(readyMsg)
                        updateNotification(readyMsg)
                        showToast("VietVoice đang dịch ✅")
                    } catch (e: Exception) {
                        Log.e(TAG, "Audio capture failed", e)
                        val errMsg = "❌ Không bắt được âm thanh: ${e.message}"
                        sendStatus(errMsg)
                        showToast(errMsg)
                    }
                }
            },
            onError = { e ->
                val msg = "❌ Tải model dịch thất bại: ${e.message}"
                sendStatus(msg)
                showToast(msg)
                Log.e(TAG, "ML Kit download failed", e)
            }
        )
    }

    private fun broadcastTranscript(original: String, translated: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_TRANSCRIPT).apply {
                putExtra("original", original)
                putExtra("translated", translated)
            })
    }

    private fun sendStatus(msg: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_STATUS).apply { putExtra("message", msg) })
    }

    private fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun tearDown() {
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
        tearDown()
    }

    private fun startForegroundCompat(text: String = "VietVoice đang chạy") {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: PHẢI dùng MICROPHONE lúc này vì chưa có MediaProjection token.
            // MEDIA_PROJECTION type sẽ được thêm vào trong startPipeline() sau khi nhận token.
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // Gọi ngay đầu startPipeline() — lúc này đã có MediaProjection token hợp lệ
    private fun upgradeForegroundForMediaProjection(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Gọi startForeground lần 2 với MEDIA_PROJECTION type (additive — không thay thế MICROPHONE)
            startForeground(NOTIFICATION_ID, buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            updateNotification(text)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String = "Đang dịch tiếng Trung → tiếng Việt") =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VietVoice")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "VietVoice Translation",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
