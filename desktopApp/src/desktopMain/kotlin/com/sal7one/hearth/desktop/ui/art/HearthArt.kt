package com.sal7one.hearth.desktop.ui.art

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * The six generated feature illustrations, shared with Android.
 *
 * They ship as WebP and are decoded through Skia at first use: Java's ImageIO has
 * no WebP reader, and re-encoding them to PNG would multiply the installer size.
 * A missing or corrupt resource returns null so a tile can fall back to a tonal
 * panel instead of crashing the window.
 */
internal enum class HearthArt(val file: String, val alt: String) {
    CAPTIONS("captions.webp", "Live captions artwork"),
    CONVERSATION("conversation.webp", "Conversation artwork"),
    FACE("face.webp", "Face to face artwork"),
    TEXT("text.webp", "Type to translate artwork"),
    CAMERA("camera.webp", "Camera and photos artwork"),
    SCREEN("screen.webp", "Screen and manga artwork"),
}

@Composable
internal fun rememberArtwork(art: HearthArt): ImageBitmap? = remember(art) {
    runCatching {
        val stream = HearthArt::class.java.classLoader?.getResourceAsStream("art/${art.file}")
            ?: return@runCatching null
        stream.use { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
    }.getOrNull()
}
