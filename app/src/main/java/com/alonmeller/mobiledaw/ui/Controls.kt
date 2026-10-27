package com.alonmeller.mobiledaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alonmeller.mobiledaw.model.ParamSpec
import com.alonmeller.mobiledaw.model.Scales
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Height of the fader cap; meters next to a fader are inset by half of it so their scales line up. */
val FaderCap = 30.dp

private val FaderTicks = listOf(6f, 0f, -6f, -12f, -24f, -40f)
/** The order fader ticks get their labels in, when a short fader has room for only some. */
private val FaderLabelOrder = listOf(0f, -12f, -40f, 6f, -24f, -6f)
private val YellowAt = Scales.gainToPosition(0.5f) // -6 dB
private val RedAt = Scales.gainToPosition(1f) // 0 dB

/**
 * Compact horizontal slider over positions 0..1, drawn by hand so it fits
 * tight rows. A drag moves the value relative to where it was, so touching
 * the slider never makes it jump, and a double tap calls [onReset], like a
 * double-click in REAPER. With [origin] set, the fill starts there (pan).
 * With [level] set, a level meter runs under the track, as on GarageBand's
 * track volume sliders.
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
    level: Float? = null,
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
        val cy = if (level != null) size.height / 2 - 2.dp.toPx() else size.height / 2
        val thumbX = x0 + (x1 - x0) * position.coerceIn(0f, 1f)
        val corner = CornerRadius(trackH / 2)
        drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(x0, cy - trackH / 2), Size(x1 - x0, trackH), corner)
        val fillFrom = x0 + (x1 - x0) * (origin ?: 0f)
        drawRoundRect(color, Offset(minOf(fillFrom, thumbX), cy - trackH / 2), Size(abs(thumbX - fillFrom), trackH), corner)
        if (origin != null) {
            drawLine(DawColors.TextSecondary, Offset(fillFrom, cy - 6.dp.toPx()), Offset(fillFrom, cy + 6.dp.toPx()), 1.dp.toPx())
        }
        if (level != null) {
            drawMeter(level, Offset(x0, cy + trackH / 2 + 3.dp.toPx()), Size(x1 - x0, 3.dp.toPx()), vertical = false)
        }
        // A white knob with a soft shadow, like an iOS slider's.
        drawCircle(Color.Black.copy(alpha = 0.35f), r + 1.dp.toPx(), Offset(thumbX, cy + 1.dp.toPx()))
        drawCircle(Color.White, r, Offset(thumbX, cy))
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
        fun tickY(db: Float) = yOf((db - Scales.MIN_DB) / (Scales.MAX_DB - Scales.MIN_DB))
        for (db in FaderTicks) {
            drawLine(DawColors.GridBar, Offset(cx - 12.dp.toPx(), tickY(db)), Offset(cx + 12.dp.toPx(), tickY(db)), 1.dp.toPx())
        }
        // On a short fader the labels would run into each other: 0 dB first, then those with room.
        val labeled = mutableListOf<Float>()
        for (db in FaderLabelOrder) {
            val y = tickY(db)
            if (labeled.any { abs(it - y) < 11.dp.toPx() }) continue
            labeled += y
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
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFFF2F2F7), Color(0xFFAEAEB2)), startY = y - capH / 2, endY = y + capH / 2),
            Offset(cx - capW / 2, y - capH / 2), Size(capW, capH), CornerRadius(4.dp.toPx()),
        )
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

// ---------------------------------------------------------------- Knobs

/** A knob turns from 7:30 round to 4:30, the angles of its arc (0 is 3 o'clock, clockwise). */
private const val KNOB_START = 135f
private const val KNOB_SWEEP = 270f

/**
 * A round knob, as on GarageBand's instruments and effects. Dragging up or
 * to the right turns it up, relative to where it was, so touching it never
 * makes it jump; a double tap calls [onReset]. Its arc lights up from
 * [origin] to the value: the middle for pan or a boost or cut, else the start.
 */
@Composable
fun Knob(
    position: Float,
    color: Color,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    origin: Float = 0f,
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
                    // The whole range is a drag of about 160dp, whatever the knob's size.
                    val range = 160.dp.toPx()
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    fun moveTo(p: Offset) {
                        val turned = (p.x - down.position.x) - (p.y - down.position.y)
                        latestOnChange((start + turned / range).coerceIn(0f, 1f))
                    }
                    moveTo(slop.position)
                    drag(slop.id) { change ->
                        moveTo(change.position)
                        change.consume()
                    }
                    latestOnEnd()
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { latestOnReset?.invoke() })
            }
    ) { drawKnob(position.coerceIn(0f, 1f), color, origin) }
}

private fun DrawScope.drawKnob(position: Float, color: Color, origin: Float) {
    val d = minOf(size.width, size.height)
    val c = center
    val ring = d * 0.09f
    val arcR = d / 2 - ring / 2
    val arcTopLeft = Offset(c.x - arcR, c.y - arcR)
    val arcSize = Size(arcR * 2, arcR * 2)
    val arc = Stroke(ring, cap = StrokeCap.Round)
    drawArc(Color.Black.copy(alpha = 0.6f), KNOB_START, KNOB_SWEEP, false, arcTopLeft, arcSize, style = arc)
    val from = KNOB_START + KNOB_SWEEP * origin
    val to = KNOB_START + KNOB_SWEEP * position
    if (abs(to - from) > 0.5f) drawArc(color, minOf(from, to), abs(to - from), false, arcTopLeft, arcSize, style = arc)

    // The cap, lit from above, with a darker face inside its rim.
    val capR = d / 2 - ring * 2f
    drawCircle(Color.Black.copy(alpha = 0.45f), capR + 1.dp.toPx(), c + Offset(0f, 1.5.dp.toPx()))
    drawCircle(
        Brush.verticalGradient(listOf(Color(0xFF77777D), Color(0xFF2A2A2E)), startY = c.y - capR, endY = c.y + capR),
        capR, c,
    )
    drawCircle(
        Brush.verticalGradient(listOf(Color(0xFF4C4C52), Color(0xFF36363A)), startY = c.y - capR, endY = c.y + capR),
        capR * 0.84f, c,
    )
    val angle = Math.toRadians(to.toDouble())
    val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
    drawLine(Color.White, c + dir * (capR * 0.3f), c + dir * (capR * 0.84f), strokeWidth = d * 0.06f, cap = StrokeCap.Round)
}

/** A [Knob] with its name above it and its value under it, as GarageBand labels its knobs. */
@Composable
fun LabeledKnob(
    label: String,
    value: String,
    position: Float,
    color: Color,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    origin: Float = 0f,
    knobSize: Dp = 40.dp,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = DawColors.TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Knob(position, color, onChange, onEnd, Modifier.size(knobSize), origin, onReset)
        Spacer(Modifier.height(1.dp))
        Text(value, color = DawColors.TextPrimary, fontSize = 10.sp, maxLines = 1)
    }
}

/**
 * A knob for a parameter of an instrument or effect, on the parameter's own
 * scale; one that cuts and boosts lights up from 0.
 */
@Composable
fun ParamKnob(
    spec: ParamSpec,
    value: Float,
    color: Color,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    knobSize: Dp = 40.dp,
) {
    LabeledKnob(
        spec.name, spec.format(value), spec.toPosition(value), color,
        onChange = { onChange(spec.fromPosition(it)) },
        onEnd = onEnd,
        onReset = onReset,
        modifier = modifier,
        origin = if (spec.min < 0f && spec.max > 0f) spec.toPosition(0f) else 0f,
        knobSize = knobSize,
    )
}

// ---------------------------------------------------------------- GarageBand-style buttons

private val ToolShape = RoundedCornerShape(8.dp)

/**
 * A raised gray button, as in GarageBand's control bar. When [active] its
 * face takes a tint of [activeColor]; the caller colors the glyph to match.
 */
@Composable
fun ToolButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColor: Color = DawColors.Accent,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(ToolShape)
            .background(DawColors.button)
            .background(if (active) activeColor.copy(alpha = 0.24f) else Color.Transparent)
            .border(1.dp, DawColors.Separator, ToolShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Buttons joined into one raised bar, like GarageBand's transport; fill it with [ToolSegment]s. */
@Composable
fun ToolGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .clip(ToolShape)
            .background(DawColors.button)
            .border(1.dp, DawColors.Separator, ToolShape),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One button of a [ToolGroup]; every one but the first has a hairline before it. */
@Composable
fun RowScope.ToolSegment(
    width: Dp,
    onClick: () -> Unit,
    active: Boolean = false,
    activeColor: Color = DawColors.Accent,
    first: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (!first) Box(Modifier.width(1.dp).fillMaxHeight().background(DawColors.Separator))
    Box(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(if (active) activeColor.copy(alpha = 0.24f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The glyph color of a toolbar button: its own color when on, white otherwise. */
fun toolColor(on: Boolean, onColor: Color) = if (on) onColor else DawColors.TextPrimary

/**
 * A small square toggle with a glyph, as GarageBand's track buttons:
 * mute (a speaker, blue), solo (headphones, yellow), record (a red dot).
 */
@Composable
fun IconToggle(
    glyph: Glyph,
    on: Boolean,
    onColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    offColor: Color = DawColors.TextSecondary,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (on) Brush.verticalGradient(listOf(DawColors.tint(onColor, 0.15f), onColor)) else DawColors.button)
            .border(1.dp, DawColors.Separator, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (on) textOn(onColor) else offColor, size = size * 0.66f)
    }
}

/**
 * A track's instrument picture, like the icons in GarageBand's track
 * headers: a glossy tile in the track's color with [glyph] on it.
 */
@Composable
fun InstrumentTile(glyph: Glyph, color: Color, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(DawColors.tint(color, 0.18f), DawColors.shade(color, 0.32f))))
            .drawWithContent {
                drawContent()
                // The gloss on the top half.
                drawRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), endY = this.size.height * 0.55f),
                    size = Size(this.size.width, this.size.height * 0.55f),
                )
            }
            .border(1.dp, Color.Black.copy(alpha = 0.5f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, Color.White, size = size * 0.6f)
    }
}

/** A group of commands in a black bubble, like the edit menu GarageBand shows over a region. */
@Composable
fun MenuGroup(content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .fillMaxHeight()
            .clip(shape)
            .background(DawColors.Menu)
            .border(1.dp, Color(0xFF2C2C30), shape),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One command of a [MenuGroup]: white text, blue when [active], gray when not [enabled]. */
@Composable
fun RowScope.MenuAction(
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    first: Boolean = false,
    bold: Boolean = false,
    onClick: (() -> Unit)?,
) {
    if (!first) Box(Modifier.padding(vertical = 8.dp).width(1.dp).fillMaxHeight().background(Color(0xFF3A3A3E)))
    Box(
        Modifier
            .fillMaxHeight()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> DawColors.TextSecondary.copy(alpha = 0.45f)
                active -> DawColors.Accent
                else -> DawColors.TextPrimary
            },
            fontSize = 13.sp,
            fontWeight = if (bold || active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** A short message floating over the screen, like an iOS banner. */
@Composable
fun Toast(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0xF0101012))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(text, color = DawColors.TextPrimary, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
    }
}
