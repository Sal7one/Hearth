package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.PcmFrame
import com.sal7one.hearth.desktop.media.DesktopSystemAudioRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.Closeable
import java.nio.file.Path

interface DesktopPcmCapture : Closeable {
    fun start(): Flow<PcmFrame>
    suspend fun finish()
    fun cancel()
    override fun close() = cancel()
}

/** Native Mac system mixer input, independent of microphone capture and provider/model choices. */
class DesktopSystemAudioCapture(dataDirectory: Path) : DesktopPcmCapture {
    private val recorder = DesktopSystemAudioRecorder(dataDirectory)
    override fun start(): Flow<PcmFrame> = recorder.start().map { PcmFrame(it) }
    override suspend fun finish() = recorder.finish()
    override fun cancel() = recorder.cancel()
    companion object {
        val available: Boolean get() = DesktopSystemAudioRecorder.available
        val availabilityReason: String? get() = DesktopSystemAudioRecorder.availabilityReason
        suspend fun probe(dataDirectory: Path): String = DesktopSystemAudioRecorder.probe(dataDirectory)
    }
}
