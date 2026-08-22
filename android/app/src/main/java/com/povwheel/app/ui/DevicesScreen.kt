package com.povwheel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.povwheel.app.WheelEntry
import com.povwheel.app.WheelVm
import com.povwheel.app.ble.Link

/**
 * Список колёс.
 *
 * Экран подписан на ОДИН поток — [WheelVm.wheels]. Это не стилистика: раньше
 * строка собиралась из трёх источников, и состояние соединения читалось так:
 *
 *     connected.firstOrNull { it.address == d.address }?.link?.collectAsState()
 *
 * то есть composable-вызов происходил условно — только когда соединение уже
 * есть. Появление и исчезновение клиента меняли состав вызовов внутри элемента
 * списка, а этого Compose не допускает: строка переставала перерисовываться и
 * показывала состояние, которого давно нет. Теперь всё сведение живёт во
 * ViewModel, а здесь остаётся чистая отрисовка готового значения.
 */
@Composable
fun DevicesScreen(vm: WheelVm) {
    val wheels by vm.wheels.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val connected by vm.connected.collectAsState()
    val mirror by vm.mirrorAll.collectAsState()

    // Поиск идёт, пока открыт этот экран, и снимается уходом с него, а не
    // таймером на пятнадцать секунд: колесо обычно включают уже после того,
    // как достали телефон, и к этому моменту прежний поиск успевал закончиться.
    DisposableEffect(Unit) {
        vm.startScan()
        onDispose { vm.stopScan() }
    }

    val live = wheels.count { it.link == Link.Ready }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "POV Wheel",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when {
                        live > 0 -> "" + live + " connected · " + wheels.size + " known"
                        wheels.isEmpty() -> if (scanning) "Scanning…" else "Not scanning"
                        else -> "Tap a wheel to connect"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (scanning) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
            }
            OutlinedButton(onClick = { if (scanning) vm.stopScan() else vm.startScan() }) {
                Text(if (scanning) "Stop" else "Scan")
            }
        }

        Spacer(Modifier.height(12.dp))

        if (connected.size > 1) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mirror to all wheels", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Playback, effects and settings go to every connected wheel at once.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = mirror, onCheckedChange = { vm.setMirror(it) })
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (wheels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No wheels found yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Give the wheel a shake to wake it — it sleeps after a minute idle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(wheels, key = { it.address }) { w -> DeviceRow(vm, w) }
            }
        }
    }
}

@Composable
private fun DeviceRow(vm: WheelVm, w: WheelEntry) {
    val dot = when (w.link) {
        Link.Ready -> Ok
        Link.Connecting -> Warn
        Link.Error -> Danger
        else -> if (w.stale) MaterialTheme.colorScheme.outlineVariant
                else MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Одна строка — одно состояние. Ровно то, чего не хватало: видно и что
    // колесо рядом, и что с ним сейчас происходит, и почему не подключилось.
    val status: String = when (w.link) {
        Link.Ready -> buildString {
            append(w.soc).append("% battery")
            if (w.rpm > 1f) append(" · ").append(w.rpm.toInt()).append(" rpm")
            if (w.usb) append(" · USB")
            if (w.playing.isNotEmpty()) append(" · ").append(w.playing)
        }
        Link.Connecting -> "connecting…"
        Link.Error -> w.error ?: "connection failed"
        else -> when {
            w.seenAgo == Long.MAX_VALUE -> "not in range"
            w.stale -> "last seen " + (w.seenAgo / 1000) + "s ago"
            w.rssi == Int.MIN_VALUE -> w.address
            else -> w.address + "  ·  " + w.rssi + " dBm"
        }
    }

    Card(
        Modifier.fillMaxWidth().clickable { vm.connect(w.address, w.name) },
        colors = CardDefaults.cardColors(
            containerColor = if (w.link == Link.Ready) MaterialTheme.colorScheme.surfaceVariant
                             else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (w.link == Link.Connecting) {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 2.dp, color = Warn)
            } else {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    w.name,
                    fontWeight = FontWeight.SemiBold,
                    color = if (w.stale && w.link != Link.Ready)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (w.link == Link.Error) Danger
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (w.link) {
                Link.Ready -> {
                    TextButton(onClick = { vm.selectWheel(w.address) }) { Text("Open") }
                    TextButton(onClick = { vm.disconnect(w.address) }) { Text("Drop") }
                }
                Link.Connecting -> {
                    TextButton(onClick = { vm.disconnect(w.address) }) { Text("Cancel") }
                }
                else -> {
                    TextButton(onClick = { vm.connect(w.address, w.name) }) { Text("Connect") }
                    if (w.known) TextButton(onClick = { vm.forget(w.address) }) { Text("Forget") }
                }
            }
        }
    }
}
