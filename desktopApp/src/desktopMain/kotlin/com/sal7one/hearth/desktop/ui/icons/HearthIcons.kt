package com.sal7one.hearth.desktop.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A hand-drawn 24×24 line set. The app ships no icon font and no bitmap icons:
 * every glyph is a vector, tinted at the call site.
 */
internal object HearthIcons {

    private const val K = 0.5523f

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        val o = r * K
        moveTo(cx, cy - r)
        curveTo(cx + o, cy - r, cx + r, cy - o, cx + r, cy)
        curveTo(cx + r, cy + o, cx + o, cy + r, cx, cy + r)
        curveTo(cx - o, cy + r, cx - r, cy + o, cx - r, cy)
        curveTo(cx - r, cy - o, cx - o, cy - r, cx, cy - r)
        close()
    }

    private fun line(builder: ImageVector.Builder, block: PathBuilder.() -> Unit): ImageVector =
        builder
            .path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
            .build()

    private fun newBuilder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    val Microphone: ImageVector by lazy {
        line(newBuilder("Microphone")) {
            moveTo(9f, 5f)
            lineTo(9f, 11f)
            curveTo(9f, 12.7f, 10.3f, 14f, 12f, 14f)
            curveTo(13.7f, 14f, 15f, 12.7f, 15f, 11f)
            lineTo(15f, 5f)
            curveTo(15f, 3.3f, 13.7f, 2f, 12f, 2f)
            curveTo(10.3f, 2f, 9f, 3.3f, 9f, 5f)
            close()
            moveTo(5f, 10f)
            lineTo(5f, 11f)
            curveTo(5f, 14.9f, 8.1f, 18f, 12f, 18f)
            curveTo(15.9f, 18f, 19f, 14.9f, 19f, 11f)
            lineTo(19f, 10f)
            moveTo(12f, 18f)
            lineTo(12f, 22f)
        }
    }

    val ArrowRight: ImageVector by lazy {
        line(newBuilder("ArrowRight")) {
            moveTo(4f, 12f)
            lineTo(20f, 12f)
            moveTo(14f, 6f)
            lineTo(20f, 12f)
            lineTo(14f, 18f)
        }
    }

    val ArrowUpRight: ImageVector by lazy {
        line(newBuilder("ArrowUpRight")) {
            moveTo(7f, 17f)
            lineTo(17f, 7f)
            moveTo(9f, 7f)
            lineTo(17f, 7f)
            lineTo(17f, 15f)
        }
    }

    val Download: ImageVector by lazy {
        line(newBuilder("Download")) {
            moveTo(12f, 3f)
            lineTo(12f, 15f)
            moveTo(7f, 10f)
            lineTo(12f, 15f)
            lineTo(17f, 10f)
            moveTo(5f, 21f)
            lineTo(19f, 21f)
        }
    }

    val Swap: ImageVector by lazy {
        line(newBuilder("Swap")) {
            moveTo(7f, 4f)
            lineTo(4f, 7f)
            lineTo(7f, 10f)
            moveTo(4f, 7f)
            lineTo(16f, 7f)
            curveTo(17.7f, 7f, 19f, 8.3f, 19f, 10f)
            moveTo(17f, 20f)
            lineTo(20f, 17f)
            lineTo(17f, 14f)
            moveTo(20f, 17f)
            lineTo(8f, 17f)
            curveTo(6.3f, 17f, 5f, 15.7f, 5f, 14f)
        }
    }

    val Gear: ImageVector by lazy {
        line(newBuilder("Gear")) {
            circle(12f, 12f, 3.2f)
            circle(12f, 12f, 8.4f)
        }
    }

    val Moon: ImageVector by lazy {
        line(newBuilder("Moon")) {
            moveTo(20.5f, 14.5f)
            curveTo(19.2f, 15.1f, 17.8f, 15.5f, 16.3f, 15.5f)
            curveTo(11.1f, 15.5f, 6.9f, 11.3f, 6.9f, 6.1f)
            curveTo(6.9f, 5f, 7.1f, 4f, 7.4f, 3f)
            curveTo(4.2f, 4.4f, 2f, 7.6f, 2f, 11.3f)
            curveTo(2f, 16.4f, 6.1f, 20.5f, 11.2f, 20.5f)
            curveTo(15.1f, 20.5f, 18.4f, 18.3f, 19.7f, 15.1f)
            close()
        }
    }

    val Sun: ImageVector by lazy {
        line(newBuilder("Sun")) {
            circle(12f, 12f, 4.2f)
            moveTo(12f, 2f); lineTo(12f, 4f)
            moveTo(12f, 20f); lineTo(12f, 22f)
            moveTo(2f, 12f); lineTo(4f, 12f)
            moveTo(20f, 12f); lineTo(22f, 12f)
            moveTo(4.9f, 4.9f); lineTo(6.3f, 6.3f)
            moveTo(17.7f, 17.7f); lineTo(19.1f, 19.1f)
            moveTo(19.1f, 4.9f); lineTo(17.7f, 6.3f)
            moveTo(6.3f, 17.7f); lineTo(4.9f, 19.1f)
        }
    }

    val Warning: ImageVector by lazy {
        line(newBuilder("Warning")) {
            moveTo(10.3f, 3.9f)
            lineTo(2.4f, 18f)
            curveTo(1.6f, 19.4f, 2.6f, 21f, 4.1f, 21f)
            lineTo(19.9f, 21f)
            curveTo(21.4f, 21f, 22.4f, 19.4f, 21.6f, 18f)
            lineTo(13.7f, 3.9f)
            curveTo(12.9f, 2.5f, 11.1f, 2.5f, 10.3f, 3.9f)
            close()
            moveTo(12f, 9f); lineTo(12f, 13f)
            moveTo(12f, 17f); lineTo(12f, 17f)
        }
    }

    val Info: ImageVector by lazy {
        line(newBuilder("Info")) {
            circle(12f, 12f, 9f)
            moveTo(12f, 11f); lineTo(12f, 16f)
            moveTo(12f, 8f); lineTo(12f, 8f)
        }
    }

    val Check: ImageVector by lazy {
        line(newBuilder("Check")) {
            moveTo(20f, 6f)
            lineTo(9f, 17f)
            lineTo(4f, 12f)
        }
    }

    val Close: ImageVector by lazy {
        line(newBuilder("Close")) {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }

    val Play: ImageVector by lazy {
        newBuilder("Play")
            .path(fill = SolidColor(Color.Black), pathBuilder = {
                moveTo(8f, 5.2f)
                lineTo(19f, 12f)
                lineTo(8f, 18.8f)
                close()
            })
            .build()
    }

    val Pause: ImageVector by lazy {
        newBuilder("Pause")
            .path(fill = SolidColor(Color.Black), pathBuilder = {
                moveTo(8f, 5f); lineTo(10.6f, 5f); lineTo(10.6f, 19f); lineTo(8f, 19f); close()
                moveTo(13.4f, 5f); lineTo(16f, 5f); lineTo(16f, 19f); lineTo(13.4f, 19f); close()
            })
            .build()
    }

    val Stop: ImageVector by lazy {
        newBuilder("Stop")
            .path(fill = SolidColor(Color.Black), pathBuilder = {
                moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close()
            })
            .build()
    }

    val Plus: ImageVector by lazy {
        line(newBuilder("Plus")) {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(5f, 12f); lineTo(19f, 12f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        line(newBuilder("ChevronDown")) {
            moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
        }
    }

    val ChevronLeft: ImageVector by lazy {
        line(newBuilder("ChevronLeft")) {
            moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f)
        }
    }

    val Folder: ImageVector by lazy {
        line(newBuilder("Folder")) {
            moveTo(3f, 7f)
            curveTo(3f, 5.9f, 3.9f, 5f, 5f, 5f)
            lineTo(9f, 5f)
            lineTo(11f, 8f)
            lineTo(19f, 8f)
            curveTo(20.1f, 8f, 21f, 8.9f, 21f, 10f)
            lineTo(21f, 17f)
            curveTo(21f, 18.1f, 20.1f, 19f, 19f, 19f)
            lineTo(5f, 19f)
            curveTo(3.9f, 19f, 3f, 18.1f, 3f, 17f)
            close()
        }
    }

    val Speaker: ImageVector by lazy {
        line(newBuilder("Speaker")) {
            moveTo(4f, 10f); lineTo(7f, 10f); lineTo(11f, 6f); lineTo(11f, 18f); lineTo(7f, 14f); lineTo(4f, 14f)
            close()
            moveTo(15f, 8.5f)
            curveTo(16.4f, 9.5f, 16.4f, 14.5f, 15f, 15.5f)
            moveTo(18f, 6f)
            curveTo(20.8f, 8.2f, 20.8f, 15.8f, 18f, 18f)
        }
    }

    val WarningFilled: ImageVector by lazy {
        line(newBuilder("WarningFilled")) {
            circle(12f, 12f, 9f)
            moveTo(12f, 7.5f); lineTo(12f, 13f)
            moveTo(12f, 16.5f); lineTo(12f, 16.5f)
        }
    }

    val Search: ImageVector by lazy {
        line(newBuilder("Search")) {
            circle(11f, 11f, 7f)
            moveTo(16.2f, 16.2f); lineTo(21f, 21f)
        }
    }

    val Overlay: ImageVector by lazy {
        line(newBuilder("Overlay")) {
            moveTo(3f, 6f)
            curveTo(3f, 4.9f, 3.9f, 4f, 5f, 4f)
            lineTo(19f, 4f)
            curveTo(20.1f, 4f, 21f, 4.9f, 21f, 6f)
            lineTo(21f, 14f)
            curveTo(21f, 15.1f, 20.1f, 16f, 19f, 16f)
            lineTo(5f, 16f)
            curveTo(3.9f, 16f, 3f, 15.1f, 3f, 14f)
            close()
            moveTo(12f, 16f); lineTo(12f, 21f)
            moveTo(8f, 21f); lineTo(16f, 21f)
        }
    }

    val Display: ImageVector by lazy {
        line(newBuilder("Display")) {
            moveTo(2.5f, 5f)
            curveTo(2.5f, 4.2f, 3.2f, 3.5f, 4f, 3.5f)
            lineTo(20f, 3.5f)
            curveTo(20.8f, 3.5f, 21.5f, 4.2f, 21.5f, 5f)
            lineTo(21.5f, 15f)
            curveTo(21.5f, 15.8f, 20.8f, 16.5f, 20f, 16.5f)
            lineTo(4f, 16.5f)
            curveTo(3.2f, 16.5f, 2.5f, 15.8f, 2.5f, 15f)
            close()
            moveTo(8f, 20.5f); lineTo(16f, 20.5f)
            moveTo(12f, 16.5f); lineTo(12f, 20.5f)
        }
    }

    val Sparkle: ImageVector by lazy {
        line(newBuilder("Sparkle")) {
            moveTo(12f, 3f)
            curveTo(12f, 8f, 16f, 12f, 21f, 12f)
            curveTo(16f, 12f, 12f, 16f, 12f, 21f)
            curveTo(12f, 16f, 8f, 12f, 3f, 12f)
            curveTo(8f, 12f, 12f, 8f, 12f, 3f)
            close()
        }
    }
}
