package com.povwheel.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.povwheel.app.ui.DeviceScreen
import com.povwheel.app.ui.PovTheme
import com.povwheel.app.ui.hapticClick

class MainActivity : ComponentActivity() {

    private val vm: WheelVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PovTheme {
                val snack = remember { SnackbarHostState() }
                val toast by vm.toast.collectAsState()
                LaunchedEffect(toast) {
                    toast?.let {
                        snack.showSnackbar(it)
                        vm.toast.value = null
                    }
                }
                // Экран держим включённым, пока идёт долгая передача, и делаем
                // это ЗДЕСЬ — выше всех экранов. Заливка живёт во ViewModel и
                // переживает и смену вкладки, и возврат к списку колёс; будь
                // этот эффект внутри экрана устройства, он снялся бы ровно
                // тогда, когда нужнее всего. Погасший экран подвешивает очередь
                // BLE на середине файла, и устройство обрывает передачу по
                // своему сторожу молчания.
                val upBusy by vm.upBusy.collectAsState()
                val fw by vm.fwProgress.collectAsState()
                val view = LocalView.current
                val keepAwake = upBusy || fw != null
                DisposableEffect(keepAwake) {
                    view.keepScreenOn = keepAwake
                    onDispose { view.keepScreenOn = false }
                }

                Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
                    Column(Modifier.fillMaxSize().padding(pad)) {
                        Gate(vm)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // Выход из приложения не должен оставлять открытыми полдюжины соединений.
        if (isFinishing) vm.disconnectAll()
        super.onDestroy()
    }
}

/** Есть ли уже выданное разрешение — чтобы не спрашивать заново на каждом запуске. */
private fun hasPerms(ctx: Context, perms: Array<String>): Boolean =
    perms.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * До Android 12 поиск устройств BLE идёт через разрешение на местоположение, и
 * системе мало самого разрешения — нужен ВКЛЮЧЁННЫЙ поставщик геоданных. При
 * выключенном поиск не падает и не сообщает об ошибке: он просто не находит
 * ничего и не вызывает onScanFailed. Снаружи это выглядит как «приложение не
 * видит колесо», и понять причину без подсказки невозможно.
 */
private fun locationOn(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
    return try {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { true }
}

/** Спрашивает разрешения для Bluetooth и передаёт управление рабочим экранам. */
@Composable
private fun Gate(vm: WheelVm) {
    val ctx = LocalContext.current

    val needed = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Начальное значение — реальное состояние, а не false. Иначе приложение
    // спрашивало разрешение заново при каждом запуске, а после двух отказов
    // система отвечает молча и мгновенно — экран так и оставался заглушкой.
    var granted by remember { mutableStateOf(hasPerms(ctx, needed)) }
    var asked by remember { mutableStateOf(false) }
    var btOn by remember { mutableStateOf(vm.bluetoothReady()) }
    var locOn by remember { mutableStateOf(locationOn(ctx)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        granted = res.values.all { it }
        asked = true
    }

    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { btOn = vm.bluetoothReady() }

    // Возврат из системных настроек (разрешения, Bluetooth, геоданные) ничего
    // не присылает — состояние надо перечитать самим при возвращении в фокус.
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        granted = hasPerms(ctx, needed)
        locOn = locationOn(ctx)
        btOn = vm.bluetoothReady()
    }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(needed) }

    // Bluetooth могут выключить прямо во время работы. Без этого пользователь
    // оставался на списке колёс, где каждое действие молча не срабатывает.
    DisposableEffect(Unit) {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { btOn = vm.bluetoothReady() }
        }
        ContextCompat.registerReceiver(
            ctx, rx, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { try { ctx.unregisterReceiver(rx) } catch (_: Exception) {} }
    }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Bluetooth permission needed", style = MaterialTheme.typography.titleLarge)
            Text(
                "The wheels are controlled over Bluetooth Low Energy. " +
                "Nothing is uploaded anywhere and your location is never used.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = hapticClick { permLauncher.launch(needed) }) { Text("Grant permission") }
            // После второго отказа система показывает диалог мгновенно и
            // впустую — единственный оставшийся путь ведёт в настройки.
            if (asked) {
                Text(
                    "Denied twice? Android stops showing the dialog. " +
                    "Enable it in app settings instead.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = hapticClick {
                    settingsLauncher.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", ctx.packageName, null)
                        )
                    )
                }) { Text("Open app settings") }
            }
        }
        return
    }

    if (!btOn) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Bluetooth is off", style = MaterialTheme.typography.titleLarge)
            Button(onClick = hapticClick {
                btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }) { Text("Turn Bluetooth on") }
        }
        return
    }

    if (!locOn) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Turn Location on", style = MaterialTheme.typography.titleLarge)
            Text(
                "On Android 11 and older, Bluetooth scanning silently returns nothing " +
                "while Location is switched off. The app never reads your position — " +
                "this is an Android requirement, not ours.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = hapticClick {
                settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }) { Text("Open location settings") }
            OutlinedButton(onClick = hapticClick { locOn = locationOn(ctx) }) { Text("I turned it on") }
        }
        return
    }

    // Один экран: страница дисплея, а список доступных колёс — строкой в его
    // шапке. Отдельного экрана-списка больше нет.
    DeviceScreen(vm)
}
