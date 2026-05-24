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
        if (results.all { it.value }) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "Cần cấp đầy đủ quyền để app hoạt động", Toast.LENGTH_LONG).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            launchService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Cần cho phép bắt âm thanh hệ thống", Toast.LENGTH_LONG).show()
        }
    }

    private val transcriptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val original = intent.getStringExtra("original") ?: return
            val translated = intent.getStringExtra("translated") ?: ""
            binding.tvOriginal.text = "🇨🇳 $original"
            binding.tvTranslated.text = "🇻🇳 $translated"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        updateModelStatus()
        setupButtons()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            transcriptReceiver,
            IntentFilter(TranslationService.ACTION_TRANSCRIPT)
        )
    }

    override fun onResume() {
        super.onResume()
        updateModelStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(transcriptReceiver)
    }

    private fun setupButtons() {
        binding.btnDownloadModel.setOnClickListener { startModelDownload() }

        binding.btnStartStop.setOnClickListener {
            if (isServiceRunning) stopService() else checkAndStart()
        }

        binding.switchTranscript.setOnCheckedChangeListener { _, checked ->
            val action = if (checked) TranslationService.ACTION_SHOW_TRANSCRIPT
                         else TranslationService.ACTION_HIDE_TRANSCRIPT
            startService(Intent(this, TranslationService::class.java).apply { this.action = action })
        }
    }

    private fun updateModelStatus() {
        val downloaded = ModelDownloader.isModelDownloaded(this)
        binding.tvModelStatus.text = if (downloaded) "✅ Model tiếng Trung đã sẵn sàng"
                                     else "⚠️ Chưa tải model tiếng Trung (~42MB)"
        binding.btnDownloadModel.isEnabled = !downloaded
        binding.btnStartStop.isEnabled = downloaded
    }

    private fun startModelDownload() {
        binding.btnDownloadModel.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvModelStatus.text = "Đang tải model..."

        ModelDownloader.download(this) { progress, error ->
            runOnUiThread {
                if (error != null) {
                    binding.tvModelStatus.text = "❌ Lỗi: ${error.message}"
                    binding.btnDownloadModel.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                } else if (progress == 100) {
                    binding.progressBar.visibility = View.GONE
                    updateModelStatus()
                } else {
                    binding.progressBar.progress = progress
                    binding.tvModelStatus.text = "Đang tải: $progress%"
                }
            }
        }
    }

    private fun checkAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cần quyền 'Hiển thị trên ứng dụng khác'", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) requestMediaProjection()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestMediaProjection() {
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun launchService(resultCode: Int, data: Intent) {
        val intent = Intent(this, TranslationService::class.java).apply {
            action = TranslationService.ACTION_START
            putExtra(TranslationService.EXTRA_RESULT_CODE, resultCode)
            putExtra(TranslationService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        isServiceRunning = true
        binding.btnStartStop.text = "⏹ Dừng"
    }

    private fun stopService() {
        startService(Intent(this, TranslationService::class.java).apply {
            action = TranslationService.ACTION_STOP
        })
        isServiceRunning = false
        binding.btnStartStop.text = "▶ Bắt đầu"
        binding.tvOriginal.text = ""
        binding.tvTranslated.text = ""
    }
}
