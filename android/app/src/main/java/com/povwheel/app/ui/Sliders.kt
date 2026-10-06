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
import androidx.compose.ui.graphics.lerp
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
 * Ползунки: тонкий утопленный жёлоб (лёгкая тень по верхней кромке внутрь),
 * цветная заливка в нём, и белый выпуклый бегунок с мягкой тенью — у одиночного
 * круглый с цветной точкой, у диапазона две узкие черточки с цветной риской.
 * Рельеф намечен, а не нарисован: тени и обводки — в несколько процентов
 * прозрачности.
 *
 * Поведение: бегунок трогается, только если палец опустился прямо на него и
 * повёл; касание мимо бегунка жест не перехватывает, и лента под ним свободно
 * скроллится. Тап по жёлобу (без протяжки) переставляет бегунок туда.
 * Хаптик-щелчок — на каждое пройденное деление.
 */

private val SLIDER_H = 32.dp
private val RANGE_H = 34.dp
private val TRACK_H = 6.dp
private val THUMB_R = 10.dp
private val DOT_R = 3.5.dp
private val BAR_W = 6.dp
private val BAR_H = 20.dp
private val GRAB = 24.dp       // насколько близко к бегунку нужно попасть пальцем

/** Цвета рельефа: жёлоб — полупрозрачный поверх карточки, бегунок светлый всегда. */
private class Relief(
    val track: Color,
    val trackShadow: Color,
    val trackLight: Color,
    val thumbTop: Color,
    val thumbBottom: Color,
    val thumbRim: Color,
    val shadow: Color
)

@Composable
private fun relief(): Relief {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Relief(
        track = Color.White.copy(alpha = 0.10f),
        trackShadow = Color.Black.copy(alpha = 0.35f),
        trackLight = Color.Transparent,
        thumbTop = Color(0xFFF0F0F0),
        thumbBottom = Color(0xFFD8D8D8),
        thumbRim = Color.Black.copy(alpha = 0.30f),
        shadow = Color.Black.copy(alpha = 0.45f)
    ) else Relief(
        track = Color.Black.copy(alpha = 0.07f),
        trackShadow = Color.Black.copy(alpha = 0.10f),
        trackLight = Color.White.copy(alpha = 0.8f),
        thumbTop = Color.White,
        thumbBottom = Color(0xFFF1F1F1),
        thumbRim = Color.Black.copy(alpha = 0.09f),
        shadow = Color.Black.copy(alpha = 0.16f)
    )
}

/** Жёлоб от [x0] до [x1] по центру [cy]: основа, тень от верхней кромки и
 *  светлый волосок под нижней — свет падает сверху. */
private fun DrawScope.drawTrack(r: Relief, x0: Float, x1: Float, cy: Float) {
    val h = TRACK_H.toPx()
    val cr = CornerRadius(h / 2f, h / 2f)
    val tl = Offset(x0, cy - h / 2f)
    val sz = Size(x1 - x0, h)
    drawRoundRect(r.track, tl, sz, cr)
    drawRoundRect(
        Brush.verticalGradient(0f to r.trackShadow, 0.6f to Color.Transparent, startY = tl.y, endY = tl.y + h),
        tl, sz, cr
    )
    val hair = 0.8.dp.toPx()
    drawLine(r.trackLight, Offset(x0 + h / 2f, tl.y + h + hair / 2f), Offset(x1 - h / 2f, tl.y + h + hair / 2f), hair)
}

/** Цветная заливка жёлоба от [x0] до [x1]: чуть светлее сверху. */
private fun DrawScope.drawFill(color: Color, x0: Float, x1: Float, cy: Float) {
    val h = TRACK_H.toPx()
    if (x1 - x0 < h) { drawCircle(color, h / 2f, Offset(x0, cy)); return }
    drawRoundRect(
        Brush.verticalGradient(listOf(lerp(color, Color.White, 0.22f), color), startY = cy - h / 2f, endY = cy + h / 2f),
        Offset(x0, cy - h / 2f), Size(x1 - x0, h), CornerRadius(h / 2f, h / 2f)
    )
}

/** Мягкая тень под выпуклой деталью: радиальное пятно чуть ниже центра. */
private fun DrawScope.drawSoftShadow(r: Relief, c: Offset, radius: Float) {
    val dy = 1.dp.toPx()
    val rr = radius + 2.5.dp.toPx()
    drawCircle(
        Brush.radialGradient(
            0f to r.shadow, (radius / rr) to r.shadow.copy(alpha = r.shadow.alpha * 0.5f), 1f to Color.Transparent,
            center = c + Offset(0f, dy), radius = rr
        ),
        rr, c + Offset(0f, dy)
    )
}

/** Круглый бегунок с цветной точкой. */
private fun DrawScope.drawRoundThumb(r: Relief, c: Offset, dot: Color) {
    val rad = THUMB_R.toPx()
    drawSoftShadow(r, c, rad)
    drawCircle(Brush.verticalGradient(listOf(r.thumbTop, r.thumbBottom), startY = c.y - rad, endY = c.y + rad), rad, c)
    drawCircle(r.thumbRim, rad - 0.4.dp.toPx(), c, style = Stroke(0.8.dp.toPx()))
    drawCircle(dot, DOT_R.toPx(), c)
}

/** Бегунок-черточка поперёк жёлоба с цветной риской. */
private fun DrawScope.drawBarThumb(r: Relief, cx: Float, cy: Float, accent: Color) {
    val w = BAR_W.toPx()
    val h = BAR_H.toPx()
    val tl = Offset(cx - w / 2f, cy - h / 2f)
    val cr = CornerRadius(w / 2f, w / 2f)
    // Тень: два слоя, второй шире и прозрачнее — мягкий край без размытия.
    val dy = 1.dp.toPx()
    drawRoundRect(r.shadow.copy(alpha = r.shadow.alpha * 0.45f), tl + Offset(-1.2f, dy), Size(w + 2.4f, h + 1.5f), cr)
    drawRoundRect(r.shadow.copy(alpha = r.shadow.alpha * 0.6f), tl + Offset(0f, dy), Size(w, h), cr)
    drawRoundRect(Brush.verticalGradient(listOf(r.thumbTop, r.thumbBottom), startY = tl.y, endY = tl.y + h), tl, Size(w, h), cr)
    drawRoundRect(r.thumbRim, tl + Offset(0.4f, 0.4f), Size(w - 0.8f, h - 0.8f), cr, style = Stroke(0.8.dp.toPx()))
    val sh = h * 0.5f
    drawLine(accent, Offset(cx, cy - sh / 2f), Offset(cx, cy + sh / 2f), 1.8.dp.toPx(), StrokeCap.Round)
}

/**
 * Одиночный ползунок. [divisions] — число делений (0 — плавно). [fill] — своя
 * заливка жёлоба целиком (баланс белого — градиент), без «активной» части;
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
            val cy = size.height / 2f
            val x0 = inset - TRACK_H.toPx() / 2f
            val x1 = size.width - inset + TRACK_H.toPx() / 2f
            val tx = xOf(value)
            val on = if (enabled) color else color.copy(alpha = 0.38f)
            drawTrack(rel, x0, x1, cy)
            if (fill != null) {
                val h = TRACK_H.toPx()
                drawRoundRect(fill, Offset(x0, cy - h / 2f), Size(x1 - x0, h), CornerRadius(h / 2f, h / 2f))
            } else {
                drawFill(on, x0, tx, cy)
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
        // Черточки ходят между центрами скруглений жёлоба.
        val inset = with(density) { (BAR_W / 2 + 1.dp).toPx() }
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
            val cy = size.height / 2f
            val loX = xOf(lo.toFloat())
            val hiX = xOf(hi.toFloat())
            val on = if (enabled) color else color.copy(alpha = 0.38f)
            drawTrack(rel, 0f, size.width, cy)
            drawFill(on, loX, hiX, cy)
            // Текущее значение — метка поперёк жёлоба. Толстая и заметно выше
            // жёлоба, с тёмной каймой: тонкая риска цвета, близкого к заливке,
            // почти терялась на ней.
            if (marker != null) {
                val mx = xOf(marker.coerceIn(first.toFloat(), range.last.toFloat()))
                val half = TRACK_H.toPx() / 2f + 5.dp.toPx()
                val w = 4.5.dp.toPx()
                drawLine(rel.shadow.copy(alpha = 0.55f), Offset(mx, cy - half), Offset(mx, cy + half),
                    w + 2.dp.toPx(), StrokeCap.Round)
                drawLine(markerColor, Offset(mx, cy - half), Offset(mx, cy + half), w, StrokeCap.Round)
            }
            drawBarThumb(rel, loX, cy, on)
            drawBarThumb(rel, hiX, cy, on)
        }
    }
}
