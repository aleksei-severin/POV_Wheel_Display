package com.povwheel.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.povwheel.app.WheelEntry
import com.povwheel.app.WheelVm
import com.povwheel.app.ble.BleClient
import com.povwheel.app.ble.Link
import com.povwheel.app.ble.Proto
import com.povwheel.app.ble.Settings
import com.povwheel.app.ble.Tele
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// Вкладок больше нет — один экран: сверху библиотека, за ней настройки дисплея
// той же лентой. Меню Tuning свёрнуто в секцию «Colour». Лог — окно из
// Maintenance → Log.

@Composable
fun DeviceScreen(vm: WheelVm) {
    val wheels by vm.wheels.collectAsState()
    val current by vm.current.collectAsState()
    val client = vm.client(current)
    val curEntry = wheels.firstOrNull { it.address == current }

    // Открываем последнее колесо сразу, а не ждём тапа по строке.
    LaunchedEffect(Unit) { if (vm.current.value == null) vm.openLastWheel() }

    // Поиск идёт всё время, пока открыт экран, и снимается уходом с него: колесо
    // обычно будят уже после того, как достали телефон. Кроме времени заливки —
    // она делит одно радио с LOW_LATENCY-поиском.
    val upBusy by vm.upBusy.collectAsState()
    DisposableEffect(upBusy) {
        if (!upBusy) vm.startScan()
        onDispose { vm.stopScan() }
    }

    // Свайп влево/вправо по контенту — следующее/предыдущее колесо по кругу.
    val canSwipe = wheels.count { it.reachable } >= 2
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }

    Column(Modifier.fillMaxSize()) {
        WheelStrip(vm, wheels, current)
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Box(
            Modifier
                .weight(1f)
                .then(if (!canSwipe) Modifier else Modifier.pointerInput(Unit) {
                    // acc — реальный путь пальца (решение о переключении),
                    // dragX — только картинка (демпфированный сдвиг контента).
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f },
                        onDragEnd = {
                            val threshold = 100.dp.toPx()
                            val w = size.width.toFloat()
                            when {
                                // Свайп влево — следующее колесо; новый контент
                                // въезжает справа (snapTo(+w) → animateTo(0)).
                                acc <= -threshold -> {
                                    vm.cycleWheel(1)
                                    scope.launch { dragX.snapTo(w); dragX.animateTo(0f) }
                                }
                                acc >= threshold -> {
                                    vm.cycleWheel(-1)
                                    scope.launch { dragX.snapTo(-w); dragX.animateTo(0f) }
                                }
                                else -> scope.launch { dragX.animateTo(0f) }
                            }
                        },
                        onDragCancel = { scope.launch { dragX.animateTo(0f) } }
                    ) { change, drag ->
                        change.consume()
                        acc += drag
                        // Настоящей «второй страницы» под контентом нет —
                        // движение лишь показывает, что экран свайпается.
                        val target = acc * 0.5f
                        scope.launch { dragX.snapTo(target) }
                    }
                })
                .offset { IntOffset(dragX.value.roundToInt(), 0) }
        ) {
            if (client != null && curEntry?.link == Link.Ready) {
                key(current) { ConnectedContent(vm, client) }
            } else {
                IdleContent(wheels, curEntry)
            }
        }
    }
}

@Composable
private fun ConnectedContent(vm: WheelVm, client: BleClient) {
    val link by client.link.collectAsState()
    val tele by client.tele.collectAsState()
    MainContent(vm, tele, link == Link.Ready)
}

/**
 * Единый экран: плитка библиотеки ([LibraryTab]) плюс блоки настроек дисплея,
 * добавленные в ту же сетку full-span элементами — всё скроллится вместе.
 */
@OptIn(ExperimentalMaterial3Api::class)   // LocalMinimumInteractiveComponentEnforcement
@Composable
private fun MainContent(vm: WheelVm, tele: Tele, online: Boolean) {
    val s by vm.settings.collectAsState()
    val magLocked by vm.magnetLocked.collectAsState()
    var colourOpen by rememberSaveable { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val fw by vm.fwProgress.collectAsState()
    val fwPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.updateFirmware(uri) { vm.say(it) } }

    LibraryTab(
        vm, tele,
        leadingItems = {
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) { Hero(vm, tele, online) }
        }
    ) {
        item(key = "brightness", span = { GridItemSpan(maxLineSpan) }) {
            SettingCard { AutoBrightnessRange(vm, s, tele) }
        }
        item(key = "magnet", span = { GridItemSpan(maxLineSpan) }) {
            SettingCard {
                // Заголовок и компактный спиннер в одну строку — блок по высоте
                // совпадает со свёрнутым «Colour». Спиннер, а не ползунок: на 360
                // положениях один пиксель дорожки стоит больше градуса, а
                // «поставить картинку ровно» — правка на единицы градусов.
                // SpaceBetween ставит замок ровно посередине между заголовком и
                // спиннером — равные зазоры слева и справа от него.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Magnet Position", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    // Замок: тап — закрыть и залочить значение; чтобы открыть —
                    // держать 1 с (кольцо вокруг замка показывает прогресс).
                    MagnetLock(
                        locked = magLocked,
                        onLock = { vm.setMagnetLocked(true) },
                        onUnlock = { vm.setMagnetLocked(false) },
                        onHint = { vm.say("Hold the lock 1 s to unlock") }
                    )
                    NumberSpinner(
                        value = s.angle,
                        range = 0..359,          // 360 == 0, поэтому предел — 359
                        suffix = "°",
                        modifier = Modifier.width(120.dp),
                        dense = true,
                        wrap = true,              // прокрутка бесконечная, без упора в 0/359
                        enabled = !magLocked,
                        // Применяем на дисплей ПРЯМО во время перетаскивания
                        // (throttled, только на открытое колесо, без записи в
                        // NVS) — калибровать вслепую до отпускания пальца неудобно.
                        onChange = { vm.pushSettingsLive(s.copy(angle = it)) },
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
                        .tapClickable { colourOpen = !colourOpen }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Color Correction", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        "▾",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .rotate(if (colourOpen) 0f else -90f)
                    )
                }
                if (colourOpen) {
                    Spacer(Modifier.height(4.dp))
                    ColourControls(vm)
                }
            }
        }
        item(key = "maint", span = { GridItemSpan(maxLineSpan) }) {
            SettingCard {
                // Только кнопки, без заголовка — карточка по высоте как «Magnet
                // Position». «OFF» — уход в транспортный режим: колесо гаснет и
                // до удержания кнопки не проснётся ни по тряске, ни по BLE.
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentEnforcement provides false
                ) {
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
                TextButton(onClick = hapticClick {
                    confirmOff = false
                    vm.powerOff(); vm.say("Powering off")
                }) { Text("Power off") }
            },
            dismissButton = { TextButton(onClick = hapticClick { confirmOff = false }) { Text("Cancel") } }
        )
    }

    if (showLog) LogDialog(vm) { showLog = false }
}

// --------------------------------------------------------------- строка колёс

/**
 * Шапка экрана: доступные дисплеи именами в одну строку с прокруткой вправо.
 * Тап — открыть/подключиться ([WheelVm.openWheel]), долгое удержание — диалог
 * переименования/забыть. Отдельного экрана-списка больше нет.
 *
 * Подписка ТОЛЬКО на [WheelVm.wheels] — тот же приём, что был на экране-списке:
 * состояние связи каждого колеса уже сведено во ViewModel, и здесь нет
 * условных `collectAsState` на клиенте (их появление/исчезновение меняло бы
 * состав composable-вызовов, чего Compose не допускает).
 */
@Composable
private fun WheelStrip(vm: WheelVm, wheels: List<WheelEntry>, current: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (wheels.isEmpty()) {
            Text(
                "Searching for wheels…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        wheels.forEach { w -> WheelName(vm, w, selected = w.address == current) }
    }
}

@Composable
private fun WheelName(vm: WheelVm, w: WheelEntry, selected: Boolean) {
    var renaming by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    // К колесу можно подключиться: оно в эфире, но связи ещё нет.
    val connectable = w.link != Link.Ready && w.link != Link.Connecting &&
        !w.stale && w.rssi != Int.MIN_VALUE

    val nameColor = when {
        selected             -> cs.primary
        w.link == Link.Ready -> cs.onSurface
        connectable          -> cs.onSurface
        else                 -> cs.onSurfaceVariant   // серый: неактивно / не в эфире
    }

    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .then(if (selected) Modifier.background(cs.primary.copy(alpha = 0.14f)) else Modifier)
            .tapCombinedClickable(
                onClick = { vm.openWheel(w.address, w.name) },
                onLongClick = { renaming = true }
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        when {
            w.link == Link.Connecting ->
                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = Warn)
            w.link == Link.Ready ->
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (selected) cs.primary else Ok))
            // значок «можно подключиться» — полое кольцо в акцентном цвете
            connectable ->
                Box(Modifier.size(8.dp).clip(CircleShape).border(1.5.dp, Accent, CircleShape))
            else -> Spacer(Modifier.size(0.dp))
        }
        Text(
            w.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = nameColor,
            maxLines = 1,
            softWrap = false
        )
    }

    if (renaming) RenameDialog(
        current = w.name,
        connected = w.link == Link.Ready,
        onDismiss = { renaming = false },
        onForget = { vm.forget(w.address); renaming = false },
        onSave = { name -> vm.renameWheel(w.address, name) { vm.say(it) }; renaming = false }
    )
}

@Composable
private fun IdleContent(wheels: List<WheelEntry>, cur: WheelEntry?) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                cur?.link == Link.Connecting -> {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Connecting to " + cur.name + "…")
                }
                cur != null -> {
                    // Открытое колесо отвалилось (сон, уехало из радиуса).
                    // Переподключение крутится само.
                    Text(cur.name + " is offline", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Reconnecting automatically — shake it or click any button to wake it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                wheels.any { it.link == Link.Ready || (!it.stale && it.rssi != Int.MIN_VALUE) } ->
                    Text("Tap a wheel above to connect", style = MaterialTheme.typography.titleMedium)
                else -> {
                    Text("Looking for wheels…", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Shake or click any button to wake up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    current: String,
    connected: Boolean,
    onDismiss: () -> Unit,
    onForget: () -> Unit,
    onSave: (String) -> Unit
) {
    var draft by remember { mutableStateOf(current) }
    val nameOk = Proto.nameOk(draft.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current) },
        text = {
            Column {
                Text(
                    if (connected)
                        "Rename this wheel. Two wheels on one bike are both \"POV-xxxx\" " +
                            "out of the box, and which is which is anyone's guess."
                    else
                        "Connect to this wheel first to rename it.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(Proto.NAME_MAX) },
                    singleLine = true,
                    enabled = connected,
                    isError = connected && draft.isNotEmpty() && !nameOk,
                    label = { Text("Display name") }
                )
                Text(
                    "Latin letters, digits, - and _ , up to " + Proto.NAME_MAX + " characters. " +
                        "Takes effect at once. The mDNS/OTA hostname is unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = hapticClick(onForget),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("Forget this wheel", color = Danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = connected && nameOk,
                onClick = hapticClick { onSave(draft.trim()) }
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = hapticClick(onDismiss)) { Text("Cancel") } }
    )
}

// Высота строки-заголовка карточек DISPLAY/BATTERY. Фиксирована, чтобы бейдж
// статуса (LOW/Charging/…) не растягивал карточку вниз и числа в обеих
// карточках стояли на одном уровне.
private val HERO_HEADER_H = 22.dp

@Composable
private fun Hero(vm: WheelVm, tele: Tele, online: Boolean) {
    var showCirc by remember { mutableStateOf(false) }
    var showRpm by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()

    // Нет связи или телеметрия ещё не пришла — прочерки, как у скорости/оборотов,
    // а не нули напряжения и процентов.
    val battKnown = online && tele.vbatMv > 0
    // Короткий статус справа от «BATTERY». Discharging (просто разряд) не
    // показываем — только зарядку, «Charged», «Low» (яркость урезана защитой)
    // и «Empty» (дисплей выключен по разряду).
    val battBadge: Pair<String, Color?>? = when {
        !battKnown -> "Offline" to null
        tele.cutoff -> "Empty" to Danger
        tele.ablCap < 100 -> "Low" to Warn
        tele.chg == 1 -> "Charging" to Accent
        tele.chg == 2 -> "Charged" to Ok
        else -> null
    }

    // Обе карточки — две строки (метка + число), вдвое ниже прежнего: кнопку
    // Start/Stop и напряжение USB убрали. Горизонтальные поля даёт сетка
    // (contentPadding), своего padding у ряда нет.
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Строка-заголовок фиксированной высоты — как у BATTERY с бейджем,
                // чтобы числа в обеих карточках стояли на одном уровне.
                Box(Modifier.height(HERO_HEADER_H), contentAlignment = Alignment.CenterStart) {
                    Label("DISPLAY")
                }
                Spacer(Modifier.height(2.dp))
                Row {
                    Text(
                        if (tele.kmh > 0f) String.format("%.1f", tele.kmh) else "--",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alignByBaseline().tapClickable { showCirc = true }
                    )
                    Text(" km/h", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline())
                    Text(
                        if (tele.rpm > 0f) tele.rpm.roundToInt().toString() else "--",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.alignByBaseline().padding(start = 10.dp)
                            .tapClickable { showRpm = true }
                    )
                    Text(" rpm", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline())
                }
            }
        }

        Card(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Фиксированная высота: бейдж (LOW/Charging/…) появляется и
                // исчезает, не меняя высоту карточки.
                Row(
                    Modifier.fillMaxWidth().height(HERO_HEADER_H),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Label("BATTERY")
                    Spacer(Modifier.weight(1f))
                    battBadge?.let { (t, c) -> Badge2(t, c) }
                }
                Spacer(Modifier.height(2.dp))
                Row {
                    Text(
                        if (battKnown) tele.soc.toString() + "%" else "--",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alignByBaseline(),
                        color = when {
                            !battKnown -> MaterialTheme.colorScheme.onSurfaceVariant
                            tele.soc < 15 -> Danger
                            tele.soc < 35 -> Warn
                            else -> Ok
                        }
                    )
                    Text(
                        (if (battKnown) String.format("%.2f", tele.vbatMv / 1000f) else "--") + " V",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline().padding(start = 6.dp)
                    )
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
 *
 * Шкала для пользователя 1..25 линейно натянута на реальный brightness-байт
 * [BRI_LO]..[BRI_HI]: user 1 = байт 6 (ниже лента едва различима даже в
 * темноте), user 25 = байт 31 (максимум 5-битного поля тока SK9822).
 */
private const val BRI_LO = 6
private const val BRI_HI = 31
private const val BRI_U_MAX = 25

private fun briToUser(b: Int): Int =
    (1 + ((b - BRI_LO) * (BRI_U_MAX - 1).toFloat() / (BRI_HI - BRI_LO)).roundToInt())
        .coerceIn(1, BRI_U_MAX)

private fun userToBri(u: Int): Int =
    (BRI_LO + ((u - 1) * (BRI_HI - BRI_LO).toFloat() / (BRI_U_MAX - 1)).roundToInt())
        .coerceIn(BRI_LO, BRI_HI)

@Composable
private fun AutoBrightnessRange(vm: WheelVm, s: Settings, tele: Tele) {
    val lo = briToUser(minOf(s.bmin, s.bmax))
    val hi = briToUser(maxOf(s.bmin, s.bmax))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Auto Brightness Range", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(lo.toString() + "–" + hi + " / " + BRI_U_MAX,
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Дорожка слайдера отбита от краёв на радиус бегунка — метку считаем
        // в тех же границах, иначе она разъедется с делениями.
        val inset = 10.dp
        StepRangeSlider(
            lo = lo, hi = hi, valueRange = 1..BRI_U_MAX,
            onChange = { a, b ->
                vm.settings.value = s.copy(
                    bmin = userToBri(minOf(a, b)),
                    bmax = userToBri(maxOf(a, b))
                )
            },
            onChangeFinished = { vm.pushSettings(vm.settings.value); vm.saveSettings() },
            modifier = Modifier.fillMaxWidth()
        )
        // Оранжевая метка — текущая эффективная яркость на пользовательской шкале;
        // прячем ниже пола (лента выключена или очень тускло) — метка у левого
        // края читалась бы как «яркость 1».
        if (tele.effBri in BRI_LO..BRI_HI) {
            val frac = ((tele.effBri - BRI_LO).toFloat() / (BRI_HI - BRI_LO)).coerceIn(0f, 1f)
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

/**
 * Ползунок-диапазон с двумя бегунками. В отличие от штатного `RangeSlider` не
 * прыгает к точке касания: бегунок трогается, только если палец опустился
 * прямо на него и повёл. Если касание не по бегунку — жест не перехватывается,
 * и лента под ним свободно скроллится, даже когда палец пошёл по самой дорожке.
 * Так же ведут себя ползунки в «Colour» (штатный `Slider` двигается лишь по
 * настоящему тапу, а не по касанию-протяжке).
 */
@Composable
private fun StepRangeSlider(
    lo: Int,
    hi: Int,
    valueRange: IntRange,
    onChange: (Int, Int) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val view = LocalView.current
    val thumbR = 10.dp
    val trackH = 4.dp
    val grab = 22.dp                    // насколько близко к центру бегунка нужно попасть

    val first = valueRange.first
    val spanV = (valueRange.last - first).coerceAtLeast(1)
    val loS = rememberUpdatedState(lo)
    val hiS = rememberUpdatedState(hi)

    BoxWithConstraints(modifier.fillMaxWidth().height(thumbR * 2 + 12.dp)) {
        val wPx = with(density) { maxWidth.toPx() }
        val insetPx = with(density) { thumbR.toPx() }
        val usable = (wPx - insetPx * 2f).coerceAtLeast(1f)
        val grabPx = with(density) { grab.toPx() }

        fun xOf(v: Int) = insetPx + usable * (v - first) / spanV
        fun vOf(x: Float) = (first + (x - insetPx) / usable * spanV)
            .roundToInt().coerceIn(valueRange.first, valueRange.last)

        Box(
            Modifier.matchParentSize().pointerInput(usable) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val x = down.position.x
                    val dLo = abs(x - xOf(loS.value))
                    val dHi = abs(x - xOf(hiS.value))
                    // Не по бегунку — выходим не трогая событие: пусть скроллится список.
                    if (dLo > grabPx && dHi > grabPx) return@awaitEachGesture
                    // Бегунки на одном делении: какой тянуть — решаем по НАПРАВЛЕНИЮ
                    // первого движения (влево — нижний, вправо — верхний), иначе
                    // при равном расстоянии всегда выигрывал бы нижний и верхний
                    // было не сдвинуть вправо вообще.
                    var movingLo: Boolean? =
                        if (loS.value == hiS.value) null else dLo <= dHi
                    down.consume()
                    var moved = false
                    var lastV = if (movingLo == false) hiS.value else loS.value
                    horizontalDrag(down.id) { ch ->
                        ch.consume()
                        if (movingLo == null) {
                            val dx = ch.position.x - x
                            if (abs(dx) < 4f) return@horizontalDrag   // мало — ждём
                            movingLo = dx < 0f
                        }
                        moved = true
                        val v = vOf(ch.position.x)
                        // Хаптик-щелчок на каждое пройденное деление шкалы.
                        if (v != lastV) { view.tickFeedback(); lastV = v }
                        if (movingLo == true) onChange(v.coerceAtMost(hiS.value), hiS.value)
                        else onChange(loS.value, v.coerceAtLeast(loS.value))
                    }
                    if (moved) onChangeFinished()
                }
            }
        ) {
            Canvas(Modifier.matchParentSize()) {
                val cy = size.height / 2f
                val loX = insetPx + usable * (loS.value - first) / spanV
                val hiX = insetPx + usable * (hiS.value - first) / spanV
                val th = trackH.toPx()
                drawLine(cs.surfaceVariant, Offset(insetPx, cy),
                    Offset(size.width - insetPx, cy), th, StrokeCap.Round)
                drawLine(cs.primary, Offset(loX, cy), Offset(hiX, cy), th, StrokeCap.Round)
                // Деления-точки на фоне, как у ползунков в «Colour» (штатный
                // Slider со `steps`): по одной на каждое положение шкалы.
                val tickR = 1.dp.toPx()
                for (v in first..valueRange.last) {
                    val x = insetPx + usable * (v - first) / spanV
                    val active = x in loX..hiX
                    drawCircle(
                        color = (if (active) cs.onPrimary else cs.onSurfaceVariant).copy(alpha = 0.38f),
                        radius = tickR,
                        center = Offset(x, cy)
                    )
                }
                drawCircle(cs.primary, thumbR.toPx(), Offset(loX, cy))
                drawCircle(cs.primary, thumbR.toPx(), Offset(hiX, cy))
            }
        }
    }
}

@Composable
private fun RowScope.MaintBtn(
    label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit
) {
    OutlinedButton(
        onClick = hapticClick(onClick),
        enabled = enabled,
        modifier = Modifier.weight(1f).height(32.dp),   // как компактный спиннер «Magnet Position»
        contentPadding = PaddingValues(horizontal = 2.dp),
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
        SliderRow("Gamma", String.format("%.1f", s.gammaX100 / 100f),
            s.gammaX100 / 10f, 10f, 50f, 40,
            onChange = { vm.settings.value = s.copy(gammaX100 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() })

        SliderRow("Saturation", String.format("%.1f", s.satX100 / 100f),
            s.satX100 / 10f, 10f, 30f, 20,
            onChange = { vm.settings.value = s.copy(satX100 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() })

        SliderRow("Contrast", (s.contrastX10 / 10).toString() + "%",
            (s.contrastX10 / 10).toFloat(), 0f, 100f, 100,
            onChange = { vm.settings.value = s.copy(contrastX10 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() })

        SliderRow("Red", (s.rgX10 / 10).toString() + "%",
            (s.rgX10 / 10).toFloat(), 0f, 100f, 20,
            onChange = { vm.settings.value = s.copy(rgX10 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() },
            color = Danger)
        SliderRow("Green", (s.ggX10 / 10).toString() + "%",
            (s.ggX10 / 10).toFloat(), 0f, 100f, 20,
            onChange = { vm.settings.value = s.copy(ggX10 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() },
            color = Ok)
        SliderRow("Blue", (s.bgX10 / 10).toString() + "%",
            (s.bgX10 / 10).toFloat(), 0f, 100f, 20,
            onChange = { vm.settings.value = s.copy(bgX10 = it.roundToInt() * 10) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() },
            color = Accent)

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = hapticClick {
                val d = vm.settings.value.copy(
                    gammaX100 = 250, satX100 = 150, contrastX10 = 50,
                    rgX10 = 1000, ggX10 = 800, bgX10 = 1000
                )
                vm.pushSettings(d); vm.saveSettings()
                vm.say("Color correction reset")
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
                onClick = hapticClick {
                    clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                    vm.say("Log copied (" + lines.size + " lines)")
                },
                enabled = lines.isNotEmpty()
            ) { Text("Copy") }
            TextButton(onClick = hapticClick { vm.clearLogView() }) { Text("Clear") }
            TextButton(onClick = hapticClick(onDismiss)) { Text("✕") }
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
private fun Badge2(text: String, color: Color?) {
    val c = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberSpinner(
    value: Int,
    range: IntRange,
    suffix: String = "",
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    wrap: Boolean = false,   // прокрутка по кругу: ниже range.first → range.last и дальше
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
    onCommit: () -> Unit = {}
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    val view = LocalView.current
    LaunchedEffect(enabled) { if (!enabled) editing = false }

    // Компактный режим: всё в одну строку рядом с заголовком карточки.
    val btnSize = if (dense) 32.dp else 44.dp
    val signSize = if (dense) 16.sp else 20.sp
    val valueStyle = if (dense) MaterialTheme.typography.titleMedium
                     else MaterialTheme.typography.headlineSmall
    val dragPadV = if (dense) 2.dp else 6.dp

    // wrap = true: значение ходит по кругу [range.first..range.last] — ниже
    // нижней границы продолжает с верхней и наоборот. Иначе упирается в края.
    fun norm(v: Int): Int =
        if (!wrap) v.coerceIn(range.first, range.last)
        else {
            val n = range.last - range.first + 1
            range.first + ((v - range.first) % n + n) % n
        }

    // Показываемое значение всегда приведено к диапазону — старое значение из
    // NVS (например 360) на экране будет 0.
    val shown = norm(value)

    fun commitText() {
        val v = text.trim().toIntOrNull()
        if (v != null) { onChange(norm(v)); onCommit() }   // мусор и пустое — просто откат
        editing = false
    }

    // Состояние жеста перетаскивания — создаём безусловно (rememberDraggableState —
    // composable), а само перетаскивание глушим через draggable(enabled = …).
    val stepPx = with(density) { 8.dp.toPx() }
    var acc by remember { mutableStateOf(0f) }
    var base by remember { mutableStateOf(shown) }
    var lastEmit by remember { mutableStateOf(shown) }
    val dragState = rememberDraggableState { d ->
        acc += d
        val v = norm(base + (acc / stepPx).toInt())
        if (v != lastEmit) { view.tickFeedback(); lastEmit = v }
        onChange(v)
    }

    val row = @Composable {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = hapticClick { onChange(norm(shown - 1)); onCommit() },
            enabled = enabled && (wrap || shown > range.first),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(btnSize)
        ) { Text("−", fontSize = signSize) }

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (editing && enabled) {
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
                // Значение: тап — правка с клавиатуры, горизонтальное
                // перетаскивание — листание по градусу (шаг 8 dp). База берётся
                // на НАЧАЛЕ жеста от общего пройденного пути, иначе три шага за
                // кадр дали бы (value+1) трижды. Всё глохнет, когда замок закрыт.
                Text(
                    shown.toString() + suffix,
                    style = valueStyle,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Color.Unspecified
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier
                        .tapClickable(enabled = enabled) { text = shown.toString(); editing = true }
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = dragState,
                            enabled = enabled,
                            onDragStarted = { base = shown; acc = 0f; lastEmit = shown },
                            onDragStopped = { acc = 0f; onCommit() }
                        )
                        .padding(horizontal = 8.dp, vertical = dragPadV)
                )
            }
        }

        FilledTonalButton(
            onClick = hapticClick { onChange(norm(shown + 1)); onCommit() },
            enabled = enabled && (wrap || shown < range.last),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(btnSize)
        ) { Text("+", fontSize = signSize) }
    }
    }

    // В компактном режиме отключаем расширение кнопок до 48 dp — иначе строка
    // всё равно была бы во весь минимальный тач-таргет и «в одну строку с
    // Magnet Position» не уместилась бы по высоте свёрнутого «Colour».
    if (dense) {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentEnforcement provides false, content = row
        )
    } else row()
}

/**
 * Замок значения «Magnet Position». Тап по открытому — закрывает и лочит
 * спиннер. Открыть можно только удержанием ≥ 1 с (кольцо вокруг замка тем
 * временем заполняется); короткий тап по закрытому — подсказка [onHint].
 */
@Composable
private fun MagnetLock(
    locked: Boolean,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onHint: () -> Unit
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val hold = remember { Animatable(0f) }
    val cs = MaterialTheme.colorScheme

    // Смена состояния (в т.ч. разблокировка по таймеру, когда жест-корутина
    // обрывается на середине) — кольцо прогресса обнуляем принудительно.
    LaunchedEffect(locked) { hold.snapTo(0f) }

    Box(
        Modifier
            .size(30.dp)
            .pointerInput(locked) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!locked) {
                        if (waitForUpOrCancellation() != null) { view.tapFeedback(); onLock() }
                        return@awaitEachGesture
                    }
                    // Закрыт: разблокировка удержанием 1 с. Отдельный таймер, а
                    // не тайм-аут ожидания up — чтобы отличить «держал 1 с» от
                    // «жест отменён» (палец увело в скролл).
                    scope.launch { hold.snapTo(0f); hold.animateTo(1f, tween(1000, easing = LinearEasing)) }
                    var done = false
                    val timer = scope.launch {
                        delay(1000)
                        done = true
                        view.tickFeedback()
                        onUnlock()
                    }
                    val up = waitForUpOrCancellation()
                    timer.cancel()
                    scope.launch { hold.animateTo(0f, tween(140)) }
                    if (!done && up != null) onHint()   // отпустил рано; отмена — молча
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (hold.value > 0.01f) {
            Canvas(Modifier.matchParentSize().padding(1.dp)) {
                drawArc(
                    color = cs.primary,
                    startAngle = -90f,
                    sweepAngle = 360f * hold.value,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        LockIcon(
            locked = locked,
            tint = if (locked) cs.primary else cs.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
    }
}

/** Пиктограмма навесного замка: закрыт — дужка сомкнута, открыт — правая ножка
 *  отведена. Рисуется в цвете [tint], без эмодзи, как «▾▸−+» рядом. */
@Composable
private fun LockIcon(locked: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = h * 0.13f
        // тело
        val bodyW = w * 0.74f
        val bodyH = h * 0.5f
        val bx = (w - bodyW) / 2f
        val by = h - bodyH
        drawRoundRect(
            color = tint,
            topLeft = Offset(bx, by),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(sw * 1.6f)
        )
        // дужка
        val shW = bodyW * 0.58f
        val sx = (w - shW) / 2f
        val shTop = if (locked) h * 0.09f else 0f
        drawArc(
            color = tint,
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(sx, shTop),
            size = Size(shW, shW),
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )
        val legTop = shTop + shW / 2f
        val legBottom = by + sw * 0.4f
        drawLine(tint, Offset(sx, legTop), Offset(sx, legBottom), strokeWidth = sw, cap = StrokeCap.Round)
        val rightBottom = if (locked) legBottom else legTop + shW * 0.3f
        drawLine(tint, Offset(sx + shW, legTop), Offset(sx + shW, rightBottom), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun SliderRow(
    label: String, valueText: String,
    value: Float, min: Float, max: Float, steps: Int,
    onChange: (Float) -> Unit, onCommit: () -> Unit,
    color: Color? = null   // R/G/B — красим дорожку и подпись в свой цвет
) {
    val view = LocalView.current
    val divs = steps.coerceAtLeast(1)
    // Индекс деления, на котором был бегунок в прошлый раз (−1 — жест ещё не шёл).
    var lastIdx by remember { mutableStateOf(-1) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                color = color ?: Color.Unspecified)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = color ?: Color.Unspecified)
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = {
                // Хаптик-щелчок на каждое пройденное деление, а не на каждый
                // промежуточный кадр перетаскивания.
                val idx = ((it - min) / (max - min) * divs).roundToInt()
                if (idx != lastIdx) {
                    if (lastIdx >= 0) view.tickFeedback()
                    lastIdx = idx
                }
                onChange(it)
            },
            onValueChangeFinished = { lastIdx = -1; onCommit() },
            valueRange = min..max,
            steps = (steps - 1).coerceAtLeast(0),
            colors = if (color != null) SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                activeTickColor = color.copy(alpha = 0.4f)
            ) else SliderDefaults.colors()
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
            TextButton(onClick = hapticClick {
                onSave((text.toIntOrNull() ?: initial).coerceIn(min, max))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = hapticClick(onDismiss)) { Text("Cancel") } }
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
            TextButton(onClick = hapticClick {
                val nOn = (a.toIntOrNull() ?: on).coerceIn(30, 600)
                // Разрыв в 5 об/мин обязателен, как и на веб-странице: без
                // гистерезиса картинка мигает на самом пороге.
                val nOff = (b.toIntOrNull() ?: off).coerceIn(20, nOn - 5)
                onSave(nOn, nOff)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = hapticClick(onDismiss)) { Text("Cancel") } }
    )
}
