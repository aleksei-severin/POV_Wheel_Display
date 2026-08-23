package com.povwheel.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.unit.sp
import com.povwheel.app.WheelVm
import com.povwheel.app.ble.DevFile
import com.povwheel.app.ble.Link
import com.povwheel.app.ble.Proto
import com.povwheel.app.ble.Settings
import com.povwheel.app.ble.Tele
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val TABS = listOf("Library", "Display", "Tuning", "Effects", "Log")

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
    var tab by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Header(vm, tele, link)
        Hero(vm, tele)

        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            TABS.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> LibraryTab(vm, tele)
                1 -> DisplayTab(vm, tele)
                2 -> TuningTab(vm)
                3 -> EffectsTab(vm, tele)
                else -> LogTab(vm)
            }
        }
    }
}

// ------------------------------------------------------------------- обвязка

@Composable
private fun Header(vm: WheelVm, tele: Tele, link: Link) {
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { vm.current.value = null }) { Text("‹ Wheels") }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                (if (link == Link.Ready) "Online" else "Offline") +
                    (client?.hello?.fw?.let { "  ·  " + it } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (link == Link.Ready) Ok else Danger
            )
        }
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (link == Link.Ready) Ok else Danger)
        )
    }
}

@Composable
private fun Hero(vm: WheelVm, tele: Tele) {
    var showCirc by remember { mutableStateOf(false) }
    var showRpm by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(Modifier.weight(1f)) {
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
                if (tele.rpm > 0f && tele.dir < 0) {
                    Text("· reverse", style = MaterialTheme.typography.bodySmall,
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

        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(12.dp)) {
                Label("BATTERY")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        tele.soc.toString() + "%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            tele.soc < 15 -> Danger
                            tele.soc < 35 -> Warn
                            else -> Ok
                        }
                    )
                    Text(
                        "  " + String.format("%.2f", tele.vbatMv / 1000f) + " V",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "USB " + String.format("%.2f", tele.vusbMv / 1000f) + " V",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
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

// ---------------------------------------------------------------- Библиотека

@Composable
private fun LibraryTab(vm: WheelVm, tele: Tele) {
    val files by vm.files.collectAsState()
    val fs by vm.fsInfo.collectAsState()
    val connected by vm.connected.collectAsState()
    val mirror by vm.mirrorAll.collectAsState()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    // Значение с устройства — источник истины. Пока оно не приехало (интервал 0),
    // держим последнее показанное: иначе регулятор дёргался бы на каждом пакете
    // телеметрии, приходящем раз в полсекунды.
    var albumSecs by rememberSaveable { mutableStateOf(10) }
    var albumTouched by rememberSaveable { mutableStateOf(false) }
    // Сменили колесо — снова слушаем устройство, а не помним чужую цифру.
    // У каждого колеса свой интервал, и показывать здесь настройку соседнего
    // тем вреднее, что она применяется сразу: первое же касание навязало бы её.
    val currentWheel by vm.current.collectAsState()
    LaunchedEffect(currentWheel) { albumTouched = false }
    LaunchedEffect(tele.slideSecs, albumTouched) {
        if (tele.slideSecs in 1..300 && !albumTouched) albumSecs = tele.slideSecs
    }

    // Интервал применяется СРАЗУ, без стоп/старта. Устройство при уже идущем
    // слайдшоу меняет только интервал и не трогает текущий индекс (OP_ALBUM),
    // так что картинка на ободе от этого не перескакивает.
    // Задержка — чтобы прокрутка колеса не сыпала командой на каждое деление.
    LaunchedEffect(albumSecs, tele.slideshow) {
        if (albumTouched && tele.slideshow) {
            kotlinx.coroutines.delay(350)
            vm.album(true, albumSecs * 1000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {

        UploadPanel(vm)

        Spacer(Modifier.height(12.dp))

        if (connected.size > 1) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Mirror to all " + connected.size + " wheels", Modifier.weight(1f))
                    Switch(checked = mirror, onCheckedChange = { vm.setMirror(it) })
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Slideshow", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Each file for", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (tele.slideshow) { vm.album(false, 0); vm.say("Slideshow stopped") }
                        else {
                            albumTouched = true
                            vm.album(true, albumSecs * 1000)
                            vm.say("Slideshow started")
                        }
                    }) { Text(if (tele.slideshow) "⏹ Stop" else "⏩ Start") }
                }
                Spacer(Modifier.height(4.dp))
                NumberSpinner(
                    value = albumSecs,
                    range = 1..300,
                    suffix = " s",
                    modifier = Modifier.fillMaxWidth(),
                    onChange = { albumTouched = true; albumSecs = it }
                )
                if (tele.slideshow) {
                    Text(
                        "Applies straight away — no need to stop and start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text("Library", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Text("Tap a row to play it on the wheel.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        if (files.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing stored yet.")
                    Text("Upload a picture to get started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { f ->
                    FileRow(
                        vm, f,
                        playing = tele.play && tele.file == f.name,
                        armed = confirmDelete == f.name,
                        onArm = { confirmDelete = f.name },
                        onCancel = { confirmDelete = null },
                        onDelete = { vm.delete(f.name); confirmDelete = null }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Индикатор хранилища. Флеш ограничивает, сколько файлов влезет; PSRAM —
        // какой длины может быть одна анимация, ведь играет она целиком из ОЗУ.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Storage (flash)", fontWeight = FontWeight.SemiBold)
                val totalMb = fs.total / 1048576.0
                val freeMb = fs.free / 1048576.0
                Text(String.format("%.1f MB free of %.1f MB", freeMb, totalMb),
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (fs.total > 0) (fs.used.toFloat() / fs.total) else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Animations play from RAM, not from storage — " +
                        String.format("%.1f", fs.psramFree / 1048576.0) +
                        " MB of RAM free, enough for about " + fs.maxFrames +
                        " frames (~" + (fs.maxFrames / 10) + " s at 10 fps).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FileRow(
    vm: WheelVm, f: DevFile, playing: Boolean, armed: Boolean,
    onArm: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit
) {
    var thumb by remember(f.name + f.size) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(f.name, f.size) {
        val p = vm.thumb(f)
        if (p != null) thumb = WheelThumb.render(p, 96)
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (playing) Ok.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface
        )
    ) {
        if (armed) {
            Column(Modifier.padding(12.dp)) {
                Text("Delete “" + f.pretty + "”?")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = Danger)
                    ) { Text("Delete") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        } else {
            Row(
                Modifier.padding(10.dp).clickable { vm.play(f.name) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    thumb?.let { Image(it.asImageBitmap(), null, Modifier.size(48.dp).clip(CircleShape)) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.pretty, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        f.kind + "  ·  " + (if (f.frames > 1) f.frames.toString() + " frames" else "still image") +
                            "  ·  " + fmtSize(f.size),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.play(f.name) }) { Text("▶") }
                IconButton(onClick = onArm) { Text("✕") }
            }
        }
    }
}

private fun fmtSize(b: Long): String =
    if (b >= 1048576) String.format("%.1f MB", b / 1048576.0)
    else (b / 1024).toString() + " kB"

// -------------------------------------------------------------------- Экран

@Composable
private fun DisplayTab(vm: WheelVm, tele: Tele) {
    val s by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {

        Text("Brightness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "The device dims itself in the dark and brightens in daylight. " +
                "You set the range it is allowed to use.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        SliderRow(
            "Minimum brightness", s.bmin.toString() + " / 31",
            s.bmin.toFloat(), 1f, 31f, 30,
            onChange = { vm.settings.value = s.copy(bmin = it.roundToInt().coerceAtMost(s.bmax)) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() },
            reverseFill = true
        )
        SliderRow(
            "Maximum brightness", s.bmax.toString() + " / 31",
            s.bmax.toFloat(), 1f, 31f, 30,
            onChange = { vm.settings.value = s.copy(bmax = it.roundToInt().coerceAtLeast(s.bmin)) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() }
        )
        Text(
            "Right now the wheel runs at " + tele.effBri + " (of 31), ambient light " +
                tele.lux + " lx.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Divider(Modifier.padding(vertical = 14.dp))

        SliderRow(
            "Power limit", (s.ablX10 / 10).toString() + "%   (now drawing " + tele.ablRms + "%)",
            (s.ablX10 / 10).toFloat(), 0f, 100f, 100,
            onChange = { vm.settings.value = s.copy(ablX10 = (it.roundToInt() * 10)) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() }
        )
        Text(
            "Caps how much current all 528 LEDs may draw together. Lower it to make " +
                "the battery last longer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Divider(Modifier.padding(vertical = 14.dp))

        // Спиннер, а не ползунок: на 360 положениях один пиксель дорожки стоит
        // больше градуса, и попасть пальцем в нужный было делом случая, тогда
        // как «поставить картинку ровно» — это правка на единицы градусов.
        Text("Magnet position", style = MaterialTheme.typography.bodyMedium)
        NumberSpinner(
            value = s.angle,
            range = 0..360,
            suffix = "°",
            modifier = Modifier.fillMaxWidth(),
            onChange = { vm.settings.value = s.copy(angle = it) },
            onCommit = { vm.pushSettings(vm.settings.value); vm.saveSettings() }
        )
        Text("Use to stand the animation upright.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Divider(Modifier.padding(vertical = 14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Arm order — flip", fontWeight = FontWeight.SemiBold)
                Text(
                    "If the image breaks into shuffled 60° wedges, turn this on. " +
                        "Changing it resets the Hall sensor calibration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = s.armReverse != 0,
                onCheckedChange = {
                    val n = s.copy(armReverse = if (it) 1 else 0)
                    vm.pushSettings(n); vm.saveSettings()
                }
            )
        }

        Divider(Modifier.padding(vertical = 14.dp))

        Text("Name", fontWeight = FontWeight.SemiBold)
        Text(
            "What this wheel is called in the device list. Two wheels on one bike " +
                "are both \"POV-xxxx\" out of the box, and which is which is anyone's guess.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        run {
            val addr by vm.current.collectAsState()
            val wheels by vm.wheels.collectAsState()
            // Начальное значение — из общего списка колёс, а НЕ из HELLO.
            // HELLO снимается при подключении и переименование его не трогает,
            // так что поле показывало старое имя, стоило уйти с вкладки и
            // вернуться. Дальше поле живёт само и сбрасывается только при смене
            // колеса: перечитывать чаще значило бы затирать набранное.
            val seed = wheels.firstOrNull { it.address == addr }?.name
                ?: vm.currentClient()?.hello?.name ?: ""
            var draft by rememberSaveable(addr) { mutableStateOf(seed) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(Proto.NAME_MAX) },
                    singleLine = true,
                    label = { Text("Display name") },
                    isError = draft.isNotEmpty() && !Proto.nameOk(draft),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.renameCurrent(draft.trim()) { vm.say(it) } },
                    enabled = Proto.nameOk(draft.trim())
                ) { Text("Rename") }
            }
            Text(
                "Latin letters, digits, - and _ , up to " + Proto.NAME_MAX + " characters. " +
                    "Takes effect at once; the phone may keep showing the old name until " +
                    "it scans again. The mDNS/OTA hostname is unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Divider(Modifier.padding(vertical = 14.dp))

        Text("Maintenance", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))

        val fw by vm.fwProgress.collectAsState()
        val fwPicker = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) vm.updateFirmware(uri) { vm.say(it) } }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.reboot() }, enabled = fw == null) { Text("Reboot") }
            OutlinedButton(
                onClick = { vm.wifi(true); vm.say("Wi-Fi is coming up for OTA") },
                enabled = fw == null
            ) { Text("Wi-Fi on") }
            OutlinedButton(
                onClick = { fwPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = fw == null
            ) { Text("Update firmware") }
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
        } else {
            Text(
                "Firmware goes over the same BLE link as animations — pick the " +
                    "firmware.bin your build produced. The wheel checks CRC32 before " +
                    "it switches over, so a corrupted transfer is refused, not installed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Wi-Fi stays off unless you ask for it — that is what keeps the phone on " +
                "mobile data and the wheel off a 100 mA receiver. Turn it on only to " +
                "flash firmware from PlatformIO; it goes away again on the next boot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ Tuning

@Composable
private fun TuningTab(vm: WheelVm) {
    val s by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Colour", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

        Divider(Modifier.padding(vertical = 12.dp))

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
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ Эффекты

private data class Eff(val id: Int, val icon: String, val name: String, val desc: String)

private val EFFECTS = listOf(
    Eff(1, "🏁", "Speed", "Current speed in km/h — green at a crawl, red at the limit you set below."),
    Eff(2, "🔥", "Fire", "Flames rise from the hub and flicker out at the rim."),
    Eff(3, "🌈", "Rainbow", "A spectrum spiral turning against the wheel."),
    Eff(4, "🌀", "Plasma", "Four sine waves interfering — never quite repeats."),
    Eff(5, "💧", "Ripples", "Concentric waves from the centre. Stands perfectly still."),
    Eff(6, "🕑", "Clock", "Numbered dial and three hands, set from your phone.")
)

@Composable
private fun EffectsTab(vm: WheelVm, tele: Tele) {
    var red by remember { mutableStateOf(40) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Effects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "These frames are computed on the wheel instead of being read from a file — " +
                "which is the only way Speed can follow the wheel as you ride. Starting " +
                "one hands the animation memory back; press Stop, or play any file, to " +
                "return to the library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        EFFECTS.forEach { e ->
            val active = tele.effect == e.id
            Card(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable {
                    vm.effect(e.id); vm.say(e.name)
                },
                colors = CardDefaults.cardColors(
                    containerColor = if (active) Accent.copy(alpha = 0.18f)
                                     else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.icon, fontSize = 26.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontWeight = FontWeight.SemiBold)
                        Text(e.desc, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        SliderRow("Speed — fully red at", red.toString() + " km/h",
            red.toFloat(), 5f, 120f, 115,
            onChange = { red = it.roundToInt() },
            onCommit = { vm.speedRed(red) })
        Hint("The digits run from green at a standstill to fully red at this speed. " +
             "Revolutions become km/h through the wheel circumference — tap the km/h " +
             "readout above to set it.")

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { vm.effect(0); vm.say("Effects stopped") },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Danger)
        ) { Text("■ Stop effects") }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------- Лог

@Composable
private fun LogTab(vm: WheelVm) {
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
            Text("Device log", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(lines.size.toString() + " lines", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            TextButton(onClick = { vm.clearLogView() }) { Text("✕ Clear") }
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
                    modifier = Modifier.width(130.dp).focusRequester(focus)
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
                        .padding(horizontal = 14.dp, vertical = 6.dp)
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
    onChange: (Float) -> Unit, onCommit: () -> Unit,
    /**
     * Закрашивать дорожку СПРАВА от ползунка, а не слева.
     *
     * Для нижнего порога это не украшение, а смысл: закрашенное — то, чем
     * регулятор распоряжается. «Минимальная яркость 10» означает, что рабочий
     * диапазон — от десяти и выше, и подсвечена должна быть именно эта часть
     * шкалы. Заливка слева читалась ровно наоборот: будто ограничение
     * действует снизу доверху и чем больше значение, тем больше «занято».
     */
    reverseFill: Boolean = false
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        // Направление заливки меняется ПОДМЕНОЙ РОЛЕЙ ЦВЕТОВ, а не своей
        // отрисовкой дорожки: стандартная дорожка и так красит две половины
        // этими двумя цветами, поэтому засечки, отключённое состояние и тема
        // остаются штатными и переписывать нечего.
        val colors = if (!reverseFill) SliderDefaults.colors()
                     else SliderDefaults.colors(
                         activeTrackColor   = MaterialTheme.colorScheme.surfaceVariant,
                         activeTickColor    = MaterialTheme.colorScheme.onSurfaceVariant,
                         inactiveTrackColor = MaterialTheme.colorScheme.primary,
                         inactiveTickColor  = MaterialTheme.colorScheme.onPrimary
                     )
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = min..max,
            steps = (steps - 1).coerceAtLeast(0),
            colors = colors
        )
    }
}

@Composable
private fun NumberDialog(
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
