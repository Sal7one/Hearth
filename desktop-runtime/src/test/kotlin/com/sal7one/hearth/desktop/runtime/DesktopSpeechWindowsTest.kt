package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PcmFrame
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopSpeechWindowsTest {
    @Test
    fun everyLiveEngineUsesTheDisplayedFourSecondDefaultAndExplicitTuningWins() {
        for (id in listOf("whisper-local", "qwen3-asr-local", "nemotron-asr-local", "transcribepsy-local", "moonshine-local", "vosk-local", "openai-stt")) {
            val selection = EngineSelection(id)
            assertEquals(4000, desktopSpeechWindowMs(selection, true), id)
            assertEquals(12000, desktopSpeechWindowMs(selection.copy(options = mapOf("windowMs" to "12000")), true), id)
        }
        assertEquals(15000, desktopSpeechWindowMs(EngineSelection("nemotron-asr-local"), false))
        assertEquals(20000, desktopSpeechWindowMs(EngineSelection("whisper-local"), false))
        assertFailsWith<IllegalArgumentException> { DesktopSpeechWindows(true, 999) }
    }

    @Test
    fun longIdleBeforeSpeechAddsOnlyPreRollWhileSourceTimesKeepTheIdleGap() = runBlocking {
        val windows = mutableListOf<DesktopSpeechWindow>()
        val assembler = DesktopSpeechWindows(true, 4000)
        assembler.feed(pcm(12000, 0), windows::add)
        assertTrue(windows.isEmpty())
        assembler.feed(pcm(1000, 1000), windows::add)
        assembler.feed(pcm(600, 0), windows::add)
        assertEquals(1, windows.size)
        val result = windows.single()
        assertEquals(11800L * 16, result.startSample)
        assertEquals(13600L * 16, result.endSample)
        assertEquals(1800 * 32, result.bytes.size)
        assertContentEquals(pcm(200, 0) + pcm(1000, 1000) + pcm(600, 0), result.bytes)
        assembler.finish(windows::add)
        assertEquals(1, windows.size, "Finishing must not duplicate an endpointed final")
    }

    @Test
    fun sustainedSpeechCommitsAtFourSecondsWithoutDroppingOrRepeatingSamples() = runBlocking {
        val audio = pcm(9000, 1000)
        val windows = mutableListOf<DesktopSpeechWindow>()
        val assembler = DesktopSpeechWindows(true, 4000)
        assembler.feed(audio, windows::add)
        assertEquals(listOf(4000 * 32, 4000 * 32), windows.map { it.bytes.size })
        assembler.finish(windows::add)
        assertEquals(listOf(0L, 64000L, 128000L), windows.map { it.startSample })
        assertEquals(144000L, windows.last().endSample)
        assertContentEquals(audio, windows.fold(byteArrayOf()) { bytes, window -> bytes + window.bytes })
    }

    @Test
    fun fileWindowsPreserveSilenceAndEverySampleAcrossArbitraryIngressFrames() = runBlocking {
        val audio = pcm(6100, 0) + pcm(340, 1000) + pcm(1200, 0)
        val windows = mutableListOf<DesktopSpeechWindow>()
        val assembler = DesktopSpeechWindows(false, 4000)
        for (bytes in audio.asList().chunked(222).map { it.toByteArray() }) assembler.push(PcmFrame(bytes), windows::add)
        assembler.finish(windows::add)
        assertContentEquals(audio, windows.fold(byteArrayOf()) { bytes, window -> bytes + window.bytes })
        assertEquals(0L, windows.first().startSample)
        assertEquals(audio.size / 2L, windows.last().endSample)
    }

    @Test
    fun silenceOnlyLiveInputNeverInvokesTheModelButShortSpeechIsFlushed() = runBlocking {
        val windows = mutableListOf<DesktopSpeechWindow>()
        val assembler = DesktopSpeechWindows(true, 4000)
        assembler.feed(pcm(30000, 0), windows::add)
        assembler.finish(windows::add)
        assertTrue(windows.isEmpty())
        assembler.feed(pcm(120, 1000), windows::add)
        assembler.finish(windows::add)
        assertEquals(1, windows.size)
        assertEquals(320 * 32, windows.single().bytes.size)
        assertEquals(29800L * 16, windows.single().startSample)
    }

    @Test
    fun aSecondUtteranceKeepsItsOwnSourcePositionAfterDiscardingInterveningIdle() = runBlocking {
        val windows = mutableListOf<DesktopSpeechWindow>()
        val assembler = DesktopSpeechWindows(true, 4000)
        for (audio in listOf(pcm(500, 1000), pcm(600, 0), pcm(10000, 0), pcm(500, 1000), pcm(600, 0))) {
            assembler.feed(audio, windows::add)
        }
        assembler.finish(windows::add)
        assertEquals(2, windows.size)
        assertEquals(0L, windows[0].startSample)
        assertEquals(10900L * 16, windows[1].startSample)
        assertEquals(12200L * 16, windows[1].endSample)
    }

    private suspend fun DesktopSpeechWindows.feed(bytes: ByteArray, emit: suspend (DesktopSpeechWindow) -> Unit) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 3200, bytes.size)
            push(PcmFrame(bytes.copyOfRange(offset, end)), emit)
            offset = end
        }
    }

    private fun pcm(ms: Int, value: Int) = ByteArray(ms * 32).also { bytes ->
        for (index in bytes.indices step 2) {
            bytes[index] = value.toByte(); bytes[index + 1] = (value shr 8).toByte()
        }
    }
}
