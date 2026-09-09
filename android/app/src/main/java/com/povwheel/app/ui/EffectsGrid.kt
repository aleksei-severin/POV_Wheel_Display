package com.povwheel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.povwheel.app.WheelVm
import com.povwheel.app.ble.Tele
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** id 0 — «выключить», дальше — EffectId из include/effects.h (веб шлёт то же число). */
private data class Eff(val id: Int, val name: String, val desc: String)

private val EFFECTS = listOf(
    Eff(0, "Off", "Stop the effect. The wheel goes dark until you play a file or pick another effect."),
    Eff(1, "Speed", "Current speed in km/h — green at a crawl, fully red from 45 km/h up."),
    Eff(2, "Fire", "Flames rise from the hub and flicker out at the rim."),
    Eff(3, "Rainbow", "A spectrum spiral turning against the wheel."),
    Eff(4, "Testing", "Diagnostic cross through the hub — blue for 5 s, then each arm in its own colour, to spot a per-arm angle offset by eye."),
    Eff(5, "Ripples", "Concentric waves from the centre. Stands perfectly still."),
    Eff(6, "Clock", "Numbered dial and three hands, set from your phone.")
)

// Четыре в ряд, а не пять как в библиотеке: превью эффекта — это мелкая
// анимация, и её надо разглядеть; семь плиток тогда ложатся 4 + 3.
private const val COLS = 4

@Composable
internal fun EffectsTab(vm: WheelVm, tele: Tele) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Text("Effects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Tap one to run it. Stop from the DISPLAY card, the Off tile, or by playing a file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        EFFECTS.chunked(COLS).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { e ->
                    // Зелёный ободок — что реально на ободе сейчас. Плитка Off
                    // светится, только когда показ действительно остановлен, а не
                    // когда просто играет файл (там tele.effect тоже 0).
                    val active = if (e.id == 0) tele.effect == 0 && !tele.play
                                 else tele.effect == e.id
                    EffectTile(e = e, active = active, modifier = Modifier.weight(1f)) {
                        vm.effect(e.id); vm.say(e.name)
                    }
                }
                repeat(COLS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        val activeEff = EFFECTS.firstOrNull { it.id == tele.effect && it.id != 0 }
        if (activeEff != null) {
            Spacer(Modifier.height(4.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(activeEff.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        activeEff.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EffectTile(e: Eff, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(if (e.id == 0) cs.surfaceVariant else Color.Black)
                .then(if (active) Modifier.border(2.dp, Ok, CircleShape) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            when (e.id) {
                0 -> OffPreview()
                1 -> SpeedPreview()
                2 -> FirePreview()
                3 -> RainbowPreview()
                4 -> TestingPreview()
                5 -> RipplePreview()
                6 -> ClockPreview()
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            e.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) Ok else cs.onSurface
        )
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

// ---- Превью эффектов (декоративные, не с колеса) ----

@Composable
private fun OffPreview() {
    val col = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxSize().padding(11.dp)) {
        val w = 2.5.dp.toPx()
        drawArc(col, -55f, 290f, false, style = Stroke(w, cap = StrokeCap.Round))
        drawLine(col, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height * 0.45f),
            w, cap = StrokeCap.Round)
    }
}

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
        Text(v.toString(), color = col, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1)
        Text("km/h", color = col.copy(alpha = 0.8f), fontSize = 8.sp, maxLines = 1)
    }
}
