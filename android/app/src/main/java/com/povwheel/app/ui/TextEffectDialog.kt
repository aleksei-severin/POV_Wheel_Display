package com.povwheel.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.povwheel.app.WheelVm
import com.povwheel.app.convert.TextMask

/**
 * Редактор эффекта «Текст» (длинный тап по его плитке). Всё применяется сразу,
 * без кнопки «сохранить»: каждая правка строки — новая маска на колесо, каждый
 * цвет — новый цвет; первая же правка включает эффект на ободе. Превью сверху —
 * та же маска, которую получит колесо, тем же цветом.
 */
@Composable
internal fun TextEffectDialog(vm: WheelVm, onDismiss: () -> Unit) {
    val fx by vm.textFx.collectAsState()
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

    EffectDialogFrame("Text", onDismiss, preview = { TextDisc(fx, 480, Modifier.fillMaxSize()) }) {
        OutlinedTextField(
            value = tf,
            onValueChange = { v ->
                val t = if (v.text.length > TextMask.MAX_CHARS) {
                    val cut = TextMask.clip(v.text)
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
        ColourStylePicker(fx.style, latest = { vm.textFx.value.style }) { vm.editTextStyle(it) }
    }
}
