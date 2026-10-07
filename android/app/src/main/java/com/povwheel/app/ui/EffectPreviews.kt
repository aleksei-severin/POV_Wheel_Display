package com.povwheel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.povwheel.app.TEXT_EFFECT_ID
import com.povwheel.app.WheelVm
import com.povwheel.app.ble.FxParams
import com.povwheel.app.ble.TextStyle
import com.povwheel.app.convert.FxMask
import com.povwheel.app.convert.TextMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * Круглые превью процедурных эффектов для плитки библиотеки. Эффект генерится на
 * колесе, файла-источника нет — поэтому Speed, Clock, Rainbow и Text рисуются
 * тем же алгоритмом и с теми же настройками, что на ободе (см. convert/FxMask).
 * `id` — `EffectId` из include/effects.h.
 */
internal const val EFF_SPEED = 1
internal const val EFF_RAINBOW = 3
internal const val EFF_CLOCK = 6

/** Эффекты в хвосте плитки. Номера 2 (Fire), 4 (Testing) и 5 (Ripples) удалены из прошивки. */
internal val EFFECT_IDS = listOf(EFF_SPEED, EFF_RAINBOW, EFF_CLOCK, TEXT_EFFECT_ID)

/** Короткое имя эффекта (для тоста). */
internal fun effectName(id: Int): String = when (id) {
    EFF_SPEED -> "Speed"; EFF_RAINBOW -> "Rainbow"
    EFF_CLOCK -> "Clock"; TEXT_EFFECT_ID -> "Text"; else -> "Effect"
}

/**
 * [text] — текст колеса (эффект «Текст»), [fx] — настройки Speed/Rainbow/Clock,
 * [kmh] — скорость колеса сейчас (Speed показывает настоящую, без вращения — 0).
 */
@Composable
internal fun EffectPreview(
    id: Int,
    modifier: Modifier = Modifier,
    text: WheelVm.TextFx? = null,
    fx: FxParams = FxParams(),
    kmh: Float = 0f
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (id) {
            EFF_SPEED -> SpeedDisc(kmh, fx.speedRed, 160, Modifier.fillMaxSize())
            EFF_RAINBOW -> RainbowDisc(fx, 96, Modifier.fillMaxSize())
            EFF_CLOCK -> ClockDisc(fx.clock, 160, Modifier.fillMaxSize())
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

/** Фаза радуги на экране, градусы: speed 100 — два оборота круга в секунду, как на колесе. */
@Composable
private fun rainbowDeg(speed: Int): Float {
    var deg by remember { mutableStateOf(0f) }
    LaunchedEffect(speed) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) deg = (deg + (now - last) / 1_000_000f * speed * 0.0072f) % 360f
                last = now
            }
        }
    }
    return deg
}

/**
 * Маска колеса (360 × 44), окрашенная как на ободе: одним цветом или радугой,
 * которая течёт по углу с той же скоростью (тон от сектора плюс сдвиг во
 * времени, speed 100 — два оборота круга в секунду). [px] — сторона растра.
 * [rgb] — слой эмодзи «Текста» своим цветом поверх окраски, как на колесе.
 */
@Composable
internal fun StyledMaskDisc(
    mask: ByteArray, style: TextStyle, px: Int, modifier: Modifier = Modifier, rgb: ByteArray? = null
) {
    val bmp = remember(mask, px) { TextMask.disc(mask, px).asImageBitmap() }
    val over = remember(rgb, px) { rgb?.let { TextMask.discRgb(it, px).asImageBitmap() } }
    if (!style.rainbow) {
        val tint = ColorFilter.tint(Color(0xFF000000.toInt() or style.rgb))
        if (over == null) {
            Image(bmp, null, modifier, colorFilter = tint)
            return
        }
        Canvas(modifier) {
            val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
            drawImage(bmp, dstSize = dst, colorFilter = tint)
            drawImage(over, dstSize = dst)
        }
        return
    }
    val deg = rainbowDeg(style.speed)
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
        drawImage(bmp, dstSize = dst)
        // На колесе тон растёт с номером сектора — по часовой стрелке от «3 часов»,
        // как и у sweepGradient; растущий сдвиг уводит картину против часовой.
        rotate(-deg) { drawRect(Brush.sweepGradient(RAINBOW_COLORS), blendMode = BlendMode.SrcIn) }
        // Эмодзи — после радуги: SrcIn выше их бы перекрасил.
        over?.let { drawImage(it, dstSize = dst) }
    }
}

/**
 * Текст колеса — та же маска, которую красит колесо, и тем же цветом.
 * Текст ещё не задан — подпись-заглушка. [px] — сторона растра маски.
 */
@Composable
internal fun TextDisc(fx: WheelVm.TextFx?, px: Int, modifier: Modifier = Modifier) {
    val mask = fx?.mask
    if (fx == null || mask == null || fx.text.isBlank()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Aa", color = Color(0xFFECECEC), fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
        }
        return
    }
    StyledMaskDisc(mask, fx.style, px, modifier, fx.rgb)
}

/**
 * Speed: настоящая скорость колеса (округлённая, как на колесе; не крутится — 0)
 * тем же шрифтом и раскладкой и тем же цветом от зелёного к красному на [red] км/ч.
 */
@Composable
internal fun SpeedDisc(kmh: Float, red: Int, px: Int, modifier: Modifier = Modifier) {
    val v = (kmh + 0.5f).toInt().coerceIn(0, 999)
    val mask by produceState<ByteArray?>(null, v) {
        value = withContext(Dispatchers.Default) { FxMask.speed(v) }
    }
    val m = mask ?: return
    StyledMaskDisc(m, TextStyle(rainbow = false, rgb = FxMask.speedColor(v, red)), px, modifier)
}

/**
 * Clock: время и дата телефона (часы колеса выставляет он же) по окружности, как на
 * ободе, цветом часов колеса. Двоеточия мигают в той же фазе, что на колесе: горят
 * первые полсекунды каждой секунды.
 */
@Composable
internal fun ClockDisc(style: TextStyle, px: Int, modifier: Modifier = Modifier) {
    val mask by produceState<ByteArray?>(null) {
        var shown = -1L
        while (true) {
            val ms = System.currentTimeMillis()
            val now = LocalDateTime.now()
            val colon = ms % 1000L < 500L
            val key = (now.toLocalDate().toEpochDay() * 86400 + now.toLocalTime().toSecondOfDay()) * 2 +
                (if (colon) 1 else 0)
            if (key != shown) {
                shown = key
                value = withContext(Dispatchers.Default) { FxMask.clock(now, colon) }
            }
            // До следующей половины секунды — двоеточия и цифры не опаздывают.
            delay((500L - System.currentTimeMillis() % 500L).coerceIn(20L, 500L))
        }
    }
    val m = mask ?: return
    StyledMaskDisc(m, style, px, modifier)
}

/**
 * Rainbow: спектр со спиралью, скоростью и резкостью полос колеса — та же таблица
 * цвета и та же раскладка по секторам и диодам, что в effects.cpp.
 */
@Composable
internal fun RainbowDisc(fx: FxParams, px: Int, modifier: Modifier = Modifier) {
    val disc = remember(px) { FxMask.RainbowDisc(px) }
    val lut = remember(fx.rbSharp) { FxMask.rainbowLut(fx.rbSharp) }
    // Фаза в долях круга (1024 на оборот), копится по кадрам — смена скорости
    // ползунком не дёргает картину, как и на колесе.
    var phase by remember { mutableStateOf(0.0) }
    LaunchedEffect(fx.rbSpeed) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    // speed 100 — два оборота в секунду: 2048 единиц/с.
                    phase = (phase + (now - last) / 1e9 * fx.rbSpeed * 20.48) % FxMask.RB_LUT
                }
                last = now
            }
        }
    }
    Canvas(modifier) {
        val img = disc.draw(lut, phase.toInt()).asImageBitmap()
        drawImage(img, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Low)
    }
}
