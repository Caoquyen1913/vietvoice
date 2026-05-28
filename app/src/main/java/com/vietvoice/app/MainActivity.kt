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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vietvoice.app.config.DirectionPrefs
import com.vietvoice.app.config.TranslationDirection
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
        else toast(getString(R.string.toast_permissions_denied))
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
            binding.btnStartStop.text = getString(R.string.btn_stop)
        } else {
            startService(Intent(this, TranslationService::class.java).apply {
                action = TranslationService.ACTION_STOP
            })
            toast(getString(R.string.toast_audio_permission))
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TranslationService.ACTION_TRANSCRIPT -> {
                    val original   = intent.getStringExtra("original") ?: return
                    val translated = intent.getStringExtra("translated") ?: ""
                    val srcFlag    = intent.getStringExtra("src_flag") ?: ""
                    val tgtFlag    = intent.getStringExtra("tgt_flag") ?: ""
                    binding.tvOriginal.text   = "$srcFlag $original"
                    binding.tvTranslated.text = "$tgtFlag $translated"
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

        setupButtons()
        setupDirectionToggle()

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

    private fun currentLang(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.size() > 0) locales.get(0)?.language ?: "vi" else "vi"
    }

    private fun setupDirectionToggle() {
        val dir = DirectionPrefs.get(this)
        // Set initial state without triggering listener
        binding.toggleDirection.check(
            if (dir == TranslationDirection.ZH_TO_VI) R.id.btn_dir_zh_vi else R.id.btn_dir_vi_zh
        )
        updateDirectionUI(dir)

        binding.toggleDirection.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newDir = if (checkedId == R.id.btn_dir_zh_vi) TranslationDirection.ZH_TO_VI
                         else TranslationDirection.VI_TO_ZH
            DirectionPrefs.set(this, newDir)
            updateDirectionUI(newDir)
        }
    }

    private fun updateDirectionUI(dir: TranslationDirection) {
        binding.tvSubtitle.text = "${dir.srcFlag} → ${dir.tgtFlag}"
        when (dir) {
            TranslationDirection.ZH_TO_VI -> {
                binding.tvSttLabel.text   = getString(R.string.label_stt_zh)
                binding.tvTtsLabel.text   = getString(R.string.label_tts_vi)
                binding.tvOriginal.hint   = getString(R.string.hint_original_zh)
                binding.tvTranslated.hint = getString(R.string.hint_translated_vi)
            }
            TranslationDirection.VI_TO_ZH -> {
                binding.tvSttLabel.text   = getString(R.string.label_stt_vn)
                binding.tvTtsLabel.text   = getString(R.string.label_tts_zh)
                binding.tvOriginal.hint   = getString(R.string.hint_original_vn)
                binding.tvTranslated.hint = getString(R.string.hint_translated_zh)
            }
        }
        updateModelStatus()
    }

    private fun setupButtons() {
        binding.btnDownloadModel.setOnClickListener { startSttDownload() }
        binding.btnDownloadTts.setOnClickListener   { startTtsDownload() }

        binding.btnStartStop.setOnClickListener {
            if (isServiceRunning) stopTranslation() else checkAndStart()
        }

        binding.switchTranscript.setOnCheckedChangeListener { _, checked ->
            val action = if (checked) TranslationService.ACTION_SHOW_TRANSCRIPT
                         else TranslationService.ACTION_HIDE_TRANSCRIPT
            startService(Intent(this, TranslationService::class.java).apply { this.action = action })
        }

        // Language toggle: show opposite language as the label (tap to switch TO that language)
        updateLangToggleLabel()
        binding.btnLangToggle.setOnClickListener {
            val newLang = if (currentLang() == "zh") "vi" else "zh"
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newLang))
            // AppCompatDelegate persists locale and recreates Activity automatically
        }
    }

    private fun updateLangToggleLabel() {
        binding.btnLangToggle.text = if (currentLang() == "zh") getString(R.string.btn_lang_vi)
                                     else getString(R.string.btn_lang_zh)
    }

    private fun updateModelStatus() {
        val dir = DirectionPrefs.get(this)
        val sttOk = ModelDownloader.isSttModelDownloaded(this, dir)
        val ttsOk = ModelDownloader.isTtsModelDownloaded(this, dir)

        binding.tvModelStatus.text = if (sttOk) {
            when (dir) {
                TranslationDirection.ZH_TO_VI -> getString(R.string.status_stt_zh_ready)
                TranslationDirection.VI_TO_ZH -> getString(R.string.status_stt_vn_ready)
            }
        } else {
            when (dir) {
                TranslationDirection.ZH_TO_VI -> getString(R.string.status_stt_zh_not_ready)
                TranslationDirection.VI_TO_ZH -> getString(R.string.status_stt_vn_not_ready)
            }
        }
        binding.btnDownloadModel.isEnabled = !sttOk

        binding.tvTtsStatus.text = if (ttsOk) {
            when (dir) {
                TranslationDirection.ZH_TO_VI -> getString(R.string.status_tts_vi_ready)
                TranslationDirection.VI_TO_ZH -> getString(R.string.status_tts_zh_ready)
            }
        } else {
            when (dir) {
                TranslationDirection.ZH_TO_VI -> getString(R.string.status_tts_vi_not_ready)
                TranslationDirection.VI_TO_ZH -> getString(R.string.status_tts_zh_not_ready)
            }
        }
        binding.btnDownloadTts.isEnabled = !ttsOk

        val bothReady = sttOk && ttsOk
        binding.btnStartStop.isEnabled = bothReady
        if (!bothReady) binding.tvStatus.text = when {
            !sttOk && !ttsOk -> getString(R.string.status_need_both_models)
            !sttOk           -> getString(R.string.status_need_stt)
            else             -> getString(R.string.status_need_tts)
        }
    }

    private fun startSttDownload() {
        val dir = DirectionPrefs.get(this)
        binding.btnDownloadModel.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvModelStatus.text = getString(R.string.downloading_progress, 0)

        ModelDownloader.downloadStt(this, dir) { progress, error ->
            runOnUiThread {
                if (error != null) {
                    binding.tvModelStatus.text = "❌ ${error.message}"
                    binding.btnDownloadModel.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    toast(getString(R.string.toast_stt_download_error))
                } else if (progress == 100) {
                    binding.progressBar.visibility = View.GONE
                    updateModelStatus()
                    toast(getString(R.string.toast_stt_download_done))
                } else {
                    binding.progressBar.progress = progress
                    binding.tvModelStatus.text = getString(R.string.downloading_progress, progress)
                }
            }
        }
    }

    private fun startTtsDownload() {
        val dir = DirectionPrefs.get(this)
        binding.btnDownloadTts.isEnabled = false
        binding.progressBarTts.visibility = View.VISIBLE
        binding.tvTtsStatus.text = getString(R.string.downloading_progress, 0)

        ModelDownloader.downloadTts(this, dir) { progress, error ->
            runOnUiThread {
                if (error != null) {
                    binding.tvTtsStatus.text = "❌ ${error.message}"
                    binding.btnDownloadTts.isEnabled = true
                    binding.progressBarTts.visibility = View.GONE
                    toast(getString(R.string.toast_tts_download_error))
                } else if (progress == 100) {
                    binding.progressBarTts.visibility = View.GONE
                    updateModelStatus()
                    toast(getString(R.string.toast_tts_download_done))
                } else {
                    binding.progressBarTts.progress = progress
                    binding.tvTtsStatus.text = getString(R.string.downloading_progress, progress)
                }
            }
        }
    }

    private fun checkAndStart() {
        val dir = DirectionPrefs.get(this)
        if (!ModelDownloader.isSttModelDownloaded(this, dir) ||
            !ModelDownloader.isTtsModelDownloaded(this, dir)) {
            toast(getString(R.string.status_need_both_models))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_overlay_permission))
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
        binding.btnStartStop.text = getString(R.string.btn_start)
        binding.tvOriginal.text   = ""
        binding.tvTranslated.text = ""
        binding.tvStatus.text     = getString(R.string.status_stopped)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
