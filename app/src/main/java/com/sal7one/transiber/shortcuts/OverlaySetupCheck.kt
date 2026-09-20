package com.sal7one.transiber.shortcuts

import kotlinx.coroutines.CancellationException

internal enum class OverlayShortcut(val label: String) {
    CAPTIONS("Live captions"), READING("Screen translation")
}
internal enum class SetupFix { OVERLAY_PERMISSION, AUDIO_PERMISSION, CAPTIONS, MODELS, TRANSLATION, CAMERA, SESSION }
internal data class SetupResult(val label: String, val detail: String, val passed: Boolean, val fix: SetupFix)
internal data class SetupProbe(val label: String, val fix: SetupFix, val check: suspend () -> String)

/** Collect every actionable setup failure, retaining the original error and cancellation. */
internal suspend fun checkOverlaySetup(probes: List<SetupProbe>): List<SetupResult> = probes.map { probe ->
    try { SetupResult(probe.label, probe.check(), true, probe.fix) }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { SetupResult(probe.label, e.message ?: e.toString(), false, probe.fix) }
    catch (e: LinkageError) { SetupResult(probe.label, e.message ?: e.toString(), false, probe.fix) }
}
internal val List<SetupResult>.ready: Boolean get() = isNotEmpty() && all { it.passed }
