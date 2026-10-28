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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Glyphs drawn directly, so the app needs no icon library. */
enum class Glyph {
    Play, Pause, Stop, Record, Rewind, Cycle, Metronome, Songs, Share, Settings, Headphones,
    Speaker, Monitor, Microphone, Drums, Keys, Stack, Bus, Tracks, Mixer, Plus, Power,
    WaveSine, WaveTriangle, WaveSaw, WaveSquare,
    ZoomIn, ZoomOut, FitSong, TallerTracks, ShorterTracks,
}

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

/** Draws [glyph] filling this scope's size. */
fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    val round = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (glyph) {
        Glyph.Play -> {
            val p = Path().apply {
                moveTo(w * 0.24f, h * 0.14f)
                lineTo(w * 0.86f, h * 0.5f)
                lineTo(w * 0.24f, h * 0.86f)
                close()
            }
            drawPath(p, color)
            drawPath(p, color, style = Stroke(width = w * 0.06f, join = StrokeJoin.Round))
        }
        Glyph.Pause -> {
            drawRoundRect(color, Offset(w * 0.22f, h * 0.16f), Size(w * 0.19f, h * 0.68f), CornerRadius(w * 0.04f))
            drawRoundRect(color, Offset(w * 0.59f, h * 0.16f), Size(w * 0.19f, h * 0.68f), CornerRadius(w * 0.04f))
        }
        Glyph.Stop -> {
            drawRoundRect(color, Offset(w * 0.2f, h * 0.2f), Size(w * 0.6f, h * 0.6f), CornerRadius(w * 0.08f))
        }
        Glyph.Record -> drawCircle(color, radius = w * 0.32f)
        Glyph.Rewind -> {
            // Go to the beginning: a bar and a triangle pointing at it.
            drawRoundRect(color, Offset(w * 0.16f, h * 0.18f), Size(w * 0.1f, h * 0.64f), CornerRadius(w * 0.03f))
            val p = Path().apply {
                moveTo(w * 0.84f, h * 0.18f)
                lineTo(w * 0.32f, h * 0.5f)
                lineTo(w * 0.84f, h * 0.82f)
                close()
            }
            drawPath(p, color)
        }
        Glyph.Cycle -> {
            // GarageBand's cycle button: two arrows chasing each other round.
            val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round)
            val topLeft = Offset(w * 0.12f, h * 0.26f)
            val oval = Size(w * 0.76f, h * 0.48f)
            drawArc(color, 200f, 150f, false, topLeft, oval, style = stroke)
            drawArc(color, 20f, 150f, false, topLeft, oval, style = stroke)
            val a = w * 0.13f
            // Arrow heads at the end of each arc.
            drawPath(Path().apply {
                moveTo(w * 0.86f, h * 0.36f)
                lineTo(w * 0.86f - a, h * 0.36f - a * 0.2f)
                lineTo(w * 0.86f - a * 0.35f, h * 0.36f - a)
                close()
            }, color)
            drawPath(Path().apply {
                moveTo(w * 0.14f, h * 0.64f)
                lineTo(w * 0.14f + a, h * 0.64f + a * 0.2f)
                lineTo(w * 0.14f + a * 0.35f, h * 0.64f + a)
                close()
            }, color)
        }
        Glyph.Metronome -> {
            val body = Path().apply {
                moveTo(w * 0.39f, h * 0.1f)
                lineTo(w * 0.61f, h * 0.1f)
                lineTo(w * 0.84f, h * 0.9f)
                lineTo(w * 0.16f, h * 0.9f)
                close()
            }
            drawPath(body, color, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
            drawLine(color, Offset(w * 0.24f, h * 0.68f), Offset(w * 0.76f, h * 0.68f), strokeWidth = w * 0.07f)
            drawLine(color, Offset(w * 0.5f, h * 0.68f), Offset(w * 0.74f, h * 0.24f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
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
            drawPath(folder, color, style = round)
        }
        Glyph.Share -> {
            // A box with an arrow leaving it, the usual "share" sign.
            val box = Path().apply {
                moveTo(w * 0.36f, h * 0.4f)
                lineTo(w * 0.2f, h * 0.4f)
                lineTo(w * 0.2f, h * 0.9f)
                lineTo(w * 0.8f, h * 0.9f)
                lineTo(w * 0.8f, h * 0.4f)
                lineTo(w * 0.64f, h * 0.4f)
            }
            drawPath(box, color, style = round)
            val arrow = Path().apply {
                moveTo(w * 0.5f, h * 0.62f)
                lineTo(w * 0.5f, h * 0.08f)
                moveTo(w * 0.32f, h * 0.25f)
                lineTo(w * 0.5f, h * 0.08f)
                lineTo(w * 0.68f, h * 0.25f)
            }
            drawPath(arrow, color, style = round)
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
            // GarageBand's solo button.
            drawArc(
                color, 180f, 180f, false, Offset(w * 0.17f, h * 0.14f), Size(w * 0.66f, h * 0.72f),
                style = Stroke(width = w * 0.09f, cap = StrokeCap.Round),
            )
            drawRoundRect(color, Offset(w * 0.1f, h * 0.5f), Size(w * 0.2f, h * 0.36f), CornerRadius(w * 0.06f))
            drawRoundRect(color, Offset(w * 0.7f, h * 0.5f), Size(w * 0.2f, h * 0.36f), CornerRadius(w * 0.06f))
        }
        Glyph.Speaker -> {
            // GarageBand's mute button: a loudspeaker.
            val p = Path().apply {
                moveTo(w * 0.14f, h * 0.38f)
                lineTo(w * 0.34f, h * 0.38f)
                lineTo(w * 0.6f, h * 0.16f)
                lineTo(w * 0.6f, h * 0.84f)
                lineTo(w * 0.34f, h * 0.62f)
                lineTo(w * 0.14f, h * 0.62f)
                close()
            }
            drawPath(p, color)
            drawArc(
                color, -45f, 90f, false, Offset(w * 0.5f, h * 0.3f), Size(w * 0.36f, h * 0.4f),
                style = Stroke(width = w * 0.08f, cap = StrokeCap.Round),
            )
        }
        Glyph.Monitor -> {
            // Hearing the input: a dot sending out waves.
            drawCircle(color, w * 0.11f, Offset(w * 0.26f, h * 0.5f))
            val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
            drawArc(color, -50f, 100f, false, Offset(w * 0.18f, h * 0.26f), Size(w * 0.36f, h * 0.48f), style = stroke)
            drawArc(color, -50f, 100f, false, Offset(w * 0.1f, h * 0.1f), Size(w * 0.68f, h * 0.8f), style = stroke)
        }
        Glyph.Microphone -> {
            // A studio microphone on a stand.
            drawRoundRect(color, Offset(w * 0.36f, h * 0.08f), Size(w * 0.28f, h * 0.46f), CornerRadius(w * 0.14f))
            val grille = Color.Black.copy(alpha = 0.25f)
            for (k in 1..3) {
                val y = h * (0.12f + k * 0.09f)
                drawLine(grille, Offset(w * 0.4f, y), Offset(w * 0.6f, y), strokeWidth = w * 0.035f)
            }
            drawArc(color, 0f, 180f, false, Offset(w * 0.27f, h * 0.24f), Size(w * 0.46f, h * 0.44f), style = round)
            drawLine(color, Offset(w * 0.5f, h * 0.68f), Offset(w * 0.5f, h * 0.86f), strokeWidth = w * 0.08f)
            drawLine(color, Offset(w * 0.32f, h * 0.88f), Offset(w * 0.68f, h * 0.88f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        }
        Glyph.Drums -> {
            // A snare drum with two sticks over it.
            val stroke = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.18f, h * 0.12f), Offset(w * 0.58f, h * 0.44f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.82f, h * 0.12f), Offset(w * 0.42f, h * 0.44f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
            val body = Path().apply {
                moveTo(w * 0.12f, h * 0.52f)
                lineTo(w * 0.12f, h * 0.78f)
                cubicTo(w * 0.373f, h * 0.913f, w * 0.627f, h * 0.913f, w * 0.88f, h * 0.78f)
                lineTo(w * 0.88f, h * 0.52f)
            }
            drawPath(body, color, style = stroke)
            drawOval(color, Offset(w * 0.12f, h * 0.42f), Size(w * 0.76f, h * 0.2f))
            for (k in 1..3) {
                val x = w * (0.12f + k * 0.19f)
                drawLine(color, Offset(x, h * 0.6f), Offset(x, h * 0.84f), strokeWidth = w * 0.045f)
            }
        }
        Glyph.Keys -> {
            // A few piano keys.
            val top = h * 0.2f
            val keyH = h * 0.6f
            val x0 = w * 0.1f
            val keyW = w * 0.8f / 5
            drawRoundRect(color, Offset(x0, top), Size(w * 0.8f, keyH), CornerRadius(w * 0.05f), style = Stroke(width = w * 0.06f))
            for (k in 1 until 5) {
                drawLine(color, Offset(x0 + k * keyW, top), Offset(x0 + k * keyW, top + keyH), strokeWidth = w * 0.045f)
            }
            for (k in listOf(1, 2, 4)) {
                drawRoundRect(color, Offset(x0 + k * keyW - keyW * 0.3f, top), Size(keyW * 0.6f, keyH * 0.58f), CornerRadius(w * 0.02f))
            }
        }
        Glyph.Stack -> {
            // A folder of tracks: layers on top of each other, like GarageBand's track stacks.
            drawRoundRect(color.copy(alpha = 0.55f), Offset(w * 0.24f, h * 0.14f), Size(w * 0.62f, h * 0.44f), CornerRadius(w * 0.08f))
            drawRoundRect(color.copy(alpha = 0.8f), Offset(w * 0.18f, h * 0.27f), Size(w * 0.62f, h * 0.44f), CornerRadius(w * 0.08f))
            drawRoundRect(color, Offset(w * 0.12f, h * 0.4f), Size(w * 0.62f, h * 0.44f), CornerRadius(w * 0.08f))
        }
        Glyph.Bus -> {
            // An effect that tracks send to: sound spreading out, like a reverb.
            drawCircle(color, w * 0.09f, Offset(w * 0.5f, h * 0.72f))
            val stroke = Stroke(width = w * 0.075f, cap = StrokeCap.Round)
            for (k in 1..3) {
                val r = w * 0.13f * k + w * 0.06f
                drawArc(color.copy(alpha = 1f - k * 0.2f), 210f, 120f, false, Offset(w * 0.5f - r, h * 0.72f - r), Size(r * 2, r * 2), style = stroke)
            }
        }
        Glyph.Tracks -> {
            // The tracks view: regions on lanes.
            val bar = h * 0.16f
            drawRoundRect(color, Offset(w * 0.1f, h * 0.18f), Size(w * 0.5f, bar), CornerRadius(bar / 3))
            drawRoundRect(color, Offset(w * 0.32f, h * 0.42f), Size(w * 0.58f, bar), CornerRadius(bar / 3))
            drawRoundRect(color, Offset(w * 0.1f, h * 0.66f), Size(w * 0.66f, bar), CornerRadius(bar / 3))
        }
        Glyph.Mixer -> {
            // The mixer: three faders.
            for ((k, knob) in listOf(0.62f, 0.32f, 0.5f).withIndex()) {
                val x = w * (0.24f + k * 0.26f)
                drawLine(color, Offset(x, h * 0.12f), Offset(x, h * 0.88f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
                drawRoundRect(color, Offset(x - w * 0.1f, h * knob - h * 0.07f), Size(w * 0.2f, h * 0.14f), CornerRadius(w * 0.03f))
            }
        }
        Glyph.Plus -> {
            drawLine(color, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.5f, h * 0.82f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.82f, h * 0.5f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        }
        Glyph.ZoomIn, Glyph.ZoomOut -> {
            // A magnifying glass with a plus or a minus in it.
            val c = Offset(w * 0.42f, h * 0.42f)
            val r = w * 0.27f
            val line = w * 0.09f
            drawCircle(color, r, c, style = Stroke(line))
            drawLine(color, Offset(c.x + r * 0.74f, c.y + r * 0.74f), Offset(w * 0.86f, h * 0.86f), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
            drawLine(color, Offset(c.x - r * 0.5f, c.y), Offset(c.x + r * 0.5f, c.y), strokeWidth = line, cap = StrokeCap.Round)
            if (glyph == Glyph.ZoomIn) {
                drawLine(color, Offset(c.x, c.y - r * 0.5f), Offset(c.x, c.y + r * 0.5f), strokeWidth = line, cap = StrokeCap.Round)
            }
        }
        Glyph.FitSong -> {
            // The whole song: arrows out to its two ends.
            val line = w * 0.08f
            val head = w * 0.13f
            val mid = h * 0.5f
            for (x in listOf(w * 0.12f, w * 0.88f)) {
                drawLine(color, Offset(x, h * 0.26f), Offset(x, h * 0.74f), strokeWidth = line, cap = StrokeCap.Round)
            }
            drawLine(color, Offset(w * 0.27f, mid), Offset(w * 0.73f, mid), strokeWidth = line, cap = StrokeCap.Round)
            for ((tip, back) in listOf(w * 0.27f to 1f, w * 0.73f to -1f)) {
                val p = Path().apply {
                    moveTo(tip + head * back, mid - head)
                    lineTo(tip, mid)
                    lineTo(tip + head * back, mid + head)
                }
                drawPath(p, color, style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Glyph.TallerTracks, Glyph.ShorterTracks -> {
            // The line between two tracks, with arrows pushing them apart or together.
            val line = w * 0.08f
            val head = w * 0.15f
            val mid = h * 0.5f
            val apart = glyph == Glyph.TallerTracks
            drawLine(color, Offset(w * 0.14f, mid), Offset(w * 0.86f, mid), strokeWidth = line, cap = StrokeCap.Round)
            for (dir in listOf(-1f, 1f)) {
                val near = mid + dir * h * 0.15f
                val far = mid + dir * h * 0.4f
                drawLine(color, Offset(w * 0.5f, near), Offset(w * 0.5f, far), strokeWidth = line, cap = StrokeCap.Round)
                val tip = if (apart) far else near
                val back = if (apart) -dir else dir
                val p = Path().apply {
                    moveTo(w * 0.5f - head, tip + back * head)
                    lineTo(w * 0.5f, tip)
                    lineTo(w * 0.5f + head, tip + back * head)
                }
                drawPath(p, color, style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Glyph.Power -> {
            // A plug-in's on/off switch: a ring open at the top, and a bar through the gap.
            drawArc(
                color, 305f, 290f, false, Offset(w * 0.16f, h * 0.18f), Size(w * 0.68f, h * 0.68f),
                style = Stroke(width = w * 0.1f, cap = StrokeCap.Round),
            )
            drawLine(color, Offset(w * 0.5f, h * 0.1f), Offset(w * 0.5f, h * 0.48f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
        }
        Glyph.WaveSine, Glyph.WaveTriangle, Glyph.WaveSaw, Glyph.WaveSquare -> {
            // One cycle of the synth's waveform, as drawn on a synth's wave switch.
            val top = h * 0.26f
            val bottom = h * 0.74f
            val mid = h * 0.5f
            val p = Path()
            when (glyph) {
                Glyph.WaveSine -> for (i in 0..24) {
                    val t = i / 24f
                    val x = w * (0.1f + 0.8f * t)
                    val y = mid - (mid - top) * sin(2 * PI * t).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                Glyph.WaveTriangle -> {
                    p.moveTo(w * 0.1f, mid)
                    p.lineTo(w * 0.3f, top)
                    p.lineTo(w * 0.7f, bottom)
                    p.lineTo(w * 0.9f, mid)
                }
                Glyph.WaveSaw -> {
                    p.moveTo(w * 0.1f, bottom)
                    p.lineTo(w * 0.5f, top)
                    p.lineTo(w * 0.5f, bottom)
                    p.lineTo(w * 0.9f, top)
                    p.lineTo(w * 0.9f, bottom)
                }
                else -> {
                    p.moveTo(w * 0.1f, bottom)
                    p.lineTo(w * 0.1f, top)
                    p.lineTo(w * 0.5f, top)
                    p.lineTo(w * 0.5f, bottom)
                    p.lineTo(w * 0.9f, bottom)
                    p.lineTo(w * 0.9f, top)
                }
            }
            drawPath(p, color, style = round)
        }
    }
}
