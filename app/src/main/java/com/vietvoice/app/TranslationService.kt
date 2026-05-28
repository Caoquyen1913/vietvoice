package com.vietvoice.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
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
import com.vietvoice.app.audio.MicCaptureManager
import com.vietvoice.app.config.DirectionPrefs
import com.vietvoice.app.config.TranslationDirection
import com.vietvoice.app.model.ModelDownloader
import com.vietvoice.app.overlay.OverlayController
import com.vietvoice.app.stt.VoskTranscriber
import com.vietvoice.app.translate.TranslatorManager
import com.vietvoice.app.tts.SherpaTtsManager
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

        const val ACTION_PREPARE            = "com.vietvoice.app.PREPARE"
        const val ACTION_START_PIPELINE     = "com.vietvoice.app.START_PIPELINE"
        const val ACTION_START_MIC_PIPELINE = "com.vietvoice.app.START_MIC_PIPELINE"
        const val ACTION_STOP               = "com.vietvoice.app.STOP"
        const val ACTION_SHOW_TRANSCRIPT    = "com.vietvoice.app.SHOW_TRANSCRIPT"
        const val ACTION_HIDE_TRANSCRIPT    = "com.vietvoice.app.HIDE_TRANSCRIPT"
        const val ACTION_TRANSCRIPT         = "com.vietvoice.app.TRANSCRIPT"
        const val ACTION_STATUS             = "com.vietvoice.app.STATUS"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler  = Handler(Looper.getMainLooper())

    private var audioCaptureManager: AudioCaptureManager? = null
    private var micCaptureManager: MicCaptureManager? = null
    private var voskTranscriber: VoskTranscriber? = null
    private var translatorManager: TranslatorManager? = null
    private var ttsManager: SherpaTtsManager? = null
    private var overlayController: OverlayController? = null
    private var activeDirection: TranslationDirection = TranslationDirection.ZH_TO_VI

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                startForegroundCompat("⏳ Đang chuẩn bị...")
                showOverlay()
                sendStatus("⏳ Đang chờ quyền...")
            }
            ACTION_START_PIPELINE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
                    val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                    startPipelineInternal(mediaProjection)
                } else {
                    showToast("Lỗi: không lấy được quyền bắt âm thanh")
                    stopSelf()
                }
            }
            ACTION_START_MIC_PIPELINE -> startPipelineInternal(null)
            ACTION_STOP             -> tearDown()
            ACTION_SHOW_TRANSCRIPT  -> overlayController?.showTranscript(true)
            ACTION_HIDE_TRANSCRIPT  -> overlayController?.showTranscript(false)
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlayController != null) return
        val overlay = OverlayController(applicationContext)
        overlay.listener = object : OverlayController.Listener {
            override fun onStartStop() {
                if (audioCaptureManager == null && micCaptureManager == null) return
                overlay.isRunning = !overlay.isRunning
                val gated = !overlay.isRunning
                audioCaptureManager?.setGated(gated)
                micCaptureManager?.setGated(gated)
            }
            override fun onToggleTranscript() {
                overlay.showTranscript(!overlay.transcriptVisible)
            }
        }
        overlay.isRunning = false
        overlay.show()
        overlayController = overlay
    }

    /**
     * Khởi động pipeline chung cho cả 2 chiều.
     * @param mediaProjection null = dùng mic (VI→ZH), non-null = dùng AudioPlaybackCapture (ZH→VI)
     */
    private fun startPipelineInternal(mediaProjection: MediaProjection?) {
        activeDirection = DirectionPrefs.get(this)
        val dir = activeDirection
        val useMic = (mediaProjection == null)
        val dirLabel = if (dir == TranslationDirection.ZH_TO_VI) "ZH→VI" else "VI→ZH"
        val inputLabel = if (useMic) "🎤 mic" else "🎵 game audio"

        if (!useMic) {
            upgradeForegroundForMediaProjection("⬇️ Đang tải model $dirLabel...")
        } else {
            updateNotification("⬇️ Đang tải model $dirLabel...")
        }
        sendStatus("⬇️ Đang tải model dịch $dirLabel ($inputLabel)...")

        overlayController?.srcFlag = dir.srcFlag
        overlayController?.tgtFlag = dir.tgtFlag

        ttsManager = SherpaTtsManager(ModelDownloader.getTtsModelPath(this, dir)).also {
            if (!ModelDownloader.isTtsModelDownloaded(this, dir)) {
                sendStatus("⚠️ Chưa tải giọng đọc. Vào app → Tải giọng đọc.")
            }
        }
        translatorManager = TranslatorManager(dir.mlKitSrc, dir.mlKitTgt)
        voskTranscriber   = VoskTranscriber(ModelDownloader.getSttModelPath(this, dir))

        translatorManager!!.downloadModelIfNeeded(
            onReady = {
                if (translatorManager?.isReady == false) {
                    val warn = "⚠️ Không tải được model dịch (cần mạng/VPN). Vẫn chạy nhận dạng."
                    sendStatus(warn); showToast(warn)
                }
                sendStatus("⬇️ Đang tải model nhận dạng giọng nói...")
                updateNotification("⬇️ Đang load Vosk model...")
                serviceScope.launch {
                    val loaded = withContext(Dispatchers.IO) { voskTranscriber!!.load() }
                    if (!loaded) {
                        val msg = "❌ Không load được model STT. Thử tải lại model."
                        sendStatus(msg); showToast(msg)
                        return@launch
                    }

                    // Tạo audio source theo chế độ
                    if (useMic) {
                        micCaptureManager = MicCaptureManager()
                    } else {
                        audioCaptureManager = AudioCaptureManager(mediaProjection!!)
                    }

                    sendStatus("✅ Model STT loaded — bắt đầu lắng nghe ($inputLabel)...")

                    var audioChunks = 0
                    var silentChunks = 0
                    var lastDiagChunk = 0
                    var peakRms = 0

                    voskTranscriber!!.listener = object : VoskTranscriber.Listener {
                        override fun onPartialResult(text: String) {
                            mainHandler.post { sendStatus("🎙️ $text") }
                        }
                        override fun onFinalResult(text: String) {
                            translatorManager?.translate(text) { translated ->
                                broadcastTranscript(text, translated)
                                overlayController?.addTranscript(text, translated)
                                ttsManager?.speak(translated)
                                sendStatus("✅ ${dir.srcFlag} $text  →  ${dir.tgtFlag} $translated")
                            }
                        }
                    }

                    // Callback dùng chung cho cả mic và capture
                    val onAudioData: (ByteArray, Int) -> Unit = { buffer, bytesRead ->
                        voskTranscriber?.feedAudio(buffer, bytesRead)
                        audioChunks++

                        // Tính RMS amplitude từ PCM 16-bit little-endian
                        var sumSq = 0L
                        val samples = bytesRead / 2
                        for (i in 0 until samples) {
                            val lo = buffer[i * 2].toInt() and 0xFF
                            val hi = buffer[i * 2 + 1].toInt()
                            val sample = (hi shl 8) or lo
                            sumSq += sample.toLong() * sample.toLong()
                        }
                        val rms = if (samples > 0) Math.sqrt(sumSq.toDouble() / samples).toInt() else 0
                        if (rms > peakRms) peakRms = rms
                        if (rms < 30) silentChunks++

                        // Cứ ~3 giây (khoảng 150 chunks) cập nhật status 1 lần
                        if (audioChunks - lastDiagChunk >= 150) {
                            lastDiagChunk = audioChunks
                            val silentPct = if (audioChunks > 0) silentChunks * 100 / audioChunks else 100
                            val diagMsg = when {
                                silentPct > 95 ->
                                    if (useMic) "🎤 Chưa nghe thấy gì (peak RMS=$peakRms) — nói to vào mic."
                                    else        "⚠️ Không nhận được audio (peak RMS=$peakRms) — thử VLC/Google Translate TTS."
                                peakRms < 200 ->
                                    if (useMic) "🎤 Giọng nhỏ quá (peak RMS=$peakRms) — nói gần mic hơn."
                                    else        "🎙️ Audio rất nhỏ (peak RMS=$peakRms) — tăng âm lượng app nguồn."
                                else ->
                                    "🎙️ Đang nghe... (peak RMS=$peakRms, $silentPct% silence)"
                            }
                            peakRms = 0
                            mainHandler.post { sendStatus(diagMsg) }
                        }
                    }

                    audioCaptureManager?.listener = AudioCaptureManager.Listener { b, n -> onAudioData(b, n) }
                    micCaptureManager?.listener   = MicCaptureManager.Listener   { b, n -> onAudioData(b, n) }

                    try {
                        audioCaptureManager?.start() ?: micCaptureManager?.start()
                        overlayController?.isRunning = true
                        val canTranslate = translatorManager?.isReady == true
                        val readyMsg = if (canTranslate)
                            "✅ Sẵn sàng! Đang nhận dạng + dịch ($dirLabel $inputLabel)..."
                        else
                            "⚠️ Đang nhận dạng (chỉ hiện chữ, không có bản dịch)"
                        sendStatus(readyMsg)
                        updateNotification(readyMsg)
                        showToast(if (canTranslate) "VietVoice đang dịch $dirLabel ✅" else "VietVoice đang nhận dạng ⚠️")
                    } catch (e: Exception) {
                        Log.e(TAG, "Audio capture failed", e)
                        val errMsg = "❌ Không bắt được âm thanh: ${e.message}"
                        sendStatus(errMsg); showToast(errMsg)
                    }
                }
            },
            onError = { _ -> }
        )
    }

    private fun broadcastTranscript(original: String, translated: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_TRANSCRIPT).apply {
                putExtra("original", original)
                putExtra("translated", translated)
                putExtra("src_flag", activeDirection.srcFlag)
                putExtra("tgt_flag", activeDirection.tgtFlag)
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
        micCaptureManager?.stop()
        voskTranscriber?.release()
        translatorManager?.close()
        ttsManager?.shutdown()
        overlayController?.hide()
        audioCaptureManager = null
        micCaptureManager = null
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
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun upgradeForegroundForMediaProjection(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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

    private fun buildNotification(text: String = "VietVoice") =
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
