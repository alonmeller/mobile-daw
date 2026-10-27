package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Transport glyphs drawn directly, so the app needs no icon library. */
enum class Glyph { Play, Pause, Stop, Record, Rewind, Metronome, ZoomIn, ZoomOut, Pads, Songs, Share, Settings, Headphones }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when (glyph) {
            Glyph.Play -> {
                val p = Path().apply {
                    moveTo(w * 0.22f, h * 0.12f)
                    lineTo(w * 0.88f, h * 0.5f)
                    lineTo(w * 0.22f, h * 0.88f)
                    close()
                }
                drawPath(p, color)
            }
            Glyph.Pause -> {
                drawRoundRect(color, Offset(w * 0.2f, h * 0.14f), Size(w * 0.2f, h * 0.72f), CornerRadius(w * 0.04f))
                drawRoundRect(color, Offset(w * 0.6f, h * 0.14f), Size(w * 0.2f, h * 0.72f), CornerRadius(w * 0.04f))
            }
            Glyph.Stop -> {
                drawRoundRect(color, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f), CornerRadius(w * 0.08f))
            }
            Glyph.Record -> drawCircle(color, radius = w * 0.36f)
            Glyph.Rewind -> {
                drawRect(color, Offset(w * 0.12f, h * 0.16f), Size(w * 0.1f, h * 0.68f))
                val p = Path().apply {
                    moveTo(w * 0.88f, h * 0.16f)
                    lineTo(w * 0.28f, h * 0.5f)
                    lineTo(w * 0.88f, h * 0.84f)
                    close()
                }
                drawPath(p, color)
            }
            Glyph.Metronome -> {
                val body = Path().apply {
                    moveTo(w * 0.38f, h * 0.1f)
                    lineTo(w * 0.62f, h * 0.1f)
                    lineTo(w * 0.85f, h * 0.9f)
                    lineTo(w * 0.15f, h * 0.9f)
                    close()
                }
                drawPath(body, color, style = Stroke(width = w * 0.08f))
                drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.78f, h * 0.22f), strokeWidth = w * 0.08f)
            }
            Glyph.Pads -> {
                // A 3x3 grid of pads, as on a drum machine.
                val cell = w * 0.26f
                val gap = (w - cell * 3) / 4
                for (row in 0..2) for (col in 0..2) {
                    drawRoundRect(
                        color, Offset(gap + col * (cell + gap), gap + row * (cell + gap)), Size(cell, cell),
                        CornerRadius(w * 0.05f),
                    )
                }
            }
            Glyph.Songs -> {
                // A folder, for the song list.
                val folder = Path().apply {
                    moveTo(w * 0.1f, h * 0.22f)
                    lineTo(w * 0.4f, h * 0.22f)
                    lineTo(w * 0.5f, h * 0.33f)
                    lineTo(w * 0.9f, h * 0.33f)
                    lineTo(w * 0.9f, h * 0.82f)
                    lineTo(w * 0.1f, h * 0.82f)
                    close()
                }
                drawPath(folder, color, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
            }
            Glyph.Share -> {
                // A box with an arrow leaving it, the usual "share" sign.
                val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val box = Path().apply {
                    moveTo(w * 0.36f, h * 0.4f)
                    lineTo(w * 0.2f, h * 0.4f)
                    lineTo(w * 0.2f, h * 0.9f)
                    lineTo(w * 0.8f, h * 0.9f)
                    lineTo(w * 0.8f, h * 0.4f)
                    lineTo(w * 0.64f, h * 0.4f)
                }
                drawPath(box, color, style = stroke)
                val arrow = Path().apply {
                    moveTo(w * 0.5f, h * 0.62f)
                    lineTo(w * 0.5f, h * 0.08f)
                    moveTo(w * 0.32f, h * 0.25f)
                    lineTo(w * 0.5f, h * 0.08f)
                    lineTo(w * 0.68f, h * 0.25f)
                }
                drawPath(arrow, color, style = stroke)
            }
            Glyph.Settings -> {
                // A cog: eight teeth round a ring.
                val c = Offset(w / 2, h / 2)
                for (k in 0 until 8) {
                    rotate(k * 45f, c) {
                        drawRoundRect(color, Offset(w * 0.42f, h * 0.06f), Size(w * 0.16f, h * 0.2f), CornerRadius(w * 0.03f))
                    }
                }
                drawCircle(color, radius = w * 0.26f, center = c, style = Stroke(width = w * 0.15f))
            }
            Glyph.Headphones -> {
                drawArc(
                    color, 180f, 180f, false, Offset(w * 0.17f, h * 0.14f), Size(w * 0.66f, h * 0.72f),
                    style = Stroke(width = w * 0.09f, cap = StrokeCap.Round),
                )
                drawRoundRect(color, Offset(w * 0.1f, h * 0.5f), Size(w * 0.2f, h * 0.36f), CornerRadius(w * 0.06f))
                drawRoundRect(color, Offset(w * 0.7f, h * 0.5f), Size(w * 0.2f, h * 0.36f), CornerRadius(w * 0.06f))
            }
            Glyph.ZoomIn, Glyph.ZoomOut -> {
                drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), strokeWidth = w * 0.1f)
                if (glyph == Glyph.ZoomIn) {
                    drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), strokeWidth = w * 0.1f)
                }
            }
        }
    }
}
