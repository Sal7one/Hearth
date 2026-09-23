package com.sal7one.transiber.translation

import android.content.Context
import com.sal7one.common_jni.marian.MarianTranslationSession
import com.sal7one.common_jni.speech.TranslationDirection
import com.sal7one.common_jni.translation.CancellableTextTranslator
import com.sal7one.transiber.downloads.FileDownloads

/** Explicit two-model routes. A pivot is never inferred from a model's language count. */
internal object MarianCascade {
    data class Route(val id: String, val first: MarianPackage.Pair, val second: MarianPackage.Pair) {
        val source: String get() = first.source
        val target: String get() = second.target
        val downloadBytes: Long get() = first.downloadBytes + second.downloadBytes
        init { require(first.target == second.source && source != target) }
    }

    val routes: List<Route> = listOf("ru", "zh").map { source ->
        Route("marian-$source-ar-via-en", checkNotNull(MarianPackage.find("marian-$source-en")),
            checkNotNull(MarianPackage.find(TranslationOptions.MARIAN_EN_AR)))
    }
    fun find(id: String): Route? = routes.firstOrNull { it.id == id }
    fun installed(context: Context, route: Route): Boolean =
        MarianPackage.installed(context, route.first) != null && MarianPackage.installed(context, route.second) != null
    fun enqueue(context: Context, downloads: FileDownloads, route: Route) {
        MarianPackage.enqueue(context, downloads, route.first)
        MarianPackage.enqueue(context, downloads, route.second)
    }
    fun open(context: Context, route: Route): CancellableTextTranslator {
        val first = MarianTranslationSession.open(MarianPackage.modelDirectory(context, route.first),
            TranslationDirection(route.first.source, route.first.target), TranslationOptions.label(route.first.id))
        try {
            val second = MarianTranslationSession.open(MarianPackage.modelDirectory(context, route.second),
                TranslationDirection(route.second.source, route.second.target), TranslationOptions.label(route.second.id))
            return MarianCascadeTranslator(route.id, first, second,
                TranslationDirection(route.source, route.target))
        } catch (failure: Throwable) {
            first.close()
            throw failure
        }
    }
}

/** Owns both native sessions and keeps the intermediate English text inside this call. */
internal class MarianCascadeTranslator(
    override val id: String,
    private val first: CancellableTextTranslator,
    private val second: CancellableTextTranslator,
    private val direction: TranslationDirection,
) : CancellableTextTranslator {
    override val directions: Set<TranslationDirection> = setOf(direction)
    override suspend fun translate(text: String, direction: TranslationDirection): String {
        require(direction in directions) { "$id does not support ${direction.source} → ${direction.target}" }
        val sourceToEnglish = TranslationDirection(direction.source, "en")
        val englishToTarget = TranslationDirection("en", direction.target)
        check(sourceToEnglish in first.directions && englishToTarget in second.directions) {
            "$id is missing one of its two installed language directions"
        }
        val intermediate = first.translate(text, sourceToEnglish)
        check(intermediate.isNotBlank()) { "${first.id} returned empty English text" }
        return second.translate(intermediate, englishToTarget).also {
            check(it.isNotBlank()) { "${second.id} returned empty ${direction.target} text" }
        }
    }
    override fun cancel() { try { first.cancel() } finally { second.cancel() } }
    override fun close() { try { first.close() } finally { second.close() } }
}
