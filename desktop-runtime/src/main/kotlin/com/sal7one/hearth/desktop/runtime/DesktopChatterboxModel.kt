package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.tts.chatterbox.ChatterboxGraph
import com.sal7one.hearth.tts.chatterbox.ChatterboxPerth
import java.nio.file.Files
import java.nio.file.Path

/**
 * Publishes the two derived Chatterbox artifacts from the pinned downloaded sources.
 *
 * The publisher exports the language model with optional GQA slots that this ONNX Runtime
 * rejects, and Perth ships as a PyTorch ZIP. Both conversions are the Android-pinned,
 * non-pickle converters; they verify the source SHA-256, each tensor storage and the final
 * output SHA-256, and publish atomically. Nothing is downloaded and no Python is required.
 * Existing derived files are left untouched and re-verified by the engine.
 */
internal fun prepareChatterbox(root: Path) {
    require(Files.isDirectory(root)) { "Chatterbox model directory is missing" }
    if (!Files.isRegularFile(root.resolve(ChatterboxGraph.FILENAME))) {
        val source = root.resolve(ChatterboxGraph.SOURCE_FILENAME)
        require(Files.isRegularFile(source)) { "Chatterbox model is missing ${ChatterboxGraph.SOURCE_FILENAME}" }
        ChatterboxGraph.install(source.toFile(), root.toFile())
    }
    if (!Files.isRegularFile(root.resolve(ChatterboxPerth.FILENAME))) {
        val source = root.resolve("perth_net_250000.pth.tar")
        require(Files.isRegularFile(source)) { "Chatterbox model is missing perth_net_250000.pth.tar" }
        ChatterboxPerth.install(source.toFile(), root.toFile())
    }
}
