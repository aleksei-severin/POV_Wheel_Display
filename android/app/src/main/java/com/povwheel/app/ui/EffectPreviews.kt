package com.povwheel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Круглые превью процедурных эффектов для плитки библиотеки. Рисуются на телефоне
 * (эффект генерится на колесе, файла-источника нет) — декоративные Canvas-анимации,
 * каждая передаёт суть эффекта. `id` — `EffectId` из include/effects.h, 1..6.
 */
internal val EFFECT_IDS = 1..6

/** Короткое имя эффекта (для тоста). */
internal fun effectName(id: Int): String = when (id) {
    1 -> "Speed"; 2 -> "Fire"; 3 -> "Rainbow"
    4 -> "Testing"; 5 -> "Ripples"; 6 -> "Clock"; else -> "Effect"
}

@Composable
internal fun EffectPreview(id: Int, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (id) {
            1 -> SpeedPreview()
            2 -> FirePreview()
            3 -> RainbowPreview()
            4 -> TestingPreview()
            5 -> RipplePreview()
            6 -> ClockPreview()
        }
    }
}

// ---- Анимация без зависимости от animation-core: фазовые часы на withFrameNanos ----

/** Линейная фаза 0→1, зацикленная каждые [periodMs]. */
@Composable
private fun phase(periodMs: Int): Float {
    var p by remember { mutableStateOf(0f) }
    LaunchedEffect(periodMs) {
        var t0 = 0L
        while (true) {
            withFrameNanos { now ->
                if (t0 == 0L) t0 = now
                val ms = (now - t0) / 1_000_000f
                p = (ms % periodMs) / periodMs
            }
        }
    }
    return p
}

/** Треугольная волна 0→1→0 из линейной фазы. */
private fun pingPong(p: Float) = abs(p * 2f - 1f)

// ---- Сами превью ----

@Composable
private fun RainbowPreview() {
    val a = phase(3600) * 360f
    val colors = listOf(
        Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759),
        Color(0xFF32ADE6), Color(0xFF5856D6), Color(0xFFAF52DE), Color(0xFFFF3B30)
    )
    Canvas(Modifier.fillMaxSize()) {
        rotate(a) { drawCircle(Brush.sweepGradient(colors)) }
    }
}

@Composable
private fun FirePreview() {
    val f = pingPong(phase(950))
    val g = pingPong(phase(1330))
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2f, size.height * (0.62f - 0.06f * f))
        drawCircle(
            Brush.radialGradient(
                0f to Color.White,
                0.22f to Color(0xFFFFE082),
                0.5f to Color(0xFFFF7043),
                0.8f to Color(0xFF7F1000),
                1f to Color.Black,
                center = c,
                radius = size.minDimension / 2f * (0.72f + 0.28f * g)
            )
        )
    }
}

@Composable
private fun RipplePreview() {
    val p = phase(2600)
    Canvas(Modifier.fillMaxSize()) {
        val maxR = size.minDimension / 2f
        for (k in 0 until 4) {
            val rp = (p + k * 0.25f) % 1f
            drawCircle(
                color = Color(0xFF32ADE6).copy(alpha = (1f - rp) * 0.85f),
                radius = rp * maxR,
                style = Stroke(2f.dp.toPx())
            )
        }
    }
}

@Composable
private fun ClockPreview() {
    val sec = phase(4200) * 360f
    val white = Color(0xFFECECEC)
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f * 0.92f
        for (i in 0 until 12) {
            val ang = i * PI / 6.0
            val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
            drawLine(white, Offset(c.x + ca * r * 0.82f, c.y + sa * r * 0.82f),
                Offset(c.x + ca * r * 0.96f, c.y + sa * r * 0.96f), 1.4f.dp.toPx())
        }
        fun hand(deg: Float, len: Float, wDp: Float, col: Color) {
            val ang = (deg - 90f) * PI.toFloat() / 180f
            drawLine(col, c, Offset(c.x + cos(ang) * r * len, c.y + sin(ang) * r * len),
                wDp.dp.toPx(), cap = StrokeCap.Round)
        }
        hand(300f, 0.42f, 2.6f, white)
        hand(70f, 0.62f, 2.2f, white)
        hand(sec, 0.78f, 1.3f, Color(0xFFFF3B30))
    }
}

@Composable
private fun TestingPreview() {
    // 2 c — крест синий целиком, 2 c — по секторам своими цветами (модель эффекта).
    val split = phase(4000) > 0.5f
    val armCols = listOf(
        Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759),
        Color(0xFF32ADE6), Color(0xFF5856D6), Color(0xFFAF52DE)
    )
    Canvas(Modifier.fillMaxSize().padding(6.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        val w = 2.6f.dp.toPx()
        if (!split) {
            drawLine(Color(0xFF32ADE6), Offset(c.x - r, c.y), Offset(c.x + r, c.y), w, cap = StrokeCap.Round)
            drawLine(Color(0xFF32ADE6), Offset(c.x, c.y - r), Offset(c.x, c.y + r), w, cap = StrokeCap.Round)
        } else {
            for (k in 0 until 6) {
                val ang = k * PI.toFloat() / 3f
                drawLine(armCols[k], c, Offset(c.x + cos(ang) * r, c.y + sin(ang) * r), w, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun SpeedPreview() {
    val v = (6f + pingPong(phase(3200)) * 42f).roundToInt()
    val col = lerp(Color(0xFF22C55E), Color(0xFFEF4444), ((v - 6f) / 42f).coerceIn(0f, 1f))
    Column(
        Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(v.toString(), color = col, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
        Text("km/h", color = col.copy(alpha = 0.8f), fontSize = 7.sp, maxLines = 1)
    }
}
