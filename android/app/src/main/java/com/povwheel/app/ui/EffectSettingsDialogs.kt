package com.povwheel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.povwheel.app.WheelVm
import com.povwheel.app.ble.TextStyle
import com.povwheel.app.convert.FxMask

// Готовые цвета палитры (0xRRGGBB). Чёрного нет: на колесе он — пустота.
private val SWATCHES = listOf(
    0xFFFFFF, 0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x00C7BE,
    0x32ADE6, 0x3B6BFF, 0xAF52DE, 0xFF2D92, 0xFFB3C7
)

/**
 * Окно настроек эффекта: заголовок, круглое превью таким, каким эффект будет на
 * ободе, под ним — регуляторы. Всё применяется сразу, без кнопки «сохранить».
 */
@Composable
internal fun EffectDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    preview: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.align(Alignment.CenterHorizontally).size(220.dp)
                        .clip(CircleShape).background(Color.Black)
                ) {
                    preview()
                    HubHole(cs.surface, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(12.dp))
                content()
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = hapticClick(onDismiss)) { Text("Done") }
                }
            }
        }
    }
}

/** Подпись регулятора слева и его значение справа. */
@Composable
private fun SettingLabel(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Цвет — один из готовых, любой тон ползунком или радуга со своей скоростью.
 * Общий для «Текста» и часов: колесо красит их маски одинаково. [latest] — самое
 * свежее значение (ползунок шлёт правки чаще, чем экран успевает перерисоваться).
 */
@Composable
internal fun ColourStylePicker(st: TextStyle, latest: () -> TextStyle, onChange: (TextStyle) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text("Colour", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    // Две строки по шесть: палитра и в конце радуга.
    val cells: List<Int?> = SWATCHES + listOf(null)          // null — радуга
    cells.chunked(6).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { rgb ->
                if (rgb == null) {
                    Swatch(Brush.sweepGradient(RAINBOW_COLORS), selected = st.rainbow) {
                        onChange(latest().copy(rainbow = true))
                    }
                } else {
                    Swatch(Brush.linearGradient(listOf(Color(0xFF000000.toInt() or rgb), Color(0xFF000000.toInt() or rgb))),
                        selected = !st.rainbow && st.rgb == rgb) {
                        onChange(latest().copy(rainbow = false, rgb = rgb))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (st.rainbow) {
        SettingLabel("Rainbow speed", if (st.speed == 0) "still" else st.speed.toString() + "%")
        EmbossedSlider(
            value = st.speed.toFloat(), range = 0f..100f, divisions = 20,
            onChange = { onChange(latest().copy(speed = it.toInt())) },
            onFinished = {},
            fill = Brush.horizontalGradient(RAINBOW_COLORS),
            dotColor = cs.primary
        )
    } else {
        // Любой тон, если готовых мало.
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(0xFF000000.toInt() or st.rgb, hsv)
        SettingLabel("Hue", "")
        EmbossedSlider(
            value = hsv[0], range = 0f..359f, divisions = 72,
            onChange = { h ->
                val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f)) and 0xFFFFFF
                onChange(latest().copy(rainbow = false, rgb = rgb))
            },
            onFinished = {},
            fill = Brush.horizontalGradient(RAINBOW_COLORS),
            dotColor = Color(0xFF000000.toInt() or st.rgb)
        )
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

/** Сеанс правки настроек эффекта: пока окно открыто, чтение с колеса их не затирает. */
@Composable
private fun FxEditSession(vm: WheelVm) {
    DisposableEffect(Unit) {
        vm.beginFxEdit()
        onDispose { vm.endFxEdit() }
    }
}

/** Часы (длинный тап по плитке): цвет — как у «Текста». Превью идёт по часам телефона. */
@Composable
internal fun ClockEffectDialog(vm: WheelVm, onDismiss: () -> Unit) {
    val fx by vm.fxParams.collectAsState()
    FxEditSession(vm)
    EffectDialogFrame("Clock", onDismiss, preview = { ClockDisc(fx.clock, 480, Modifier.fillMaxSize()) }) {
        ColourStylePicker(fx.clock, latest = { vm.fxParams.value.clock }) { st ->
            vm.editFx(EFF_CLOCK, vm.fxParams.value.copy(clock = st))
        }
    }
}

/**
 * Speed (длинный тап по плитке): на какой скорости цифры становятся красными.
 * Превью — настоящая скорость колеса; не крутится — 0.
 */
@Composable
internal fun SpeedEffectDialog(vm: WheelVm, kmh: Float, onDismiss: () -> Unit) {
    val fx by vm.fxParams.collectAsState()
    FxEditSession(vm)
    val green = Color(0xFF000000.toInt() or FxMask.speedColor(0, 100))
    val yellow = Color(0xFF000000.toInt() or FxMask.speedColor(50, 100))
    val red = Color(0xFF000000.toInt() or FxMask.speedColor(100, 100))
    EffectDialogFrame("Speed", onDismiss, preview = { SpeedDisc(kmh, fx.speedRed, 480, Modifier.fillMaxSize()) }) {
        SettingLabel("Red at", fx.speedRed.toString() + " km/h")
        EmbossedSlider(
            value = fx.speedRed.toFloat(), range = 5f..200f, divisions = 39,
            onChange = { vm.editFx(EFF_SPEED, vm.fxParams.value.copy(speedRed = it.toInt())) },
            onFinished = {},
            fill = Brush.horizontalGradient(listOf(green, yellow, red)),
            dotColor = red
        )
        Text(
            "Digits go from green at a standstill through yellow to red at this speed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Rainbow (длинный тап по плитке): скорость вращения и резкость переходов цветов. */
@Composable
internal fun RainbowEffectDialog(vm: WheelVm, onDismiss: () -> Unit) {
    val fx by vm.fxParams.collectAsState()
    FxEditSession(vm)
    val cs = MaterialTheme.colorScheme
    EffectDialogFrame("Rainbow", onDismiss, preview = { RainbowDisc(fx, 300, Modifier.fillMaxSize()) }) {
        SettingLabel("Speed", if (fx.rbSpeed == 0) "still" else fx.rbSpeed.toString() + "%")
        EmbossedSlider(
            value = fx.rbSpeed.toFloat(), range = 0f..100f, divisions = 20,
            onChange = { vm.editFx(EFF_RAINBOW, vm.fxParams.value.copy(rbSpeed = it.toInt())) },
            onFinished = {},
            dotColor = cs.primary
        )
        Spacer(Modifier.height(4.dp))
        SettingLabel(
            "Colour bands",
            when (fx.rbSharp) { 0 -> "smooth"; 100 -> "sharp"; else -> fx.rbSharp.toString() + "%" }
        )
        EmbossedSlider(
            value = fx.rbSharp.toFloat(), range = 0f..100f, divisions = 20,
            onChange = { vm.editFx(EFF_RAINBOW, vm.fxParams.value.copy(rbSharp = it.toInt())) },
            onFinished = {},
            dotColor = cs.primary
        )
        Row {
            Text("Smooth blend", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text("Red · orange · yellow · …", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}
