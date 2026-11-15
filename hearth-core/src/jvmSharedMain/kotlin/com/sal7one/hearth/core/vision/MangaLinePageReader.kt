package com.sal7one.hearth.core.vision

import com.sal7one.common_jni.ocr.MangaLineNative
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** One resident detector and CTC recognizer, shared by the Android and desktop adapters. */
class MangaLinePageReader(detector: Path, recognizer: Path, dictionary: Path, workers: Int) : PageOcrReader, AutoCloseable {
    private val vocabulary = readVocabulary(dictionary)

    private val handle = AtomicLong(MangaLineNative.create(
        detector.toAbsolutePath().toString().toByteArray(Charsets.UTF_8),
        recognizer.toAbsolutePath().toString().toByteArray(Charsets.UTF_8),
        workers,
    ).also { check(it != 0L) { "Could not open PP-OCRv6 Manga" } })

    override fun recognize(pixels: IntArray, width: Int, height: Int, progress: PageOcrProgress): List<PageText> {
        val id = handle.get()
        check(id != 0L) { "Manga line reader is closed" }
        require(width in 1..2048 && height in 1..2048 && pixels.size == width * height) { "OCR page must be at most 2048 pixels per side" }
        if (!progress.isCurrent()) return emptyList()
        progress.stage(PageOcrStage.PREVIEW)
        if (!progress.isCurrent()) return emptyList()
        val detections = MangaLineNative.detect(id, pixels, width, height, 960)
        if (!progress.isCurrent()) return emptyList()
        check(detections.size <= 64) { "Page exceeds 64 text regions. Draw a smaller area." }
        val regions = MangaPageLayout.orderLines(detections.map { row ->
            check(row.size == 5 && row[4] in 0..10_000) { "Invalid manga line detector output" }
            val bounds = OcrRect(row[0], row[1], row[2], row[3])
            check(bounds.x >= 0 && bounds.y >= 0 && bounds.width > 0 && bounds.height > 0 &&
                bounds.right <= width && bounds.bottom <= height) { "Manga line is outside the page" }
            OcrRegion(bounds, row[4] / 10_000f)
        }.distinctBy { it.bounds })
        progress.detected(regions)
        if (regions.isEmpty() || !progress.isCurrent()) return emptyList()
        progress.stage(PageOcrStage.RECOGNITION)
        if (!progress.isCurrent()) return emptyList()

        val boxes = IntArray(regions.size * 4)
        regions.forEachIndexed { index, region ->
            val offset = index * 4
            val (x, y, w, h) = region.bounds
            boxes[offset] = x
            boxes[offset + 1] = y
            boxes[offset + 2] = w
            boxes[offset + 3] = h
        }
        val tokens = MangaLineNative.recognize(id, pixels, width, height, boxes)
        if (!progress.isCurrent()) return emptyList()
        check(tokens.size == regions.size) { "Manga recognizer returned the wrong number of lines" }
        val result = regions.mapIndexedNotNull { index, region ->
            val text = normalizeMangaText(buildString {
                for (token in tokens[index]) {
                    check(token in 1 until vocabulary.size) { "Manga recognizer token is outside its vocabulary" }
                    append(vocabulary[token])
                }
            })
            text.takeIf(String::isNotEmpty)?.let { PageText(region, it) }
        }
        if (!progress.isCurrent()) return emptyList()
        progress.recognized(result)
        return result
    }

    override fun cancel() { handle.get().takeIf { it != 0L }?.let(MangaLineNative::cancel) }
    override fun close() { handle.getAndSet(0L).takeIf { it != 0L }?.let(MangaLineNative::destroy) }

    companion object {
        private fun readVocabulary(path: Path): List<String> {
            val spec = MangaArtifacts.lineVocabulary
            val bytes = ByteArray(spec.bytes.toInt())
            Files.newInputStream(path).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    check(count > 0) { "Truncated PP-OCRv6 Manga dictionary" }
                    offset += count
                }
                check(input.read() == -1) { "Oversized PP-OCRv6 Manga dictionary" }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(digest == spec.sha256) { "PP-OCRv6 Manga dictionary checksum failed" }
            val entries = bytes.inputStream().bufferedReader(Charsets.UTF_8).use { it.readLines() }
            check(entries.size == 19_053) { "PP-OCRv6 Manga dictionary does not match the recognizer" }
            return listOf("") + entries + " "
        }

        fun verifyNativeContract() {
            val failure = runCatching { MangaLineNative.detect(0, intArrayOf(0), 1, 1, 960) }.exceptionOrNull()
            check(failure is IllegalStateException && failure.message.orEmpty().contains("closed", ignoreCase = true)) {
                "Packaged Manga line OCR JNI did not reject a retired handle"
            }
        }
    }
}
