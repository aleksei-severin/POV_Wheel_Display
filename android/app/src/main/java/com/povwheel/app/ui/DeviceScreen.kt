package com.povwheel.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.povwheel.app.WheelVm
import com.povwheel.app.ble.Link
import com.povwheel.app.ble.Proto
import com.povwheel.app.ble.Settings
import com.povwheel.app.ble.Tele
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Вкладок больше нет — один экран: сверху библиотека, за ней настройки дисплея
// той же лентой. Меню Tuning свёрнуто в секцию «Colour». Лог — окно из
// Maintenance → Log.

@Composable
fun DeviceScreen(vm: WheelVm) {
    val client = vm.currentClient()
    if (client == null) {
        // Колесо пропало, пока его экран был открыт: возвращаемся к списку,
        // а не рисуем интерфейс поверх мёртвой ссылки.
        LaunchedEffect(Unit) { vm.current.value = null }
        return
    }
    val link by client.link.collectAsState()
    val tele by client.tele.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Header(vm, link)
        Hero(vm, tele, link == Link.Ready)
        Box(Modifier.weight(1f)) { MainContent(vm, tele) }
    }
}

/**
 * Единый экран: плитка библиотеки ([LibraryTab]) плюс блоки настроек дисплея,
 * добавленные в ту же сетку full-span элементами — всё скроллится вместе.
 */
@Composable
private fun MainContent(vm: WheelVm, tele: Tele) {
    val s by vm.settings.collectAsState()
    var colourOpen by rememberSaveable { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val fw by vm.fwProgress.collectAsState()
    val fwPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.updateFirmware(uri) { vm.say(it) } }

    LibraryTab(vm, tele) {
        item(key = "brightness", span = { GridItemSpan(maxLineSpan) }) {
            SettingCard { AutoBrightnessRange(vm, s, tele) }
        }
        item(key = "magnet", span = { GridItemSpan(maxLineSpan) }) {
            SettingCard {
                // Спиннер справа, а не под заголовком, — так блок ниже по высоте.
                // Спиннер, а не ползунок: на 360 положениях один пиксель дорожки
                // стоит больше градуса, а «поставить картинку ровно» — правка на
                // единицы градусов.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Magnet position", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("Stands the animation upright.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    NumberSpinner(
                        value = s.angle,
                        range = 0..360,
                        suffix = "°",
                        modifier = Modifier.width(176.dp),
                        onChange = { vm.settings.value = s.copy(angle = it) },
                        onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() }
                    )
                }
            }
        }
        item(key = "colour", span = { GridItemSpan(maxLineSpan) }) {
            // Полдюжины ползунков незачем держать перед глазами — прячем под тап.
            SettingCard {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { colourOpen = !colourOpen }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Colour", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (colourOpen) "▾" else "▸",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (colourOpen) {
                    Spacer(Modifier.height(4.dp))
                    ColourControls(vm)
                }
            }
        }
        item(key = "maint", span = { GridItemSpan(maxLineSpan) }) {
            SettingCard {
                Text("Maintenance", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                // «OFF» — уход в транспортный режим: колесо гаснет и до удержания
                // кнопки не проснётся ни по тряске, ни по BLE.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MaintBtn("OFF", fw == null, danger = true) { confirmOff = true }
                    MaintBtn("Reboot", fw == null) { vm.reboot() }
                    MaintBtn("Wi-Fi", fw == null) {
                        vm.wifi(true); vm.say("Wi-Fi is coming up for OTA")
                    }
                    MaintBtn("Update", fw == null) {
                        fwPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    }
                    MaintBtn("Log", fw == null) { showLog = true }
                }
                if (fw != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { fw ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Sending firmware — " + ((fw ?: 0f) * 100).toInt() + "%. " +
                            "Keep the phone near the wheel; it reboots by itself when done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Power off the wheel?") },
            text = {
                Text(
                    "The wheel shuts down and will not wake on a shake or over " +
                        "Bluetooth — only by holding its button for about 1.5 s."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    vm.powerOff(); vm.say("Powering off")
                }) { Text("Power off") }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } }
        )
    }

    if (showLog) LogDialog(vm) { showLog = false }
}

// ------------------------------------------------------------------- обвязка

@Composable
private fun Header(vm: WheelVm, link: Link) {
    val client = vm.currentClient()
    // Имя берём из общего списка, а НЕ из client.hello. hello — снимок,
    // сделанный при подключении: он не меняется от переименования, да и
    // Compose за ним не следит (обычное @Volatile-поле, не State). Поэтому
    // после Rename заголовок так и показывал старое POV-xxxx, хотя строка в
    // списке колёс обновлялась сразу.
    val wheels by vm.wheels.collectAsState()
    val curAddr by vm.current.collectAsState()
    val title = wheels.firstOrNull { it.address == curAddr }?.name
        ?: client?.hello?.name ?: "POV Wheel"

    var renaming by remember { mutableStateOf(false) }

    // Одна строка: «‹ Wheels», имя (оно же кнопка переименования) и статус
    // связи справа — там, где раньше была светящаяся точка. Дату сборки убрали,
    // высоту шапки — до одной строки.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = { vm.current.value = null },
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) { Text("‹ Wheels") }

        // Тап по имени открывает переименование — отдельной строки «Name» в
        // Display больше нет.
        Text(
            title,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .clickable { renaming = true }
                .padding(horizontal = 6.dp, vertical = 6.dp)
        )

        Text(
            if (link == Link.Ready) "Online" else "Offline",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (link == Link.Ready) Ok else Danger,
            modifier = Modifier.padding(end = 10.dp)
        )
    }

    if (renaming) {
        RenameDialog(
            current = title,
            onDismiss = { renaming = false },
            onSave = { name -> vm.renameCurrent(name) { vm.say(it) }; renaming = false }
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember { mutableStateOf(current) }
    val ok = Proto.nameOk(draft.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename wheel") },
        text = {
            Column {
                Text(
                    "What this wheel is called in the device list. Two wheels on one " +
                        "bike are both \"POV-xxxx\" out of the box, and which is which is " +
                        "anyone's guess.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(Proto.NAME_MAX) },
                    singleLine = true,
                    isError = draft.isNotEmpty() && !ok,
                    label = { Text("Display name") }
                )
                Text(
                    "Latin letters, digits, - and _ , up to " + Proto.NAME_MAX + " characters. " +
                        "Takes effect at once. The mDNS/OTA hostname is unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onSave(draft.trim()) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Hero(vm: WheelVm, tele: Tele, online: Boolean) {
    var showCirc by remember { mutableStateOf(false) }
    var showRpm by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()

    // Нет связи или телеметрия ещё не пришла — в поле Battery прочерки, как у
    // скорости/оборотов, а не нули напряжения и процентов.
    val battKnown = online && tele.vbatMv > 0

    // IntrinsicSize.Min + fillMaxHeight — обе карточки одной высоты, по более
    // высокой из двух.
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(12.dp)) {
                Label("DISPLAY")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (tele.kmh > 0f) String.format("%.1f", tele.kmh) else "--",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { showCirc = true }
                    )
                    Text(" km/h", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (tele.rpm > 0f) tele.rpm.roundToInt().toString() else "--",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable { showRpm = true }
                    )
                    Text(" rpm", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (tele.play) vm.stopDisplay() else vm.currentPlayAgain() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (tele.play) Danger else Ok
                        )
                    ) { Text(if (tele.play) "■ Stop" else "▶ Start") }
                }
            }
        }

        Card(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(12.dp)) {
                Label("BATTERY")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (battKnown) tele.soc.toString() + "%" else "--",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            !battKnown -> MaterialTheme.colorScheme.onSurfaceVariant
                            tele.soc < 15 -> Danger
                            tele.soc < 35 -> Warn
                            else -> Ok
                        }
                    )
                    Text(
                        "  " + (if (battKnown) String.format("%.2f", tele.vbatMv / 1000f) else "--") + " V",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "USB " + (if (battKnown) String.format("%.2f", tele.vusbMv / 1000f) else "--") + " V",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                if (battKnown) {
                    val chg = when (tele.chg) {
                        1 -> "Charging"; 2 -> "Charged"; else -> "Discharging"
                    }
                    Badge2(chg, if (tele.chg == 2) Ok else if (tele.chg == 1) Accent else null)
                    if (tele.cutoff) {
                        Spacer(Modifier.height(4.dp))
                        Badge2("Empty — display off until charged", Danger)
                    } else if (tele.ablCap < 100) {
                        Spacer(Modifier.height(4.dp))
                        Badge2("Low — power limited to " + tele.ablCap + "%", Warn)
                    }
                } else {
                    Badge2("Offline", null)
                }
            }
        }
    }

    if (showCirc) {
        NumberDialog(
            title = "Wheel circumference",
            body = "Used only to turn rpm into km/h. Roll the wheel one full turn and " +
                   "measure, or take it from the tyre: 700x35C is about 2168 mm, " +
                   "28 inch about 2200 mm, 29 inch MTB about 2300 mm.",
            initial = settings.circ, unit = "mm", min = 2000, max = 2500,
            onDismiss = { showCirc = false },
            onSave = { v ->
                vm.pushSettings(settings.copy(circ = v))
                vm.say("Wheel circumference: " + v + " mm")
                showCirc = false
            }
        )
    }
    if (showRpm) {
        RpmDialog(
            on = settings.rpmOn, off = settings.rpmOff,
            onDismiss = { showRpm = false },
            onSave = { on, off ->
                vm.pushSettings(settings.copy(rpmOn = on, rpmOff = off))
                vm.say("Rendering: start " + on + " rpm, stop " + off + " rpm")
                showRpm = false
            }
        )
    }
}

// LibraryTab (единый экран: плитка + блоки настроек) — в ui/LibraryGrid.kt;
// сами блоки настроек собирает MainContent выше.

/** Блок настроек в своей карточке. Отступы между карточками задаёт сетка
 *  (`verticalArrangement`), поэтому своего нижнего поля у карточки нет. */
@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

/**
 * Диапазон авто-яркости одним слайдером с двумя бегунками вместо двух
 * отдельных. Оранжевая метка на той же шкале — текущая яркость
 * (`global_effective_brightness`) по нынешней освещённости.
 */
@Composable
private fun AutoBrightnessRange(vm: WheelVm, s: Settings, tele: Tele) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Auto Brightness Range", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(s.bmin.toString() + "–" + s.bmax + " / 31",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Дорожка слайдера отбита от краёв на радиус бегунка — метку считаем
        // в тех же границах, иначе она разъедется с делениями.
        val inset = 10.dp
        val lo = s.bmin.coerceAtMost(s.bmax)
        val hi = s.bmax.coerceAtLeast(s.bmin)
        RangeSlider(
            modifier = Modifier.fillMaxWidth(),
            value = lo.toFloat()..hi.toFloat(),
            onValueChange = { r ->
                val a = r.start.roundToInt().coerceIn(1, 31)
                val b = r.endInclusive.roundToInt().coerceIn(1, 31)
                vm.settings.value = s.copy(
                    bmin = a.coerceAtMost(b),
                    bmax = b.coerceAtLeast(a)
                )
            },
            onValueChangeFinished = { vm.pushSettings(vm.settings.value); vm.saveSettings() },
            valueRange = 1f..31f,
            steps = 29
        )
        // Оранжевая метка — только когда лента реально светит: на выключенном
        // дисплее eff_bri == 0, и метка у левого края читалась бы как «яркость 1».
        if (tele.effBri in 1..31) {
            val frac = ((tele.effBri - 1f) / 30f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = inset)
                    .offset(x = (maxWidth - inset * 2) * frac - 1.5.dp)
                    .width(3.dp)
                    .height(22.dp)
                    .background(Warn, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun RowScope.MaintBtn(
    label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
        colors = if (danger)
            ButtonDefaults.outlinedButtonColors(contentColor = Danger)
        else ButtonDefaults.outlinedButtonColors()
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
    }
}

// ------------------------------------------------------------------ Colour
// Бывшее меню Tuning: сворачивается под заголовком «Colour» в Display.

@Composable
private fun ColourControls(vm: WheelVm) {
    val s by vm.settings.collectAsState()
    Column {
        Text(
            "Gamma, saturation and contrast shape the picture; R/G/B set the white " +
                "balance of the LEDs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        SliderRow("Gamma", String.format("%.1f", s.gammaX100 / 100f),
            s.gammaX100 / 10f, 10f, 50f, 40,
            onChange = { vm.settings.value = s.copy(gammaX100 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() })
        Hint("Higher = deeper shadows and a cleaner black; lower = flatter, brighter mid-tones.")

        SliderRow("Saturation", String.format("%.1f", s.satX100 / 100f),
            s.satX100 / 10f, 10f, 30f, 20,
            onChange = { vm.settings.value = s.copy(satX100 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() })
        Hint("How vivid the colours are. 1.0 is the picture as uploaded.")

        SliderRow("Contrast", (s.contrastX10 / 10).toString() + "%",
            (s.contrastX10 / 10).toFloat(), 0f, 100f, 100,
            onChange = { vm.settings.value = s.copy(contrastX10 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() })
        Hint("A few percent is usually enough — large values swallow detail at both ends.")

        Spacer(Modifier.height(6.dp))
        Text("White balance", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))

        SliderRow("Red", (s.rgX10 / 10).toString() + "%",
            (s.rgX10 / 10).toFloat(), 0f, 100f, 20,
            onChange = { vm.settings.value = s.copy(rgX10 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() })
        SliderRow("Green", (s.ggX10 / 10).toString() + "%",
            (s.ggX10 / 10).toFloat(), 0f, 100f, 20,
            onChange = { vm.settings.value = s.copy(ggX10 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() })
        SliderRow("Blue", (s.bgX10 / 10).toString() + "%",
            (s.bgX10 / 10).toFloat(), 0f, 100f, 20,
            onChange = { vm.settings.value = s.copy(bgX10 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() })
        Hint("Green LEDs are the brightest of the three, so green usually sits lower " +
             "than the others. Aim for neutral white, not maximum output.")

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                val d = vm.settings.value.copy(
                    gammaX100 = 250, satX100 = 150, contrastX10 = 50,
                    rgX10 = 1000, ggX10 = 800, bgX10 = 1000
                )
                vm.pushSettings(d); vm.saveSettings()
                vm.say("Colour settings restored")
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("↺ Restore defaults") }
    }
}

// ------------------------------------------------------------------ Эффекты

// Эффекты переехали в плитку Library (ui/LibraryGrid.kt); превью — ui/EffectPreviews.kt.

// ---------------------------------------------------------------------- Лог
// Не вкладка, а окно: Display → Maintenance → Log. Опрос идёт, только пока
// окно открыто.

@Composable
private fun LogDialog(vm: WheelVm, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxSize().padding(10.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            LogContent(vm, onDismiss)
        }
    }
}

@Composable
private fun LogContent(vm: WheelVm, onDismiss: () -> Unit) {
    val lines by vm.logLines.collectAsState()
    LaunchedEffect(Unit) {
        while (true) { vm.pollLog(); delay(2000) }
    }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val logBase by vm.logFirstIdx.collectAsState()

    // «Прилипание» к концу: доматываем сами только пока пользователь и так
    // внизу. Иначе новая строка каждые две секунды вырывала бы список из рук у
    // того, кто отлистал вверх что-то прочитать.
    var stick by remember { mutableStateOf(true) }

    // Палец лёг на список — сразу перестаём тянуть его вниз, не дожидаясь конца
    // жеста: бороться с рукой пользователя нельзя.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            if (i is DragInteraction.Start) stick = false
        }
    }

    // Решение принимаем ТОЛЬКО когда прокрутка уже остановилась.
    //
    // Первая версия пересчитывала stick непрерывно из layoutInfo — то есть из
    // того самого, что меняет наша же анимация, — и держала stick ключом
    // эффекта, который эту анимацию запускает. Получалась замкнутая петля:
    // /logs отдаёт строки пачкой, после вставки пачки последний элемент уже
    // не виден, stick тут же становился false, эффект перезапускался, отменяя
    // едва начавшуюся прокрутку, и залипал навсегда. Здесь обратной связи нет:
    // пока список стоит, stick не трогается вовсе.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { moving ->
            if (!moving) {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                stick = info.totalItemsCount == 0 || last >= info.totalItemsCount - 1
            }
        }
    }

    // Ключ — САМ список, а не его длина. Буфер обрезан четырьмя сотнями строк,
    // и в установившемся режиме — то есть в любой сессии длиннее пары минут,
    // ровно когда автопрокрутка и нужна — длина навсегда остаётся 400, а
    // содержимое меняется каждый опрос. По длине эффект не перезапускался бы
    // больше никогда. Сравнение списков структурное, так что новые строки дают
    // новый ключ, а пустой ответ — нет.
    LaunchedEffect(lines) {
        if (stick && lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Device log · " + lines.size,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            // Выделение пальцем достаёт только то, что на экране, — так устроен
            // SelectionContainer поверх ленивого списка. Для «прислать весь лог»
            // нужна кнопка, и она надёжнее любого жеста.
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                    vm.say("Log copied (" + lines.size + " lines)")
                },
                enabled = lines.isNotEmpty()
            ) { Text("Copy") }
            TextButton(onClick = { vm.clearLogView() }) { Text("Clear") }
            TextButton(onClick = onDismiss) { Text("✕") }
        }
        Card(Modifier.fillMaxSize()) {
            // SelectionContainer — то, чего не хватало: без него Text в Compose
            // не выделяется вообще, ни долгим нажатием, ни как-либо ещё.
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                    itemsIndexed(lines, key = { i, _ -> logBase + i }) { _, l ->
                        Text(
                            l,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = logColor(l),
                            // Перенос вместо горизонтальной прокрутки у каждой
                            // строки: своя прокрутка перехватывала жест и мешала
                            // тянуть выделение, а строка целиком на экране всё
                            // равно удобнее, чем возить её пальцем вбок.
                            softWrap = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun logColor(l: String): Color = when {
    l.contains("[ERR]") -> Danger
    l.contains("[WARN]") -> Warn
    l.contains("[DISP]") -> Ok
    l.contains("[BLE]") || l.contains("[NET]") -> Accent
    l.contains("[PWR]") -> Color(0xFFFB923C)
    l.contains("[BATT]") -> Warn
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ------------------------------------------------------------ вспомогательное

@Composable
private fun Label(t: String) = Text(
    t, style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun Hint(t: String) = Text(
    t, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = 8.dp)
)

@Composable
private fun Badge2(text: String, color: Color?) {
    val c = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) { Text(text, style = MaterialTheme.typography.labelSmall, color = c) }
}

/**
 * Ползунок, который живо обновляет подпись, но отправляет значение на колесо
 * только когда палец отпустили. Слать по BLE каждое промежуточное значение
 * значило бы выстроить десятки записей в очередь за одним движением.
 */
/**
 * Число, которое крутят прямо на нём самом.
 *
 * Слева «−», справа «+», между ними текущее значение: горизонтальным
 * перетаскиванием оно листается, нажатием — превращается в поле ввода.
 * Ленты соседних цифр по бокам нет намеренно: она занимала половину карточки,
 * а показывала то, что и так очевидно.
 *
 * Ввод с клавиатуры держит СВОЙ текст, пока идёт правка, и применяет его лишь
 * по «готово» или по потере фокуса. Это принципиально: раньше полем управляло
 * само число, и любой неразобранный текст откатывался к прежнему значению —
 * стереть содержимое было нельзя, а набранная следом цифра приписывалась к
 * старой, превращая «1» и «5» в «15». Здесь пустое поле — законное
 * промежуточное состояние, и ничего за спиной пользователя не дописывается.
 */
@Composable
private fun NumberSpinner(
    value: Int,
    range: IntRange,
    suffix: String = "",
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
    onCommit: () -> Unit = {}
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current

    fun clamp(v: Int) = v.coerceIn(range.first, range.last)

    fun commitText() {
        val v = text.trim().toIntOrNull()
        if (v != null) { onChange(clamp(v)); onCommit() }   // мусор и пустое — просто откат
        editing = false
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = { onChange(clamp(value - 1)); onCommit() },
            enabled = value > range.first,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(44.dp)
        ) { Text("−", fontSize = 20.sp) }

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (editing) {
                // Поле только что появилось и фокуса ещё не получало, а
                // onFocusChanged срабатывает и на «не в фокусе» при первой же
                // компоновке. Без этого флага правка закрывалась бы в тот же
                // кадр, в котором открылась, и набрать не удалось бы ничего.
                var everFocused by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter { it.isDigit() || it == '-' }.take(4) },
                    singleLine = true,
                    modifier = Modifier.width(104.dp).focusRequester(focus)
                        .onFocusChanged {
                            if (it.isFocused) everFocused = true
                            else if (everFocused && editing) commitText()
                        },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number, imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { commitText() })
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else {
                // Шаг перетаскивания. 8 dp на единицу — мелкие правки берутся
                // пальцем, а до дальнего конца шкалы всё равно быстрее добраться
                // вводом с клавиатуры, чем протаскиванием.
                val stepPx = with(density) { 8.dp.toPx() }
                // Считаем от значения на момент НАЧАЛА жеста и от общего
                // пройденного расстояния. Прибавлять по единице на каждый шаг
                // внутри обработчика нельзя: value меняется только с
                // перекомпоновкой, поэтому три шага за кадр давали бы
                // (value + 1) трижды — то есть всё те же +1.
                var acc by remember { mutableStateOf(0f) }
                var base by remember { mutableStateOf(value) }
                Text(
                    value.toString() + suffix,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { text = value.toString(); editing = true }
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { d ->
                                acc += d
                                onChange(clamp(base + (acc / stepPx).toInt()))
                            },
                            onDragStarted = { base = value; acc = 0f },
                            onDragStopped = { acc = 0f; onCommit() }
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

        FilledTonalButton(
            onClick = { onChange(clamp(value + 1)); onCommit() },
            enabled = value < range.last,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(44.dp)
        ) { Text("+", fontSize = 20.sp) }
    }
}

@Composable
private fun SliderRow(
    label: String, valueText: String,
    value: Float, min: Float, max: Float, steps: Int,
    onChange: (Float) -> Unit, onCommit: () -> Unit
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = min..max,
            steps = (steps - 1).coerceAtLeast(0)
        )
    }
}

@Composable
internal fun NumberDialog(
    title: String, body: String, initial: Int, unit: String,
    min: Int, max: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true, suffix = { Text(unit) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave((text.toIntOrNull() ?: initial).coerceIn(min, max))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RpmDialog(on: Int, off: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var a by remember { mutableStateOf(on.toString()) }
    var b by remember { mutableStateOf(off.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start / stop speed") },
        text = {
            Column {
                Text(
                    "Below the stop threshold the wheel turns too slowly for the image " +
                        "to hold together, so the arms are powered down. The two values " +
                        "must differ — without that gap the picture would flicker on and " +
                        "off at the boundary.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(a, { a = it }, label = { Text("Start") },
                    suffix = { Text("rpm") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(b, { b = it }, label = { Text("Stop") },
                    suffix = { Text("rpm") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val nOn = (a.toIntOrNull() ?: on).coerceIn(30, 600)
                // Разрыв в 5 об/мин обязателен, как и на веб-странице: без
                // гистерезиса картинка мигает на самом пороге.
                val nOff = (b.toIntOrNull() ?: off).coerceIn(20, nOn - 5)
                onSave(nOn, nOff)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
