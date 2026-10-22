package com.sal7one.transiber.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sal7one.hearth.tts.ArticleReader
import com.sal7one.hearth.tts.ArticleSpeech
import com.sal7one.hearth.tts.PcmAudio
import com.sal7one.hearth.tts.VoicePreference
import com.sal7one.hearth.tts.ming.MingSpeechEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner-authorized device test: the shared Ming JNI loaded from the installed APK
 * (System.loadLibrary), synthesizing in the app process under SELinux.
 *
 * Push the 17-file bundle to the app's external files dir before running:
 *   adb push build/agents/ming-native/bundle/. \
 *     /sdcard/Android/data/com.sal7one.transiber.qa/files/ming-bundle/
 */
@RunWith(AndroidJUnit4::class)
class MingLocalDeviceTest {
    @Test
    fun synthesizesInsideTheAppProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = context.getExternalFilesDir(null)?.resolve("ming-bundle")
        assertTrue("Push the 17-file Ming bundle to $external", external?.isDirectory == true)
        // The production install root is internal app storage; /sdcard FUSE rejects hardlinks/symlinks.
        val bundle = context.filesDir.resolve("ming-bundle-device-test")
        if (!bundle.resolve("manifest.json").isFile) {
            bundle.mkdirs()
            external!!.listFiles().orEmpty().filter { it.isFile }.forEach { source ->
                source.copyTo(bundle.resolve(source.name), overwrite = true)
            }
        }
        val engine = MingSpeechEngine(bundle)
        assertTrue("Ming must expose US_Emma", engine.voices.any { it.id == "US_Emma" })
        val frames = mutableListOf<PcmAudio>()
        try {
        ArticleReader(engine).use { reader ->
            reader.read(
                "Hello from Hearth on Android.",
                "en",
                VoicePreference(id = "US_Emma"),
            ).collect { event -> if (event is ArticleSpeech.Audio) frames += event.pcm }
        }
        assertTrue("Ming produced no audio frames", frames.isNotEmpty())
        assertEquals(44_100, frames.first().sampleRate)
        val samples = frames.sumOf { it.samples.size }
        val peak = frames.maxOf { frame -> frame.samples.maxOf { kotlin.math.abs(it) } }
        println("MING_ANDROID_APP frames=$samples peak=$peak")
        assertTrue("Ming produced too little audio: $samples", samples > 22_050)
        assertTrue("Ming produced silent audio", peak > 0.01f)
        } finally {
            bundle.deleteRecursively()
        }
    }
}
