package com.povwheel.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.povwheel.app.convert.PreviewClip
import kotlinx.coroutines.delay

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
