package com.sal7one.common_jni.ocr

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in hardware integration: place verified publisher artifacts in filesDir/ocr-smoke.
 * No models are bundled, no network calls and no private user images are read. */
@RunWith(AndroidJUnit4::class)
class JapaneseOcrDeviceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val root=File(context.filesDir,"ocr-smoke")
    private fun file(name: String)=File(root,name).also {assertTrue("Missing fixture/model: $name",it.isFile)}
    private fun requireFixtures(){assumeTrue("Copy opt-in OCR fixtures/models first",root.isDirectory)}
    private fun check(engine: OcrEngine,name: String,expected: String) {
        val bitmap=BitmapFactory.decodeFile(file(name).path)
        assertNotNull(bitmap)
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val start=System.nanoTime()
        val text=engine.recognize(pixels,bitmap.width,bitmap.height,960).joinToString("\n"){it.text}
        Log.i("HearthOcrDevice","${engine.javaClass.simpleName} $name: ${(System.nanoTime()-start)/1_000_000} ms; text=$text")
        assertEquals(expected,text)
        bitmap.recycle()
    }
    @Test fun paddleEnglishOnAndroidRuntime() {
        requireFixtures()
        val array=JSONArray(context.assets.open("ocr/latin.json").bufferedReader().use{it.readText()})
        PaddleOcr(file("PP-OCRv5_mobile_det_onnx.onnx"),file("latin_PP-OCRv5_mobile_rec_onnx.onnx"),(0 until array.length()).map(array::getString)).use {
            check(it,"english.png","The train leaves at noon.")
        }
    }
    @Test fun mangaHorizontalAndVerticalOnAndroidRuntime() {
        requireFixtures()
        val array=JSONArray(context.assets.open("ocr/manga.json").bufferedReader().use{it.readText()})
        JapaneseOcr(1,file("encoder_model_uint8.onnx"),file("decoder_model_int8.onnx"),vocabulary=(0 until array.length()).map(array::getString)).use {
            check(it,"fixture.png","今日はいい天気ですね。")
            check(it,"vertical.png","今日はいい天気ですね。")
            it.cancel()
            try {it.recognize(IntArray(10000),100,100);fail("Cancelled recognizer accepted another image")}
            catch(expected: IllegalStateException){assertTrue(expected.message.orEmpty().isNotBlank())}
        }
    }
    @Test fun meikiHorizontalVerticalAndBlankOnAndroidRuntime() {
        requireFixtures()
        JapaneseOcr(2,file("meiki.text.detect.v0.1.960x544.onnx"),file("meiki.text.rec.v0.960x32.onnx"),file("meiki.text.rec.v0.vertical.32x480.onnx")).use {
            check(it,"fixture.png","今日はいい天気ですね。")
            check(it,"vertical.png","今日はいい天気ですね")
            check(it,"blank.png","")
        }
    }
}
