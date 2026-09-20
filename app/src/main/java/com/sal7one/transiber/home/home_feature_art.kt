package com.sal7one.transiber.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform

internal enum class HomeArt { LISTEN, TALK, TRANSLATE, FACE, TYPE, CAMERA, SCREEN }

/** Original vector illustrations: scale with the button, no downloads or animation. */
@Composable
internal fun HomeFeatureArt(art: HomeArt, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val soft = MaterialTheme.colorScheme.secondaryContainer
    Canvas(modifier) {
        val scale = minOf(size.width / 220f, size.height / 160f)
        withTransform({
            translate((size.width - 220f * scale) / 2, (size.height - 160f * scale) / 2)
            scale(scale, scale, Offset.Zero)
        }) {
            drawCircle(accent.copy(alpha = .10f), 65f, Offset(117f, 82f))
            when (art) {
                HomeArt.LISTEN -> {
                    panel(45f, 18f, 134f, 115f, paper)
                    val heights = listOf(15f, 29f, 43f, 22f, 52f, 34f, 18f)
                    heights.forEachIndexed { i, h -> panel(65f + i * 13f, 65f - h / 2, 6f, h, accent, 3f) }
                    panel(60f, 106f, 135f, 35f, accent)
                    line(73f, 118f, 106f, paper)
                    line(73f, 128f, 76f, paper.copy(alpha = .7f))
                }
                HomeArt.TALK, HomeArt.FACE -> {
                    bubble(26f, 21f, 123f, 69f, paper, false)
                    line(43f, 43f, 82f, ink.copy(alpha = .7f))
                    line(43f, 56f, 57f, ink.copy(alpha = .4f))
                    bubble(86f, 81f, 111f, 61f, accent, true)
                    line(104f, 102f, 71f, paper)
                    line(124f, 115f, 51f, paper.copy(alpha = .7f))
                    if (art == HomeArt.FACE) {
                        drawCircle(soft, 15f, Offset(44f, 128f))
                        drawCircle(accent, 15f, Offset(181f, 35f))
                    }
                }
                HomeArt.TRANSLATE, HomeArt.TYPE -> {
                    withTransform({ rotate(-9f, Offset(100f, 80f)) }) {
                        panel(34f, 23f, 98f, 116f, paper)
                        line(49f, 45f, 64f, ink.copy(alpha = .6f))
                        line(49f, 59f, 45f, ink.copy(alpha = .3f))
                        line(49f, 80f, 56f, ink.copy(alpha = .3f))
                        line(49f, 94f, 62f, ink.copy(alpha = .3f))
                    }
                    panel(108f, 60f, 91f, 76f, accent)
                    // A and a flowing second-script mark, part of the illustration only.
                    drawLine(paper, Offset(125f, 104f), Offset(138f, 77f), 4f)
                    drawLine(paper, Offset(138f, 77f), Offset(151f, 104f), 4f)
                    drawLine(paper, Offset(130f, 95f), Offset(147f, 95f), 4f)
                    val script = Path().apply { moveTo(180f, 79f); cubicTo(159f, 72f, 157f, 99f, 183f, 96f); cubicTo(181f, 109f, 165f, 119f, 153f, 111f) }
                    drawPath(script, paper, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                }
                HomeArt.CAMERA -> {
                    panel(29f, 42f, 164f, 96f, paper)
                    panel(55f, 27f, 51f, 25f, paper)
                    drawCircle(accent, 34f, Offset(110f, 89f))
                    drawCircle(paper, 21f, Offset(110f, 89f))
                    drawCircle(soft, 13f, Offset(110f, 89f))
                    panel(157f, 57f, 18f, 10f, accent, 4f)
                }
                HomeArt.SCREEN -> {
                    panel(63f, 9f, 98f, 142f, paper, 17f)
                    panel(72f, 30f, 80f, 103f, soft, 5f)
                    panel(92f, 19f, 39f, 4f, ink.copy(alpha = .4f), 2f)
                    line(84f, 47f, 53f, ink.copy(alpha = .35f))
                    line(84f, 59f, 42f, ink.copy(alpha = .35f))
                    bubble(99f, 78f, 97f, 52f, accent, true)
                    line(112f, 95f, 64f, paper)
                    line(129f, 108f, 47f, paper.copy(alpha = .7f))
                }
            }
        }
    }
}

private fun DrawScope.panel(x: Float, y: Float, w: Float, h: Float, color: Color, radius: Float = 12f) =
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius))
private fun DrawScope.line(x: Float, y: Float, width: Float, color: Color) = panel(x, y, width, 5f, color, 2.5f)
private fun DrawScope.bubble(x: Float, y: Float, w: Float, h: Float, color: Color, right: Boolean) {
    panel(x, y, w, h, color, 15f)
    val tail = if (right) x + w - 22f else x + 22f
    drawPath(Path().apply { moveTo(tail, y + h - 5f); lineTo(tail, y + h + 12f); lineTo(tail + if (right) -19f else 19f, y + h - 5f); close() }, color)
}
