package com.povwheel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Ползунки «под металл»: дорожка — вдавленный в карточку жёлоб (тень сверху
 * внутрь, светлый кант снизу — свет падает сверху), в нём тонкая линия значения;
 * бегунок — выпуклый: градиент сверху вниз, мягкая тень под ним. У одиночного
 * ползунка бегунок круглый с цветной точкой в гнезде, у диапазона — две
 * «черточки» поперёк жёлоба с цветной полоской.
 *
 * Поведение общее и прежнее: бегунок трогается, только если палец опустился
 * прямо на него и повёл; касание мимо бегунка жест не перехватывает, и лента под
 * ним свободно скроллится. Тап по жёлобу (без протяжки) переставляет бегунок
 * туда. Хаптик-щелчок — на каждое пройденное деление.
 */

private val SLIDER_H = 36.dp
private val RANGE_H = 40.dp
private val GROOVE_H = 16.dp
private val LINE_W = 3.dp
private val THUMB_R = 11.dp
private val BAR_W = 10.dp
private val BAR_H = 26.dp
private val GRAB = 24.dp       // насколько близко к бегунку нужно попасть пальцем

/** Цвета рельефа: на светлой теме жёлоб светлее карточки, на тёмной — темнее. */
private class Relief(
    val grooveBase: Color,
    val grooveShadow: Color,
    val grooveHighlight: Color,
    val lineOff: Color,
    val thumbTop: Color,
    val thumbBottom: Color,
    val thumbRim: Color,
    val shadow: Color
)

@Composable
private fun relief(): Relief {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Relief(
        grooveBase = Color.Black.copy(alpha = 0.30f),
        grooveShadow = Color.Black.copy(alpha = 0.50f),
        grooveHighlight = Color.White.copy(alpha = 0.10f),
        lineOff = Color.White.copy(alpha = 0.13f),
        thumbTop = Color(0xFF767C85),
        thumbBottom = Color(0xFF454A51),
        thumbRim = Color.Black.copy(alpha = 0.45f),
        shadow = Color.Black.copy(alpha = 0.55f)
    ) else Relief(
        grooveBase = Color.White.copy(alpha = 0.60f),
        grooveShadow = Color.Black.copy(alpha = 0.16f),
        grooveHighlight = Color.White,
        lineOff = Color.Black.copy(alpha = 0.10f),
        thumbTop = Color.White,
        thumbBottom = Color(0xFFDADADA),
        thumbRim = Color.Black.copy(alpha = 0.14f),
        shadow = Color.Black.copy(alpha = 0.22f)
    )
}

/** Вдавленный жёлоб на всю ширину, высотой [h], верхний край на [top]. */
private fun DrawScope.drawGroove(r: Relief, top: Float, h: Float) {
    val cr = CornerRadius(h / 2f, h / 2f)
    val tl = Offset(0f, top)
    val sz = Size(size.width, h)
    drawRoundRect(r.grooveBase, tl, sz, cr)
    // Тень от верхней кромки внутрь.
    drawRoundRect(
        Brush.verticalGradient(0f to r.grooveShadow, 0.5f to Color.Transparent, startY = top, endY = top + h),
        tl, sz, cr
    )
    // Кромка: тёмная сверху, светлая снизу.
    val sw = 1.dp.toPx()
    drawRoundRect(
        Brush.verticalGradient(
            0f to r.grooveShadow, 0.5f to Color.Transparent, 1f to r.grooveHighlight,
            startY = top, endY = top + h
        ),
        Offset(sw / 2f, top + sw / 2f), Size(size.width - sw, h - sw),
        CornerRadius(h / 2f - sw / 2f, h / 2f - sw / 2f), style = Stroke(sw)
    )
}

/** Мягкая тень под выпуклой деталью: радиальное пятно чуть ниже центра. */
private fun DrawScope.drawSoftShadow(r: Relief, c: Offset, radius: Float) {
    val dy = 1.5.dp.toPx()
    val rr = radius + 3.dp.toPx()
    drawCircle(
        Brush.radialGradient(
            0f to r.shadow, (radius / rr) to r.shadow.copy(alpha = r.shadow.alpha * 0.6f), 1f to Color.Transparent,
            center = c + Offset(0f, dy), radius = rr
        ),
        rr, c + Offset(0f, dy)
    )
}

/** Круглый выпуклый бегунок с цветной точкой в неглубоком гнезде. */
private fun DrawScope.drawRoundThumb(r: Relief, c: Offset, dot: Color) {
    val rad = THUMB_R.toPx()
    drawSoftShadow(r, c, rad)
    drawCircle(Brush.verticalGradient(listOf(r.thumbTop, r.thumbBottom), startY = c.y - rad, endY = c.y + rad), rad, c)
    drawCircle(r.thumbRim, rad - 0.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    // Гнездо: вдавлено — тёмное сверху, светлое снизу.
    val d = rad * 0.42f
    val ring = d + 1.5.dp.toPx()
    drawCircle(
        Brush.verticalGradient(listOf(r.grooveShadow, r.grooveHighlight), startY = c.y - ring, endY = c.y + ring),
        ring, c
    )
    drawCircle(dot, d, c)
    drawCircle(Color.Black.copy(alpha = 0.18f), d - 0.4.dp.toPx(), c, style = Stroke(0.8.dp.toPx()))
    // Блик на точке.
    drawCircle(Color.White.copy(alpha = 0.55f), d * 0.32f, c + Offset(-d * 0.35f, -d * 0.35f))
}

/** Бегунок-черточка поперёк жёлоба: выпуклый «цилиндр» с цветной полоской. */
private fun DrawScope.drawBarThumb(r: Relief, cx: Float, cy: Float, accent: Color) {
    val w = BAR_W.toPx()
    val h = BAR_H.toPx()
    val tl = Offset(cx - w / 2f, cy - h / 2f)
    val cr = CornerRadius(w * 0.38f, w * 0.38f)
    // Тень.
    val sh = 1.5.dp.toPx()
    drawRoundRect(r.shadow.copy(alpha = r.shadow.alpha * 0.45f), tl + Offset(-1f, sh + 1f), Size(w + 2f, h + 1f), cr)
    drawRoundRect(r.shadow.copy(alpha = r.shadow.alpha * 0.6f), tl + Offset(0f, sh), Size(w, h), cr)
    // Тело: светлое посередине, темнее к краям — читается как выпуклость.
    drawRoundRect(
        Brush.horizontalGradient(
            0f to r.thumbBottom, 0.45f to r.thumbTop, 1f to r.thumbBottom,
            startX = tl.x, endX = tl.x + w
        ),
        tl, Size(w, h), cr
    )
    // Свет сверху: верх светлее, низ темнее.
    drawRoundRect(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.35f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.06f),
            startY = tl.y, endY = tl.y + h
        ),
        tl, Size(w, h), cr
    )
    drawRoundRect(r.thumbRim, tl + Offset(0.5f, 0.5f), Size(w - 1f, h - 1f), cr, style = Stroke(1.dp.toPx()))
    // Цветная полоска с бликом.
    val sw = 2.4.dp.toPx()
    val sh2 = h * 0.52f
    drawLine(accent, Offset(cx, cy - sh2 / 2f), Offset(cx, cy + sh2 / 2f), sw, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.5f), Offset(cx - sw * 0.15f, cy - sh2 / 2f + sw * 0.3f),
        Offset(cx - sw * 0.15f, cy - sh2 * 0.1f), sw * 0.35f, StrokeCap.Round)
}

/**
 * Одиночный ползунок. [divisions] — число делений (0 — плавно). [fill] — своя
 * заливка линии целиком (баланс белого — градиент), без «активной» части;
 * [dotColor] — цвет точки на бегунке (по умолчанию [color]).
 */
@Composable
internal fun EmbossedSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    divisions: Int,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    fill: Brush? = null,
    dotColor: Color? = null
) {
    val rel = relief()
    val density = LocalDensity.current
    val view = LocalView.current
    val valueS by rememberUpdatedState(value)
    val onChangeS by rememberUpdatedState(onChange)
    val onFinishedS by rememberUpdatedState(onFinished)
    val lo = range.start
    val span = (range.endInclusive - lo).takeIf { it > 0f } ?: 1f

    fun snap(v: Float): Float {
        val c = v.coerceIn(lo, range.endInclusive)
        if (divisions <= 0) return c
        return lo + span * ((c - lo) / span * divisions).roundToInt() / divisions
    }
    fun idx(v: Float): Int = if (divisions <= 0) -1 else ((v - lo) / span * divisions).roundToInt()

    BoxWithConstraints(
        modifier.fillMaxWidth().height(SLIDER_H).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(lo, range.endInclusive), range, maxOf(0, divisions - 1))
            if (enabled) setProgress { v -> onChangeS(snap(v)); onFinishedS(); true }
        }
    ) {
        val wPx = with(density) { maxWidth.toPx() }
        val inset = with(density) { THUMB_R.toPx() }
        val usable = (wPx - inset * 2f).coerceAtLeast(1f)
        val grab = with(density) { GRAB.toPx() }
        fun xOf(v: Float) = inset + usable * (v.coerceIn(lo, range.endInclusive) - lo) / span
        fun vOf(x: Float) = snap(lo + (x - inset) / usable * span)

        Canvas(
            Modifier.matchParentSize().pointerInput(usable, enabled, range, divisions) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Выключен — жест не трогаем вовсе: пусть скроллится лента.
                    if (!enabled) return@awaitEachGesture
                    val x0 = down.position.x
                    if (abs(x0 - xOf(valueS)) <= grab) {
                        down.consume()
                        var last = valueS
                        var moved = false
                        horizontalDrag(down.id) { ch ->
                            ch.consume()
                            val v = vOf(ch.position.x)
                            if (v != last) {
                                if (idx(v) != idx(last)) view.tickFeedback()
                                last = v
                                moved = true
                                onChangeS(v)
                            }
                        }
                        if (moved) onFinishedS()
                    } else {
                        // Мимо бегунка: скролл ленты (тогда событие съест она и
                        // ожидание вернёт null) или тап — бегунок прыгает в точку.
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        val slop = viewConfiguration.touchSlop
                        if (abs(up.position.x - x0) > slop || abs(up.position.y - down.position.y) > slop)
                            return@awaitEachGesture
                        up.consume()
                        val v = vOf(up.position.x)
                        if (v != valueS) {
                            view.tickFeedback()
                            onChangeS(v)
                            onFinishedS()
                        }
                    }
                }
            }
        ) {
            val gh = GROOVE_H.toPx()
            val cy = size.height / 2f
            drawGroove(rel, cy - gh / 2f, gh)
            val lx0 = gh / 2f
            val lx1 = size.width - gh / 2f
            val lw = LINE_W.toPx()
            val tx = xOf(value)
            val on = if (enabled) color else color.copy(alpha = 0.38f)
            if (fill != null) {
                drawLine(fill, Offset(lx0, cy), Offset(lx1, cy), lw, StrokeCap.Round)
            } else {
                drawLine(rel.lineOff, Offset(lx0, cy), Offset(lx1, cy), lw, StrokeCap.Round)
                drawLine(on, Offset(lx0, cy), Offset(tx.coerceAtLeast(lx0), cy), lw, StrokeCap.Round)
            }
            drawRoundThumb(rel, Offset(tx, cy), dotColor ?: on)
        }
    }
}

/**
 * Диапазон с двумя бегунками-черточками на целочисленной шкале [range].
 * Бегунки на одном делении: какой тянуть, решает направление первого движения
 * (влево — нижний, вправо — верхний). [marker] — необязательная метка текущего
 * значения на той же шкале (дробная), рисуется в жёлобе цветом [markerColor].
 */
@Composable
internal fun EmbossedRangeSlider(
    lo: Int,
    hi: Int,
    range: IntRange,
    onChange: (Int, Int) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    marker: Float? = null,
    markerColor: Color = Warn
) {
    val rel = relief()
    val density = LocalDensity.current
    val view = LocalView.current
    val loS by rememberUpdatedState(lo)
    val hiS by rememberUpdatedState(hi)
    val onChangeS by rememberUpdatedState(onChange)
    val onFinishedS by rememberUpdatedState(onFinished)
    val first = range.first
    val spanV = (range.last - first).coerceAtLeast(1)

    BoxWithConstraints(modifier.fillMaxWidth().height(RANGE_H)) {
        val wPx = with(density) { maxWidth.toPx() }
        // Центры черточек ходят между центрами скруглений жёлоба.
        val inset = with(density) { (GROOVE_H / 2).toPx() }
        val usable = (wPx - inset * 2f).coerceAtLeast(1f)
        val grab = with(density) { GRAB.toPx() }
        fun xOf(v: Float) = inset + usable * (v - first) / spanV
        fun vOf(x: Float) = (first + (x - inset) / usable * spanV).roundToInt().coerceIn(range.first, range.last)

        Canvas(
            Modifier.matchParentSize().pointerInput(usable, enabled, range) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled) return@awaitEachGesture
                    val x = down.position.x
                    val dLo = abs(x - xOf(loS.toFloat()))
                    val dHi = abs(x - xOf(hiS.toFloat()))
                    // Не по бегунку — выходим не трогая событие: пусть скроллится список.
                    if (dLo > grab && dHi > grab) return@awaitEachGesture
                    var movingLo: Boolean? = if (loS == hiS) null else dLo <= dHi
                    down.consume()
                    var moved = false
                    var lastV = if (movingLo == false) hiS else loS
                    horizontalDrag(down.id) { ch ->
                        ch.consume()
                        if (movingLo == null) {
                            val dx = ch.position.x - x
                            if (abs(dx) < 4f) return@horizontalDrag   // мало — ждём
                            movingLo = dx < 0f
                        }
                        moved = true
                        val v = vOf(ch.position.x)
                        if (v != lastV) { view.tickFeedback(); lastV = v }
                        if (movingLo == true) onChangeS(v.coerceAtMost(hiS), hiS)
                        else onChangeS(loS, v.coerceAtLeast(loS))
                    }
                    if (moved) onFinishedS()
                }
            }
        ) {
            val gh = GROOVE_H.toPx()
            val cy = size.height / 2f
            drawGroove(rel, cy - gh / 2f, gh)
            val lw = LINE_W.toPx()
            val loX = xOf(lo.toFloat())
            val hiX = xOf(hi.toFloat())
            val on = if (enabled) color else color.copy(alpha = 0.38f)
            drawLine(rel.lineOff, Offset(inset, cy), Offset(size.width - inset, cy), lw, StrokeCap.Round)
            drawLine(on, Offset(loX, cy), Offset(hiX, cy), lw, StrokeCap.Round)
            // Деления — точки на линии, по одной на каждое положение шкалы.
            val tickR = 0.9.dp.toPx()
            for (v in first..range.last) {
                val tx = xOf(v.toFloat())
                drawCircle(
                    if (tx in loX..hiX) Color.White.copy(alpha = 0.6f) else rel.lineOff.copy(alpha = rel.lineOff.alpha * 2.5f),
                    tickR, Offset(tx, cy)
                )
            }
            // Текущее значение — тонкая метка во всю высоту жёлоба.
            if (marker != null) {
                val mx = xOf(marker.coerceIn(first.toFloat(), range.last.toFloat()))
                drawLine(markerColor, Offset(mx, cy - gh / 2f + 2.dp.toPx()), Offset(mx, cy + gh / 2f - 2.dp.toPx()),
                    3.dp.toPx(), StrokeCap.Round)
            }
            drawBarThumb(rel, loX, cy, on)
            drawBarThumb(rel, hiX, cy, on)
        }
    }
}
