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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Transport glyphs drawn directly, so the app needs no icon library. */
enum class Glyph { Play, Pause, Stop, Record, Rewind, Metronome, ZoomIn, ZoomOut, Pads }

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
            Glyph.ZoomIn, Glyph.ZoomOut -> {
                drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), strokeWidth = w * 0.1f)
                if (glyph == Glyph.ZoomIn) {
                    drawLine(color, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), strokeWidth = w * 0.1f)
                }
            }
        }
    }
}
