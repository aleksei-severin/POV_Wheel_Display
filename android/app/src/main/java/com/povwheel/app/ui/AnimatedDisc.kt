package com.povwheel.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.povwheel.app.convert.Geom
import com.povwheel.app.convert.PreviewClip
import kotlinx.coroutines.delay

/**
 * Радиус отверстия под ступицу в долях стороны диска — тот же край, что у
 * полярного рендера (`DiscRender`/`WheelThumb`): внутренний диод минус половина
 * шага между диодами.
 */
internal val HUB_HOLE_FRAC: Float = (Geom.R_INNER_FRAC -
    (Geom.R_OUTER_FRAC - Geom.R_INNER_FRAC) / (Geom.LEDS_PER_SIDE - 1) / 2).toFloat()

/** Отверстие под ступицу поверх круглого превью — цветом фона ячейки. */
@Composable
internal fun HubHole(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawCircle(color, radius = size.minDimension * HUB_HOLE_FRAC) }
}

/**
 * Крутит кадры [clip] по кругу с его задержкой. Один кадр (или отсутствие клипа)
 * — просто картинка [fallback]. Цикл живёт в `LaunchedEffect`, поэтому уезжает
 * вместе с элементом за пределы экрана без ручной остановки.
 */
@Composable
fun AnimatedDisc(
    clip: PreviewClip?,
    fallback: Bitmap?,
    modifier: Modifier = Modifier
) {
    val frames = clip?.frames
    if (frames.isNullOrEmpty()) {
        if (fallback != null && !fallback.isRecycled) {
            Image(fallback.asImageBitmap(), null, modifier)
        }
        return
    }
    if (frames.size == 1) {
        Image(frames[0].asImageBitmap(), null, modifier)
        return
    }

    var idx by remember(clip) { mutableStateOf(0) }
    LaunchedEffect(clip) {
        val step = clip.delayMs.toLong().coerceAtLeast(33L)
        while (true) {
            delay(step)
            idx = (idx + 1) % frames.size
        }
    }
    val shown = frames.getOrElse(idx) { frames[0] }
    Image(shown.asImageBitmap(), null, modifier)
}
