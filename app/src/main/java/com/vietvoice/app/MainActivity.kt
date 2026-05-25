package com.vietvoice.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vietvoice.app.databinding.ActivityMainBinding
import com.vietvoice.app.model.ModelDownloader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var isServiceRunning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) prepareAndRequestProjection()
        else toast("❌ Cần cấp đầy đủ quyền để app hoạt động")
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startService(Intent(this, TranslationService::class.java).apply {
                action = TranslationService.ACTION_START_PIPELINE
                putExtra(TranslationService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(TranslationService.EXTRA_RESULT_DATA, result.data)
            })
            isServiceRunning = true
            binding.btnStartStop.text = "⏹ Dừng"
        } else {
            startService(Intent(this, TranslationService::class.java).apply {
                action = TranslationService.ACTION_STOP
            })
            toast("⚠️ Cần cho phép bắt âm thanh hệ thống")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TranslationService.ACTION_TRANSCRIPT -> {
                    val original   = intent.getStringExtra("original") ?: return
                    val translated = intent.getStringExtra("translated") ?: ""
                    binding.tvOriginal.text   = "🇨🇳 $original"
                    binding.tvTranslated.text = "🇻🇳 $translated"
                }
                TranslationService.ACTION_STATUS -> {
                    val msg = intent.getStringExtra("message") ?: return
                    binding.tvStatus.text = msg
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        updateModelStatus()
        setupButtons()
        val filter = IntentFilter().apply {
            addAction(TranslationService.ACTION_TRANSCRIPT)
            addAction(TranslationService.ACTION_STATUS)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        updateModelStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }

    private fun setupButtons() {
        binding.btnDownloadModel.setOnClickListener { startVoskDownload() }
        binding.btnDownloadTts.setOnClickListener   { startTtsDownload() }

        binding.btnStartStop.setOnClickListener {
            if (isServiceRunning) stopTranslation() else checkAndStart()
        }

        binding.switchTranscript.setOnCheckedChangeListener { _, checked ->
            val action = if (checked) TranslationService.ACTION_SHOW_TRANSCRIPT
                         else TranslationService.ACTION_HIDE_TRANSCRIPT
            startService(Intent(this, TranslationService::class.java).apply { this.action = action })
        }
    }

    private fun updateModelStatus() {
        val voskOk = ModelDownloader.isModelDownloaded(this)
        val ttsOk  = ModelDownloader.isTtsModelDownloaded(this)

        binding.tvModelStatus.text = if (voskOk) "✅ Model tiếng Trung đã sẵn sàng"
                                     else "⚠️ Chưa tải model nhận dạng (~42MB)"
        binding.btnDownloadModel.isEnabled = !voskOk

        binding.tvTtsStatus.text = if (ttsOk) "✅ Giọng đọc tiếng Việt đã sẵn sàng"
                                   else "⚠️ Chưa tải giọng đọc (~67MB)"
        binding.btnDownloadTts.isEnabled = !ttsOk

        val bothReady = voskOk && ttsOk
        binding.btnStartStop.isEnabled = bothReady
        if (!bothReady) binding.tvStatus.text = when {
            !voskOk && !ttsOk -> "Vui lòng tải cả hai model"
            !voskOk           -> "Vui lòng tải model nhận dạng"
            else              -> "Vui lòng tải giọng đọc tiếng Việt"
        }
    }

    private fun startVoskDownload() {
        binding.btnDownloadModel.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvModelStatus.text = "Đang tải model nhận dạng..."

        ModelDownloader.download(this) { progress, error ->
            runOnUiThread {
                if (error != null) {
                    binding.tvModelStatus.text = "❌ Lỗi: ${error.message}"
                    binding.btnDownloadModel.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    toast("Lỗi tải model. Kiểm tra kết nối mạng.")
                } else if (progress == 100) {
                    binding.progressBar.visibility = View.GONE
                    updateModelStatus()
                    toast("✅ Tải model nhận dạng xong!")
                } else {
                    binding.progressBar.progress = progress
                    binding.tvModelStatus.text = "Đang tải: $progress%"
                }
            }
        }
    }

    private fun startTtsDownload() {
        binding.btnDownloadTts.isEnabled = false
        binding.progressBarTts.visibility = View.VISIBLE
        binding.tvTtsStatus.text = "Đang tải giọng đọc..."

        ModelDownloader.downloadTts(this) { progress, error ->
            runOnUiThread {
                if (error != null) {
                    binding.tvTtsStatus.text = "❌ Lỗi: ${error.message}"
                    binding.btnDownloadTts.isEnabled = true
                    binding.progressBarTts.visibility = View.GONE
                    toast("Lỗi tải giọng đọc. Kiểm tra kết nối mạng.")
                } else if (progress == 100) {
                    binding.progressBarTts.visibility = View.GONE
                    updateModelStatus()
                    toast("✅ Tải giọng đọc xong!")
                } else {
                    binding.progressBarTts.progress = progress
                    binding.tvTtsStatus.text = "Đang tải: $progress%"
                }
            }
        }
    }

    private fun checkAndStart() {
        if (!ModelDownloader.isModelDownloaded(this) || !ModelDownloader.isTtsModelDownloaded(this)) {
            toast("Vui lòng tải đủ cả hai model trước")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Cần quyền 'Hiển thị trên ứng dụng khác' — đang mở Settings...")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                it.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) prepareAndRequestProjection()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun prepareAndRequestProjection() {
        binding.tvStatus.text = "Đang khởi động service..."
        ContextCompat.startForegroundService(this,
            Intent(this, TranslationService::class.java).apply {
                action = TranslationService.ACTION_PREPARE
            })
        Handler(Looper.getMainLooper()).postDelayed({
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }, 400)
    }

    private fun stopTranslation() {
        startService(Intent(this, TranslationService::class.java).apply {
            action = TranslationService.ACTION_STOP
        })
        isServiceRunning = false
        binding.btnStartStop.text = "▶ Bắt đầu"
        binding.tvOriginal.text   = ""
        binding.tvTranslated.text = ""
        binding.tvStatus.text     = "Đã dừng"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
