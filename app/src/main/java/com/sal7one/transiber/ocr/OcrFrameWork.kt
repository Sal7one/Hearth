package com.sal7one.transiber.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keeps late native OCR results and errors attached to the frame that started them. */
internal object OcrFrameWork {
    suspend fun <T : Any> run(epoch: Long, currentEpoch: () -> Long, work: suspend () -> T): T? {
        try {
            val result=work()
            currentCoroutineContext().ensureActive()
            return result.takeIf {epoch==currentEpoch()}
        } catch(error: CancellationException) {
            throw error
        } catch(error: Exception) {
            currentCoroutineContext().ensureActive()
            if(epoch==currentEpoch())throw error
        } catch(error: LinkageError) {
            currentCoroutineContext().ensureActive()
            if(epoch==currentEpoch())throw error
        }
        return null
    }
}
