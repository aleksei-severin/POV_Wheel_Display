@file:OptIn(ExperimentalFoundationApi::class)

package com.povwheel.app.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role

/*
 * Виброотклик на тап по активным элементам интерфейса.
 *
 * Один отклик на все кнопки, пилюли, переключатели и плитки: та же лёгкая
 * отдача, что и у системных кнопок (`VIRTUAL_KEY`). `performHapticFeedback`
 * уважает системную настройку тактильного отклика — если пользователь выключил
 * её в настройках телефона, ничего не происходит, отдельного тумблера в
 * приложении не заводим.
 *
 * Долгие нажатия (`combinedClickable`) Compose и так сопровождает своим
 * `LongPress`-откликом, поэтому здесь добавляется только отдача на обычный тап.
 * Ползунки и перетаскивание числа намеренно не трогаем — это не тап.
 */

// Одно место, где выбран тип отдачи — если `VIRTUAL_KEY` где-то ощущается
// слишком резким, поменять здесь на `CONTEXT_CLICK` (тоже с API 23).
private const val TAP_FEEDBACK = HapticFeedbackConstants.VIRTUAL_KEY

fun View.tapFeedback() = performHapticFeedback(TAP_FEEDBACK)

/**
 * Тихий «щелчок» на каждое деление — как у системных колёс выбора времени/числа.
 * Заметно слабее [tapFeedback]: за одно перетаскивание ползунка или градусов
 * магнита их набегают десятки. Вызывать только когда дискретное значение
 * реально изменилось, а не на каждый пиксель жеста.
 */
fun View.tickFeedback() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

/**
 * Оборачивает лямбду `onClick` виброоткликом. Для Material-кнопок:
 * `Button(onClick = hapticClick { ... })`.
 */
@Composable
fun hapticClick(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return { view.tapFeedback(); onClick() }
}

/**
 * То же для колбэков со значением — `Switch`/`Checkbox`:
 * `Switch(onCheckedChange = hapticChange { ... })`.
 */
@Composable
fun <T> hapticChange(onChange: (T) -> Unit): (T) -> Unit {
    val view = LocalView.current
    return { v -> view.tapFeedback(); onChange(v) }
}

/** `Modifier.clickable` с виброоткликом на тап. */
@Composable
fun Modifier.tapClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier {
    val view = LocalView.current
    return clickable(enabled = enabled, onClickLabel = onClickLabel, role = role) {
        view.tapFeedback(); onClick()
    }
}

/** `Modifier.combinedClickable` с виброоткликом на обычный тап. */
@Composable
fun Modifier.tapCombinedClickable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val view = LocalView.current
    return combinedClickable(
        enabled = enabled,
        onLongClick = onLongClick,
        onClick = { view.tapFeedback(); onClick() }
    )
}
