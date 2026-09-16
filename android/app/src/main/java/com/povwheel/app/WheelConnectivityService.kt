package com.povwheel.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.povwheel.app.ble.BleClient
import com.povwheel.app.ble.Link
import com.povwheel.app.ble.Proto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Держит колёса на связи, когда экран не может делать это сам: и пока
 * приложение просто свёрнуто (режим [ACTION_STANDBY]), и после того, как его
 * закрыли по-настоящему ([ACTION_DRIVE]).
 *
 * Экран (WheelVm) при простом сворачивании НИЧЕГО не передаёт — Compose не
 * разбирает дерево при остановке Activity, только при уничтожении, так что
 * собственные соединения и собственное сканирование ViewModel продолжают
 * работать сами. Единственная причина существования этой службы в такой
 * момент — поднять процесс в приоритет foreground: без него Android рано или
 * поздно глушит фоновое BLE-сканирование (и может убить сам процесс), и весь
 * смысл автоподключения «даже когда телефон свёрнут» пропадает. В этом режиме
 * служба сама НЕ сканирует и ни к чему не подключается — вторая, параллельная
 * попытка подключения к тому же адресу, который уже держит ViewModel,
 * ненадёжна (та же причина, что у [SyncSlideshowService], см. класс там).
 *
 * Если процесс всё же уничтожают по-настоящему, ViewModel.onCleared() успевает
 * попросить эту же службу перейти в [ACTION_DRIVE]: теперь она сама сканирует
 * (тот же фильтр по UUID сервиса, что и экран) и подключается к любому
 * найденному колесу — впервые увиденному точно так же, как уже известному, по
 * тем же правилам, что WheelVm.onSeenAgain (см. [isIgnored]). Участников
 * активной группы синхронного показа не трогает вовсе — те уже подхватывает
 * [SyncSlideshowService], и вторая параллельная попытка к ним запрещена ровно
 * по той же причине.
 *
 * Когда экран открывают снова, WheelVm просит службу [release] — она гасит
 * свои соединения и останавливается, а экран забирает управление обратно
 * (см. WheelVm.enterForeground). Уведомление здесь — исключительно тех же
 * бесшумных, IMPORTANCE_MIN правил, что у SyncSlideshowService: сама служба
 * обязана его показывать (иначе foreground-статус невозможен), но пользователю
 * не нужно, чтобы оно бросалось в глаза.
 */
class WheelConnectivityService : Service() {

    private enum class Mode { STANDBY, DRIVING }

    companion object {
        private const val CHANNEL_ID = "wheel_link"
        private const val NOTIF_ID = 43
        private const val HINT_EXTRA = "hint"

        private const val ACTION_STANDBY = "com.povwheel.app.link.STANDBY"
        private const val ACTION_DRIVE = "com.povwheel.app.link.DRIVE"
        private const val ACTION_RELEASE = "com.povwheel.app.link.RELEASE"

        @Volatile private var mode: Mode? = null   // null — служба не бежит вовсе
        @Volatile private var drivingAddresses: Set<String> = emptySet()

        /** Служба сейчас жива в каком-то виде (ожидание или полный привод) —
         *  экрану, прежде чем забрать управление обратно, есть что отпускать. */
        fun isBusy(): Boolean = mode != null

        /** Служба САМА сейчас держит связь с этим адресом — второе, параллельное
         *  подключение к нему сейчас небезопасно (см. класс). */
        fun isDrivingAddress(addr: String): Boolean = mode == Mode.DRIVING && addr in drivingAddresses

        /** Адреса, что служба реально держит подключёнными прямо сейчас —
         *  экран использует это, чтобы после [release] подключиться к ним
         *  напрямую по MAC, не дожидаясь свежей рекламы. */
        fun snapshotDriving(): Set<String> = drivingAddresses

        /** Экран свернули — поднять процесс в приоритет foreground, самой
         *  ничего не трогая (см. класс). */
        fun standby(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, WheelConnectivityService::class.java).apply { action = ACTION_STANDBY }
            )
        }

        /** ViewModel закрылась — служба берёт поиск и автоподключение на себя.
         *  [hint] — адреса, что были на связи прямо перед закрытием. */
        fun takeOver(ctx: Context, hint: List<String>) {
            val i = Intent(ctx, WheelConnectivityService::class.java).apply {
                action = ACTION_DRIVE
                putStringArrayListExtra(HINT_EXTRA, ArrayList(hint))
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Экран снова на переднем плане — служба гасит свои соединения (если
         *  были) и останавливается, ничего не рассылая колёсам. */
        fun release(ctx: Context) {
            ctx.startService(Intent(ctx, WheelConnectivityService::class.java).apply { action = ACTION_RELEASE })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clients = HashMap<String, BleClient>()
    private var scanning = false
    private var driveJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STANDBY -> {
                if (mode != Mode.DRIVING) {
                    mode = Mode.STANDBY
                    startForegroundCompat(buildNotification())
                }
            }
            ACTION_DRIVE -> startDriving(intent.getStringArrayListExtra(HINT_EXTRA) ?: arrayListOf())
            ACTION_RELEASE -> doRelease()
        }
        return START_NOT_STICKY
    }

    /** Переходит (или остаётся) в полный привод: сканирует и подключается к
     *  любому найденному колесу, кроме участников активной синхронной группы
     *  (тех уже подключает [SyncSlideshowService] — см. класс). [hint] пробуем
     *  первыми, по MAC, не дожидаясь рекламы: они были на связи секунду назад
     *  и, скорее всего, ещё в радиусе. */
    @SuppressLint("MissingPermission")
    private fun startDriving(hint: List<String>) {
        mode = Mode.DRIVING
        startForegroundCompat(buildNotification())
        driveJob?.cancel()
        driveJob = scope.launch {
            for (a in hint) connectIfEligible(a)
            startScanning()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectIfEligible(addr: String) {
        if (mode != Mode.DRIVING) return
        if (isIgnored(addr)) return
        // Участник синхронной группы — не наш: WheelVm.handOffConnectivity уже
        // исключил их из hint, а свежий scanCb мог бы наткнуться на них и без
        // подсказки. Второе, параллельное соединение с того же телефона к тому
        // же адресу ненадёжно (см. класс).
        if (SyncSlideshowService.trackedGroupFor(addr) != null) return
        val existing = clients[addr]
        if (existing != null && (existing.link.value == Link.Ready || existing.link.value == Link.Connecting)) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val dev = try { adapter.getRemoteDevice(addr) } catch (e: Exception) { return }
        existing?.close()
        val c = BleClient(applicationContext, dev)
        c.onLinkLost = { scope.launch { refreshDriving() } }
        clients[addr] = c
        scope.launch {
            val ok = runCatching { c.connect() }.getOrDefault(false)
            if (!ok) clients.remove(addr, c)   // не удалось — следующая рекламная пачка попробует снова
            refreshDriving()
        }
    }

    private fun refreshDriving() {
        drivingAddresses = clients.filterValues { it.link.value == Link.Ready }.keys
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (scanning) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SVC)).build()
        // LOW_POWER, не LOW_LATENCY: здесь никто не смотрит на экран, торопиться
        // незачем — экономия заряда важнее доли секунды задержки обнаружения.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCb)
            scanning = true
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!scanning) return
        try {
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {}
        scanning = false
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device?.address ?: return
            scope.launch { connectIfEligible(addr) }
        }
        override fun onScanFailed(errorCode: Int) { scanning = false }
    }

    private fun isIgnored(addr: String): Boolean =
        (getSharedPreferences("pov", MODE_PRIVATE).getStringSet("ignored", emptySet()) ?: emptySet())
            .contains(addr)

    /** Гасит соединения/сканирование (если были) и останавливает саму службу —
     *  безопасно звать и из режима ожидания (тогда просто нечего гасить). */
    private fun doRelease() {
        stopScanning()
        driveJob?.cancel(); driveJob = null
        val toClose = ArrayList(clients.values)
        clients.clear()
        drivingAddresses = emptySet()
        mode = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.launch {
            for (c in toClose) c.close()
            stopSelf()
        }
    }

    override fun onDestroy() {
        driveJob?.cancel()
        for (c in clients.values) c.close()
        clients.clear()
        drivingAddresses = emptySet()
        mode = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wheel connectivity", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("POV Wheel Display")
            .setContentText("Watching for your wheels in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
