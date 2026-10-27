package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.alonmeller.mobiledaw.model.Scales
import kotlin.math.abs

/** Height of the fader cap; meters next to a fader are inset by half of it so their scales line up. */
val FaderCap = 30.dp

private val FaderTicks = listOf(6f, 0f, -6f, -12f, -24f, -40f)
private val YellowAt = Scales.gainToPosition(0.5f) // -6 dB
private val RedAt = Scales.gainToPosition(1f) // 0 dB

/**
 * Compact horizontal slider over positions 0..1, drawn by hand so it fits
 * tight rows. A drag moves the value relative to where it was, so touching
 * the slider never makes it jump, and a double tap calls [onReset], like a
 * double-click in REAPER. With [origin] set, the fill starts there (pan).
 */
@Composable
fun MiniSlider(
    position: Float,
    color: Color,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    origin: Float? = null,
    onReset: (() -> Unit)? = null,
) {
    val latestPosition by rememberUpdatedState(position)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestOnEnd by rememberUpdatedState(onEnd)
    val latestOnReset by rememberUpdatedState(onReset)
    Canvas(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = latestPosition
                    val width = (size.width - 16.dp.toPx()).coerceAtLeast(1f)
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    latestOnChange((start + (slop.position.x - down.position.x) / width).coerceIn(0f, 1f))
                    horizontalDrag(slop.id) { change ->
                        latestOnChange((start + (change.position.x - down.position.x) / width).coerceIn(0f, 1f))
                        change.consume()
                    }
                    latestOnEnd()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { latestOnReset?.invoke() })
            }
    ) {
        val r = 8.dp.toPx()
        val trackH = 4.dp.toPx()
        val x0 = r
        val x1 = size.width - r
        val cy = size.height / 2
        val thumbX = x0 + (x1 - x0) * position.coerceIn(0f, 1f)
        val corner = CornerRadius(trackH / 2)
        drawRoundRect(DawColors.Background, Offset(x0, cy - trackH / 2), Size(x1 - x0, trackH), corner)
        val fillFrom = x0 + (x1 - x0) * (origin ?: 0f)
        drawRoundRect(color, Offset(minOf(fillFrom, thumbX), cy - trackH / 2), Size(abs(thumbX - fillFrom), trackH), corner)
        if (origin != null) {
            drawLine(DawColors.TextSecondary, Offset(fillFrom, cy - 6.dp.toPx()), Offset(fillFrom, cy + 6.dp.toPx()), 1.dp.toPx())
        }
        drawCircle(Color.Black.copy(alpha = 0.35f), r + 1.dp.toPx(), Offset(thumbX, cy + 1.dp.toPx()))
        drawCircle(DawColors.TextPrimary, r, Offset(thumbX, cy))
    }
}

/**
 * A mixing-desk fader on the dB scale of [Scales] (-60..+6 dB) with tick
 * marks. Vertical drags are relative; a double tap calls [onReset].
 */
@Composable
fun VerticalFader(
    gain: Float,
    color: Color,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestGain by rememberUpdatedState(gain)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestOnEnd by rememberUpdatedState(onEnd)
    val latestOnReset by rememberUpdatedState(onReset)
    Canvas(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = Scales.gainToPosition(latestGain)
                    val travel = (size.height - FaderCap.toPx()).coerceAtLeast(1f)
                    val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    fun moveTo(y: Float) =
                        latestOnChange(Scales.positionToGain((start - (y - down.position.y) / travel).coerceIn(0f, 1f)))
                    moveTo(slop.position.y)
                    verticalDrag(slop.id) { change ->
                        moveTo(change.position.y)
                        change.consume()
                    }
                    latestOnEnd()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { latestOnReset() })
            }
    ) {
        val cap = FaderCap.toPx()
        val travel = size.height - cap
        val cx = size.width * 0.6f
        fun yOf(position: Float) = cap / 2 + (1 - position) * travel

        val paint = textPaint(9.dp.toPx(), android.graphics.Color.rgb(0x8E, 0x8E, 0x93), bold = false)
        for (db in FaderTicks) {
            val y = yOf((db - Scales.MIN_DB) / (Scales.MAX_DB - Scales.MIN_DB))
            drawLine(DawColors.GridBar, Offset(cx - 12.dp.toPx(), y), Offset(cx + 12.dp.toPx(), y), 1.dp.toPx())
            val label = if (db > 0) "+${db.toInt()}" else "${db.toInt()}"
            drawContext.canvas.nativeCanvas.drawText(label, 0f, y + 3.dp.toPx(), paint)
        }

        val grooveW = 4.dp.toPx()
        val y = yOf(Scales.gainToPosition(gain))
        drawRoundRect(Color.Black, Offset(cx - grooveW / 2, cap / 2), Size(grooveW, travel), CornerRadius(grooveW / 2))
        drawRoundRect(color, Offset(cx - grooveW / 2, y), Size(grooveW, cap / 2 + travel - y), CornerRadius(grooveW / 2))

        val capW = minOf(size.width * 0.55f, 30.dp.toPx())
        val capH = cap * 0.8f
        drawRoundRect(
            Color.Black.copy(alpha = 0.4f), Offset(cx - capW / 2, y - capH / 2 + 2.dp.toPx()),
            Size(capW, capH), CornerRadius(4.dp.toPx()),
        )
        drawRoundRect(Color(0xFFD1D1D6), Offset(cx - capW / 2, y - capH / 2), Size(capW, capH), CornerRadius(4.dp.toPx()))
        drawLine(Color.Black, Offset(cx - capW / 2 + 3.dp.toPx(), y), Offset(cx + capW / 2 - 3.dp.toPx(), y), 2.dp.toPx())
    }
}

/** A vertical peak meter that lines up with a [VerticalFader] of the same height. */
@Composable
fun VerticalMeter(level: Float, modifier: Modifier) {
    Canvas(modifier) {
        val inset = FaderCap.toPx() / 2
        drawMeter(level, Offset(0f, inset), Size(size.width, size.height - inset * 2), vertical = true)
    }
}

/** Peak meter on the faders' dB scale: green, yellow above -6 dB, red above 0 dB. */
fun DrawScope.drawMeter(level: Float, topLeft: Offset, area: Size, vertical: Boolean) {
    drawRect(Color.Black, topLeft, area)
    val p = Scales.gainToPosition(level)
    if (p <= 0f) return
    val length = if (vertical) area.height else area.width
    fun segment(from: Float, to: Float, color: Color) {
        if (to <= from) return
        if (vertical) {
            drawRect(color, Offset(topLeft.x, topLeft.y + (1 - to) * length), Size(area.width, (to - from) * length))
        } else {
            drawRect(color, Offset(topLeft.x + from * length, topLeft.y), Size((to - from) * length, area.height))
        }
    }
    segment(0f, minOf(p, YellowAt), DawColors.MeterGreen)
    segment(YellowAt, minOf(p, RedAt), DawColors.MeterYellow)
    segment(RedAt, p, DawColors.MeterRed)
}
