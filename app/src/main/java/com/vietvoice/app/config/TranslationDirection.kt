package com.vietvoice.app.config

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

enum class TranslationDirection(
    val sttModelDir: String,
    val sttModelUrl: String,
    val ttsModelDir: String,
    val ttsModelUrl: String,
    val mlKitSrc: String,
    val mlKitTgt: String,
    val srcFlag: String,
    val tgtFlag: String,
) {
    ZH_TO_VI(
        sttModelDir = "vosk-model-small-cn-0.22",
        sttModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
        ttsModelDir = "vits-piper-vi_VN-vais1000-medium",
        ttsModelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-vi_VN-vais1000-medium.tar.bz2",
        mlKitSrc = TranslateLanguage.CHINESE,
        mlKitTgt = TranslateLanguage.VIETNAMESE,
        srcFlag = "中文", tgtFlag = "Việt"
    ),
    VI_TO_ZH(
        sttModelDir = "vosk-model-vn-0.4",
        sttModelUrl = "https://alphacephei.com/vosk/models/vosk-model-vn-0.4.zip",
        ttsModelDir = "vits-zh-hf-fanchen-C",
        ttsModelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-fanchen-C.tar.bz2",
        mlKitSrc = TranslateLanguage.VIETNAMESE,
        mlKitTgt = TranslateLanguage.CHINESE,
        srcFlag = "Việt", tgtFlag = "中文"
    );
}

object DirectionPrefs {
    private const val PREFS = "vv_prefs"
    private const val KEY = "direction"

    fun get(ctx: Context): TranslationDirection {
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, TranslationDirection.ZH_TO_VI.name) ?: TranslationDirection.ZH_TO_VI.name
        return runCatching { TranslationDirection.valueOf(name) }.getOrDefault(TranslationDirection.ZH_TO_VI)
    }

    fun set(ctx: Context, dir: TranslationDirection) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, dir.name).apply()
    }
}

object LangPrefs {
    private const val PREFS = "vv_prefs"
    private const val KEY = "app_lang"

    fun get(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "vi") ?: "vi"

    fun set(ctx: Context, lang: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, lang).apply()
    }
}
