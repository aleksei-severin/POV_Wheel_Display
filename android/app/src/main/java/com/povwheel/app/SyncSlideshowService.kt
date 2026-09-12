package com.povwheel.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.povwheel.app.ble.BleClient
import com.povwheel.app.ble.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Держит уведомление синхронного показа и подхватывает тикер (явные
 * `play`/`effect` на все адреса группы разом — см. WheelVm.startGroupTicker и
 * комментарий в начале секции синхронизации слайдшоу в WheelVm.kt), когда
 * приложение закрывают.
 *
 * Обычно (экран открыт) сама она НИЧЕГО не шлёт и никуда не подключается —
 * тикер живёт во ViewModel, на её же, уже открытых соединениях. Служба лишь
 * ЗАПОМИНАЕТ состав/отбор/интервал последней активной группы ([track]) и
 * показывает уведомление. Как только `WheelVm.onCleared()` замечает, что
 * приложение закрывается, она просит службу взять тикер на себя
 * ([takeOverTicking]) — и вот тогда служба подключается к участникам с нуля и
 * продолжает слать те же команды сама. Если экран потом открывают заново и
 * успешно подключаются хотя бы к одному участнику, ViewModel просит службу
 * отпустить тикер обратно ([releaseTicking]) — служба гасит СВОИ соединения
 * (ничего не разослав, показ на колёсах не трогаем), и тикер возвращается во
 * ViewModel.
 *
 * Ключевое правило, ради которого всё это разделение: НИКОГДА не пытаться
 * подключиться к адресу, который в этот момент уже подключён во ViewModel
 * (и наоборот). Bluetooth не держит два по-настоящему разных ACL-канала к
 * одному устройству с одного телефона — вторая, параллельная попытка тихо
 * не удаётся или мешает первой. Именно так выглядела реальная поломка (тост
 * «Synced slideshow started» показывался, а колесо ничего не получало) —
 * когда более ранняя версия этой службы пыталась подключаться сама сразу
 * при старте, не дожидаясь, пока экран отпустит то же самое соединение.
 */
class SyncSlideshowService : Service() {

    /** Состав/отбор/интервал последней активной группы — здесь, а не только
     *  во ViewModel, специально: переживает закрытие приложения (пока жив
     *  процесс, то есть пока жива сама служба), и именно на этом строится
     *  восстановление состояния экрана при повторном открытии (см.
     *  WheelVm.restoreSyncStateIfNeeded — она читает это напрямую, в этом же
     *  процессе, а не через Binder). */
    data class TrackedGroup(val members: List<String>, val names: List<String>, val files: List<String>, val intervalMs: Int)

    companion object {
        private const val CHANNEL_ID = "sync_slideshow"
        private const val NOTIF_ID = 42

        private const val ACTION_TRACK = "com.povwheel.app.sync.TRACK"
        private const val ACTION_TAKE_OVER = "com.povwheel.app.sync.TAKE_OVER"
        private const val ACTION_RELEASE = "com.povwheel.app.sync.RELEASE"
        private const val ACTION_STOP = "com.povwheel.app.sync.STOP"

        @Volatile private var tracked: TrackedGroup? = null
        @Volatile private var drivingMembers: Set<String> = emptySet()

        /** Группа для этого адреса, если служба о ней ещё помнит (см. класс). */
        fun trackedGroupFor(addr: String): TrackedGroup? = tracked?.takeIf { addr in it.members }

        /** Служба САМА сейчас подключена и тикает за этот адрес (а не просто
         *  помнит о нём) — второе соединение к нему сейчас небезопасно. */
        fun isDrivingAddress(addr: String): Boolean = addr in drivingMembers

        /** Запомнить состав группы и поднять уведомление — без подключения,
         *  тикер пока (или уже снова) ведёт ViewModel сама. */
        fun track(ctx: Context, members: List<String>, names: List<String>, files: List<String>, intervalMs: Int) {
            tracked = TrackedGroup(members, names, files, intervalMs)
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, SyncSlideshowService::class.java).apply { action = ACTION_TRACK }
            )
        }

        /** Обновить отбор/интервал уже запомненной группы — на лету, без
         *  пересоздания соединений (используется и когда тикер здесь, и когда
         *  во ViewModel: сама рассылку в обоих случаях делает её текущий
         *  владелец, эта запись — только чтобы держать копию в курсе на
         *  случай будущей передачи). */
        fun updateConfig(files: List<String>, intervalMs: Int) {
            tracked = tracked?.copy(files = files, intervalMs = intervalMs) ?: return
        }

        /** ViewModel умирает (закрыли приложение) — служба подключается к
         *  участникам с нуля и продолжает тикать сама. */
        fun takeOverTicking(ctx: Context) {
            ctx.startService(Intent(ctx, SyncSlideshowService::class.java).apply { action = ACTION_TAKE_OVER })
        }

        /** Экран снова открыт и подключился — служба гасит СВОИ соединения,
         *  ничего не рассылая (показ на колёсах не трогаем), и остаётся
         *  только «tracked», ожидая следующего takeOverTicking. */
        fun releaseTicking(ctx: Context) {
            ctx.startService(Intent(ctx, SyncSlideshowService::class.java).apply { action = ACTION_RELEASE })
        }

        /** Остановить показ на всех участниках группы и погасить уведомление.
         *  Безопасно звать даже если служба не запущена. */
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SyncSlideshowService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clients = HashMap<String, BleClient>()
    private var driveJob: Job? = null   // покрывает и паузу-перед-подключением, и сам тикер

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRACK -> startForegroundCompat(buildNotification())
            ACTION_TAKE_OVER -> startDriving()
            ACTION_RELEASE -> stopDriving(sendStop = false)
            ACTION_STOP -> doStop()
        }
        return START_NOT_STICKY
    }

    /** Подключается к участникам группы С НУЛЯ и заводит тикер — только по
     *  запросу [takeOverTicking], то есть когда ViewModel уже точно закрыла
     *  свои собственные соединения к тем же адресам. Небольшая пауза перед
     *  подключением — подстраховка на случай, если оба события (закрытие
     *  экрана и передача тикера) пришли не в идеальном порядке. */
    @SuppressLint("MissingPermission")
    private fun startDriving() {
        val group = tracked ?: return
        if (driveJob?.isActive == true || drivingMembers.isNotEmpty()) return
        driveJob = scope.launch {
            delay(600)
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            for (a in group.members) {
                if (clients.containsKey(a)) continue
                val dev = try { adapter.getRemoteDevice(a) } catch (e: Exception) { continue }
                clients[a] = BleClient(applicationContext, dev)
            }
            clients.values
                .map { c -> launch { if (c.link.value != Link.Ready) runCatching { c.connect() } } }
                .joinAll()
            drivingMembers = clients.filterValues { it.link.value == Link.Ready }.keys
            var index = -1
            while (isActive) {
                val cfg = tracked ?: break
                if (cfg.files.isEmpty()) break
                index = (index + 1).let { if (it >= cfg.files.size) 0 else it }
                val name = cfg.files[index]
                val effId = if (name.startsWith("@e")) name.removePrefix("@e").toIntOrNull() else null
                for (c in clients.values) {
                    if (c.link.value != Link.Ready) continue
                    runCatching { if (effId != null) c.effect(effId) else c.play(name) }
                }
                delay(cfg.intervalMs.toLong())
            }
        }
    }

    /** Отменяет и паузу-перед-подключением, и сам тикер (см. [driveJob]),
     *  закрывает СВОИ соединения — и, если [sendStop], сначала гасит показ
     *  на них ([BleClient.stop]). Останавливать саму службу/уведомление не
     *  входит в её обязанности — это делает [doStop] отдельно. */
    private fun stopDriving(sendStop: Boolean) {
        driveJob?.cancel(); driveJob = null
        val toClose = ArrayList(clients.values)
        clients.clear()
        drivingMembers = emptySet()
        if (toClose.isEmpty()) return
        scope.launch {
            for (c in toClose) {
                if (sendStop && c.link.value == Link.Ready) runCatching { c.stop() }
                c.close()
            }
        }
    }

    /** Stop насовсем: гасит показ у всех участников группы — через уже
     *  открытые соединения, если служба сейчас сама ведёт тикер, иначе
     *  подключается с нуля только на время самой команды (обычный случай —
     *  Stop из уведомления, когда экран приложения и так закрыт). */
    @SuppressLint("MissingPermission")
    private fun doStop() {
        val group = tracked
        tracked = null
        val wasDriving = drivingMembers.isNotEmpty()
        driveJob?.cancel(); driveJob = null
        val toClose = ArrayList(clients.values)
        clients.clear()
        drivingMembers = emptySet()
        scope.launch {
            if (wasDriving) {
                for (c in toClose) { if (c.link.value == Link.Ready) runCatching { c.stop() }; c.close() }
            } else if (group != null) {
                val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                group.members.map { a ->
                    launch stopOne@{
                        val dev = try { adapter.getRemoteDevice(a) } catch (e: Exception) { return@stopOne }
                        val c = BleClient(applicationContext, dev)
                        if (runCatching { c.connect() }.getOrDefault(false)) runCatching { c.stop() }
                        c.close()
                    }
                }.joinAll()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        driveJob?.cancel()
        for (c in clients.values) c.close()
        clients.clear()
        drivingMembers = emptySet()
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
                NotificationChannel(CHANNEL_ID, "Sync slideshow", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SyncSlideshowService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sync slideshow running")
            .setContentText((tracked?.names ?: emptyList()).joinToString(" ↔ "))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
