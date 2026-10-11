package com.sal7one.transiber.notes

import com.sal7one.transiber.caption.CaptionMode
import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.caption.CaptionSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Blocking microphone reads must never occupy the metadata writer's single worker. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class VoiceNoteWork {
    val metadata: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    val capture: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
}

/** Note transcription is explicit consent to this note's private sink, independent of overlay history. */
internal fun CaptionOverlayConfig.forVoiceNote(translate: Boolean) = copy(
    source = CaptionSource.MIC,
    mode = if (translate) CaptionMode.TRANSLATE else CaptionMode.CAPTIONS,
    localTranslationEnabled = translate && (localTranslationEnabled || localTranslationModelId.isNotBlank() || textTranslationProviderId.isNotBlank()),
    saveCaptionHistory = true,
    speakCaptions = false,
    paused = false,
)

/** Bound the callback wait; always shut down and await native owners before returning to storage. */
internal suspend fun finishVoiceNoteSpeech(
    drain: (onDrained: () -> Unit) -> Unit,
    release: suspend () -> Unit,
): Exception? {
    var failure: Exception? = null
    try {
        kotlinx.coroutines.withTimeout(30_000) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                drain { if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) }
            }
        }
    } catch (e: Exception) { failure = e }
    finally { release() }
    return failure
}
