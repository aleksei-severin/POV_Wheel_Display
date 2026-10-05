package com.povwheel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.povwheel.app.TEXT_EFFECT_ID
import com.povwheel.app.WheelVm
import com.povwheel.app.convert.TextMask
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Круглые превью процедурных эффектов для плитки библиотеки. Рисуются на телефоне
 * (эффект генерится на колесе, файла-источника нет) — декоративные Canvas-анимации,
 * каждая передаёт суть эффекта. `id` — `EffectId` из include/effects.h, 1..7.
 * Исключение — «Текст» (7): его миниатюра — та же маска, что на колесе.
 */
internal val EFFECT_IDS = 1..TEXT_EFFECT_ID

/** Короткое имя эффекта (для тоста). */
internal fun effectName(id: Int): String = when (id) {
    1 -> "Speed"; 2 -> "Fire"; 3 -> "Rainbow"
    4 -> "Testing"; 5 -> "Ripples"; 6 -> "Clock"; TEXT_EFFECT_ID -> "Text"; else -> "Effect"
}

/** [text] — текст колеса, нужен только эффекту «Текст». */
@Composable
internal fun EffectPreview(id: Int, modifier: Modifier = Modifier, text: WheelVm.TextFx? = null) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (id) {
            1 -> SpeedPreview()
            2 -> FirePreview()
            3 -> RainbowPreview()
            4 -> TestingPreview()
            5 -> RipplePreview()
            6 -> ClockPreview()
            TEXT_EFFECT_ID -> TextDisc(text, 160, Modifier.fillMaxSize())
        }
    }
}

// Цветовой круг в порядке тона на колесе (hsv2rgb в effects.cpp): красный →
// жёлтый → зелёный → голубой → синий → пурпурный → красный.
internal val RAINBOW_COLORS = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF),
    Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000)
)

/**
 * Текст колеса — та же маска, которую красит колесо, и тем же цветом: одним
 * или радугой, которая течёт по углу с той же скоростью (на колесе: тон от
 * сектора плюс сдвиг во времени, speed 100 — два оборота круга в секунду).
 * Текст ещё не задан — подпись-заглушка. [px] — сторона растра маски.
 */
@Composable
internal fun TextDisc(fx: WheelVm.TextFx?, px: Int, modifier: Modifier = Modifier) {
    val mask = fx?.mask
    val bmp = remember(mask, px) { mask?.let { TextMask.disc(it, px).asImageBitmap() } }
    if (fx == null || bmp == null || fx.text.isBlank()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Aa", color = Color(0xFFECECEC), fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
        }
        return
    }
    val st = fx.style
    if (!st.rainbow) {
        Image(bmp, null, modifier, colorFilter = ColorFilter.tint(Color(0xFF000000.toInt() or st.rgb)))
        return
    }
    // Сдвиг радуги: 0.0072° на мс на единицу скорости = 720°/с при speed 100.
    var deg by remember { mutableStateOf(0f) }
    LaunchedEffect(st.speed) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) deg = (deg + (now - last) / 1_000_000f * st.speed * 0.0072f) % 360f
                last = now
            }
        }
    }
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        drawImage(bmp, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
        // На колесе тон растёт с номером сектора — по часовой стрелке от «3 часов»,
        // как и у sweepGradient; растущий сдвиг уводит картину против часовой.
        rotate(-deg) { drawRect(Brush.sweepGradient(RAINBOW_COLORS), blendMode = BlendMode.SrcIn) }
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
    // Ячейка уже отступила 3 dp под серое поле — вместе это прежние 6 dp.
    Canvas(Modifier.fillMaxSize().padding(3.dp)) {
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
    // Как на колесе: число над центром, «km/h» под ним, оба в стороне от
    // отверстия под ступицу (его рисует ячейка поверх превью).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = maxWidth * HUB_HOLE_FRAC + 1.dp
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.TopCenter).padding(bottom = gap),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(v.toString(), color = col, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                lineHeight = 18.sp, maxLines = 1)
        }
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.BottomCenter).padding(top = gap),
            contentAlignment = Alignment.TopCenter
        ) {
            Text("km/h", color = col.copy(alpha = 0.8f), fontSize = 7.sp, lineHeight = 7.sp, maxLines = 1)
        }
    }
}
