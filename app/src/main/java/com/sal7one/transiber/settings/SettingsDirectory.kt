package com.sal7one.transiber.settings

import java.text.Normalizer
import java.util.Locale

/** Search is a directory only: opening settings must never change the saved engine. */
internal enum class SettingsDirectoryEntry(val keywords: String) {
    BENCHMARK("benchmark benchmarks bench speed latency quality performance gpu cpu compare test models بنشمارك اختبار مقارنة أداء سرعة نماذج 测试 基准 模型 性能"),
    DOWNLOADS("models downloads downloader download import files storage library نماذج تنزيل تحميل استيراد ملفات 模型 下载 导入"),
    BUNDLES("bundles presets setup workflow configuration حزم إعداد إعدادات 配置 预设"),
    SPEECH("stt asr speech recognition transcription language microphone cloud local صوت نسخ التعرف كلام 语音 识别"),
    TRANSLATION("translation translator language arabic english cloud local ترجمة مترجم لغة عربي إنجليزي 翻译 语言"),
    VOICES("tts voice voices read aloud articles male female system cloud local نطق قراءة أصوات صوت 朗读 语音"),
    CAMERA("ocr camera screen reading image text كاميرا صورة شاشة 文本 相机 屏幕"),
    LANGUAGE("app language interface locale language لغة التطبيق واجهة 语言 界面"),
    APPEARANCE("appearance theme dark light home navigation مظهر تنقل سمة داكن فاتح 外观 导航 主题"),
    SHORTCUTS("shortcuts keyboard hotkey launcher اختصارات لوحة مفاتيح 快捷键"),
    OVERLAY("overlay captions languages accessibility controls ترجمة عائمة تراكب تسهيلات 悬浮 字幕"),
    HISTORY("history captions saved recordings export سجل محفوظ تسجيل 历史 记录"),
    HELP("help diagnostics errors support مساعدة أخطاء تشخيص 帮助 错误"),
}

/** Benchmarks stay available in basic mode. TVs never advertise local model work. */
internal fun settingsDirectoryEntries(
    cloudAvailable: Boolean,
    localModelsAllowed: Boolean,
    advanced: Boolean,
): List<SettingsDirectoryEntry> = buildList {
    if (localModelsAllowed) {
        add(SettingsDirectoryEntry.BENCHMARK)
        add(SettingsDirectoryEntry.DOWNLOADS)
    }
    if (localModelsAllowed || cloudAvailable) add(SettingsDirectoryEntry.BUNDLES)
    SettingsFeature.entries.forEach { feature ->
        if (settingsStageLocations(feature, cloudAvailable, localModelsAllowed).isNotEmpty()) {
            add(when (feature) {
                SettingsFeature.SPEECH -> SettingsDirectoryEntry.SPEECH
                SettingsFeature.TRANSLATION -> SettingsDirectoryEntry.TRANSLATION
                SettingsFeature.VOICES -> SettingsDirectoryEntry.VOICES
                SettingsFeature.CAMERA -> SettingsDirectoryEntry.CAMERA
            })
        }
    }
    add(SettingsDirectoryEntry.LANGUAGE)
    add(SettingsDirectoryEntry.APPEARANCE)
    add(SettingsDirectoryEntry.SHORTCUTS)
    if (advanced) add(SettingsDirectoryEntry.OVERLAY)
    add(SettingsDirectoryEntry.HISTORY)
    add(SettingsDirectoryEntry.HELP)
}

/** Match all words against localized labels and common terms, ignoring accents/Arabic marks. */
internal fun settingsSearchMatches(
    entry: SettingsDirectoryEntry,
    query: String,
    title: String,
    detail: String,
): Boolean {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .replace("ـ", "")
        .lowercase(Locale.ROOT)
    val words = normalize(query).trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val haystack = normalize("$title $detail ${entry.keywords}")
    return words.all(haystack::contains)
}
