package com.povwheel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.povwheel.app.WheelVm
import com.povwheel.app.convert.TextMask

// Готовые цвета палитры (0xRRGGBB). Чёрного нет: на колесе он — пустота.
private val SWATCHES = listOf(
    0xFFFFFF, 0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x00C7BE,
    0x32ADE6, 0x3B6BFF, 0xAF52DE, 0xFF2D92, 0xFFB3C7
)

/**
 * Редактор эффекта «Текст» (длинный тап по его плитке). Всё применяется сразу,
 * без кнопки «сохранить»: каждая правка строки — новая маска на колесо, каждый
 * цвет — новый цвет; первая же правка включает эффект на ободе. Превью сверху —
 * та же маска, которую получит колесо, тем же цветом.
 */
@Composable
internal fun TextEffectDialog(vm: WheelVm, onDismiss: () -> Unit) {
    val fx by vm.textFx.collectAsState()
    val st = fx.style
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    // Строка поля — своя: состояние колеса отстаёт на отрисовку маски, и поле,
    // привязанное к нему, перескакивало бы назад посреди набора.
    var tf by remember {
        val t = vm.textFx.value.text
        mutableStateOf(TextFieldValue(t, TextRange(t.length)))
    }
    DisposableEffect(Unit) {
        vm.beginTextEdit()
        onDispose { vm.endTextEdit() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))

                Box(
                    Modifier.align(Alignment.CenterHorizontally).size(220.dp)
                        .clip(CircleShape).background(Color.Black)
                ) {
                    TextDisc(fx, 480, Modifier.fillMaxSize())
                    HubHole(cs.surface, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = tf,
                    onValueChange = { v ->
                        val t = if (v.text.length > TextMask.MAX_CHARS) {
                            val cut = v.text.take(TextMask.MAX_CHARS)
                            v.copy(text = cut, selection = TextRange(minOf(v.selection.end, cut.length)))
                        } else v
                        val changed = t.text != tf.text
                        tf = t
                        if (changed) vm.editText(t.text)
                    },
                    singleLine = true,
                    label = { Text("Text along the rim") },
                    supportingText = {
                        Text(tf.text.length.toString() + " / " + TextMask.MAX_CHARS + " · longer text gets smaller")
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                Text("Colour", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                // Две строки по шесть: палитра и в конце радуга.
                val cells: List<Int?> = SWATCHES + listOf(null)          // null — радуга
                cells.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { rgb ->
                            if (rgb == null) {
                                Swatch(Brush.sweepGradient(RAINBOW_COLORS), selected = st.rainbow) {
                                    vm.editTextStyle(st.copy(rainbow = true))
                                }
                            } else {
                                Swatch(Brush.linearGradient(listOf(Color(0xFF000000.toInt() or rgb), Color(0xFF000000.toInt() or rgb))),
                                    selected = !st.rainbow && st.rgb == rgb) {
                                    vm.editTextStyle(st.copy(rainbow = false, rgb = rgb))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (st.rainbow) {
                    Row {
                        Text("Rainbow speed", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(if (st.speed == 0) "still" else st.speed.toString() + "%",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    EmbossedSlider(
                        value = st.speed.toFloat(), range = 0f..100f, divisions = 20,
                        onChange = { vm.editTextStyle(vm.textFx.value.style.copy(speed = it.toInt())) },
                        onFinished = {},
                        fill = Brush.horizontalGradient(RAINBOW_COLORS),
                        dotColor = cs.primary
                    )
                } else {
                    // Любой тон, если готовых мало.
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(0xFF000000.toInt() or st.rgb, hsv)
                    Row {
                        Text("Hue", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    EmbossedSlider(
                        value = hsv[0], range = 0f..359f, divisions = 72,
                        onChange = { h ->
                            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)) and 0xFFFFFF
                            vm.editTextStyle(vm.textFx.value.style.copy(rainbow = false, rgb = rgb))
                        },
                        onFinished = {},
                        fill = Brush.horizontalGradient(RAINBOW_COLORS),
                        dotColor = Color(0xFF000000.toInt() or st.rgb)
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = hapticClick(onDismiss)) { Text("Done") }
                }
            }
        }
    }
}

/** Кружок палитры; выбранный — в кольце цвета акцента. */
@Composable
private fun Swatch(fill: Brush, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(38.dp)
            .border(2.dp, if (selected) cs.primary else Color.Transparent, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, Color(cs.onSurface.copy(alpha = 0.15f).toArgb()), CircleShape)
            .tapClickable(onClick = onClick)
    )
}
