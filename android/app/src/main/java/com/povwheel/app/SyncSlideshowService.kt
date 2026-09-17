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
import androidx.core.app.NotificationManagerCompat
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
 * ЗАПОМИНАЕТ состав/отбор/интервал последней активной группы ([track]) — само
 * запоминание уже не требует её запуска, это просто statics, живущие пока жив
 * процесс. Как только `WheelVm.onCleared()` замечает, что приложение
 * закрывается, она просит службу взять тикер на себя ([takeOverTicking]) — и
 * вот тогда служба подключается к участникам с нуля и продолжает слать те же
 * команды сама. Если экран потом открывают заново и успешно подключаются хотя
 * бы к одному участнику, ViewModel просит службу отпустить тикер обратно
 * ([releaseTicking]) — служба гасит СВОИ соединения (ничего не разослав, показ
 * на колёсах не трогаем), и тикер возвращается во ViewModel.
 *
 * Ключевое правило, ради которого всё это разделение: НИКОГДА не пытаться
 * подключиться к адресу, который в этот момент уже подключён во ViewModel
 * (и наоборот). Bluetooth не держит два по-настоящему разных ACL-канала к
 * одному устройству с одного телефона — вторая, параллельная попытка тихо
 * не удаётся или мешает первой. Именно так выглядела реальная поломка (тост
 * «Synced slideshow started» показывался, а колесо ничего не получало) —
 * когда более ранняя версия этой службы пыталась подключаться сама сразу
 * при старте, не дожидаясь, пока экран отпустит то же самое соединение.
 *
 * Уведомление — живой индикатор, а не флаг «пользователь нажал Start»: оно
 * показывается РОВНО когда у группы реально на связи два участника и больше,
 * и прячется, как только их снова меньше двух (одно из колёс заснуло/вышло из
 * радиуса) — независимо от того, кто в этот момент ведёт тикер. Пока ведёт
 * ViewModel (экран открыт), решение принимает она сама через [setLiveVisible]
 * — обычным `Notification`, безо всякого foreground-статуса службы, которой
 * в этот момент даже не обязательно быть живой. Как только тикер переходит
 * сюда ([startDriving]), решение переходит вместе с ним — [updateNotificationVisibility]
 * дальше делает то же самое через startForeground/stopForeground, потому что
 * здесь показ уже обязан быть foreground (иначе Android убьёт службу за
 * несколько секунд простоя без него).
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

        private const val ACTION_TAKE_OVER = "com.povwheel.app.sync.TAKE_OVER"
        private const val ACTION_RELEASE = "com.povwheel.app.sync.RELEASE"
        private const val ACTION_STOP = "com.povwheel.app.sync.STOP"

        /** Пауза между попытками подтянуть участника, который всё ещё не Ready —
         *  см. [reconnectLoop]. */
        private const val RECONNECT_RETRY_MS = 10_000L

        @Volatile private var tracked: TrackedGroup? = null
        @Volatile private var drivingMembers: Set<String> = emptySet()

        /** Группа для этого адреса, если служба о ней ещё помнит (см. класс). */
        fun trackedGroupFor(addr: String): TrackedGroup? = tracked?.takeIf { addr in it.members }

        /** Служба САМА сейчас подключена и тикает за этот адрес (а не просто
         *  помнит о нём) — второе соединение к нему сейчас небезопасно. */
        fun isDrivingAddress(addr: String): Boolean = addr in drivingMembers

        /**
         * Запомнить состав группы — просто statics, живущие пока жив процесс.
         * НЕ запускает и не трогает саму службу и НЕ показывает уведомление:
         * пока ведёт ViewModel, ей самой ничего из этого не нужно (см. класс).
         */
        fun track(members: List<String>, names: List<String>, files: List<String>, intervalMs: Int) {
            tracked = TrackedGroup(members, names, files, intervalMs)
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

        /**
         * Показать (или спрятать) живое уведомление синхронного показа —
         * ПОКА тикер ведёт ViewModel, то есть службе для этого не обязательно
         * быть запущена вовсе: обычный `Notification`, не связанный ни с каким
         * foreground-статусом. [namesIfLive] — участники, реально на связи
         * ([Link.Ready]), числом два и больше; null — меньше двух, уведомление
         * прячем. Как только тикер переходит службе ([startDriving]), эту же
         * запись (тот же канал/id) начинает вести она сама, через
         * [updateNotificationVisibility] — двух одновременных писателей в
         * один и тот же момент не бывает по построению: пока ViewModel жива и
         * зовёт это, служба ещё не забирала у неё соединения (см. класс).
         */
        fun setLiveVisible(ctx: Context, namesIfLive: List<String>?) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            ensureChannel(mgr)
            if (namesIfLive == null) {
                mgr.cancel(NOTIF_ID)
            } else {
                NotificationManagerCompat.from(ctx).notify(NOTIF_ID, buildNotification(ctx, namesIfLive))
            }
        }

        private fun ensureChannel(mgr: NotificationManager) {
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                // MIN, а не LOW: пользователю сам факт синхронизации не так
                // важен, чтобы всплывать или показывать значок в статус-баре —
                // MIN сворачивает уведомление в конец шторки, под "Show silent
                // notifications", но не убирает его как таковое (обязательное
                // условие для живущей foreground-службы, когда она есть).
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Sync slideshow", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }

        private fun buildNotification(ctx: Context, names: List<String>): Notification {
            ensureChannel(ctx.getSystemService(NotificationManager::class.java))
            val stopIntent = PendingIntent.getService(
                ctx, 0,
                Intent(ctx, SyncSlideshowService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val openIntent = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Sync slideshow running")
                .setContentText(names.joinToString(" ↔ "))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(openIntent)
                .addAction(0, "Stop", stopIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clients = HashMap<String, BleClient>()
    private var driveJob: Job? = null   // покрывает и паузу-перед-подключением, и сам тикер

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
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
        // Обязана стать foreground сразу — иначе Android убьёт службу в
        // течение нескольких секунд после startForegroundService(). Заметность
        // самого уведомления решает refreshDriving()/updateNotificationVisibility
        // по факту того, сколько участников РЕАЛЬНО на связи — это здесь
        // единственное отступление от правила, и то временное: доля секунды,
        // пока идёт первое подключение, когда Android требует показать хоть что-то.
        startForegroundCompat(buildNotification(this, group.names))
        driveJob = scope.launch {
            delay(600)
            connectMissingMembers()
            clients.values
                .map { c -> launch { if (c.link.value != Link.Ready) runCatching { c.connect() } } }
                .joinAll()
            refreshDriving()
            launch { reconnectLoop() }
            var index = -1
            while (isActive) {
                val cfg = tracked ?: break
                if (cfg.files.isEmpty()) break
                index = (index + 1).let { if (it >= cfg.files.size) 0 else it }
                val name = cfg.files[index]
                val effId = if (name.startsWith("@e")) name.removePrefix("@e").toIntOrNull() else null
                for (c in clients.values) {
                    if (c.link.value != Link.Ready) continue
                    // syncTick, не play()/effect(): не должен гасить автономный
                    // ход слайдшоу на колесе — та же причина, что и во ViewModel
                    // (см. WheelVm.startGroupTicker), только здесь ещё важнее:
                    // если это соединение тоже пропадёт (Bluetooth выключили,
                    // саму службу убила система), колесу продолжать самому
                    // ровно за счёт того, что этот тикер его не разоружал.
                    runCatching { if (effId != null) c.syncTick(null, effId) else c.syncTick(name) }
                }
                delay(cfg.intervalMs.toLong())
            }
        }
    }

    /** Заводит клиента для каждого участника группы, для которого его ещё нет
     *  ([clients] дальше живёт до самого [stopDriving]/[doStop]) — сам
     *  connectGatt не запускает, только создаёт объект и вешает [BleClient.onLinkLost].
     *  Вызывается и при первом запуске ([startDriving]), и повторно из
     *  [reconnectLoop] на случай, если сюда попали до того, как первый вызов
     *  вообще успел отработать. */
    @SuppressLint("MissingPermission")
    private fun connectMissingMembers() {
        val group = tracked ?: return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        for (a in group.members) {
            if (clients.containsKey(a)) continue
            val dev = try { adapter.getRemoteDevice(a) } catch (e: Exception) { continue }
            val c = BleClient(applicationContext, dev)
            c.onLinkLost = { scope.launch { refreshDriving() } }
            clients[a] = c
        }
    }

    /**
     * Повторяет попытку подключения к участникам, которые ещё (или уже) не
     * Ready. Единственная попытка при старте [startDriving] исходила из того,
     * что колесо либо уже в эфире, либо не наша забота — но самый обычный
     * повод для передачи тикера сюда (ViewModel закрылась, потому что колёса
     * заснули) — это ровно тот случай, когда колесо ещё СПИТ в момент этого
     * первого подключения: BLE-стек и реклама поднимаются не мгновенно после
     * пробуждения. Без повтора такое колесо оставалось бы вне тиков до самого
     * doStop/следующего takeOverTicking — крутило бы свой автономный запасной
     * ход (WheelVm.armGroupFallback), с тем же интервалом, но расходясь по
     * фазе с остальными участниками, которые тикер всё это время получают, —
     * снаружи это выглядит как «переключается синхронно, показывает разное».
     * Фиксированный интервал, без экспоненциального роста, как у
     * WheelVm.onLinkLost на экране: участников здесь единицы, а не десятки
     * экранов сразу, так что телефон от этого заметно не греется.
     */
    private suspend fun CoroutineScope.reconnectLoop() {
        while (isActive) {
            delay(RECONNECT_RETRY_MS)
            val group = tracked ?: break
            connectMissingMembers()
            val toRetry = group.members.mapNotNull { a ->
                clients[a]?.takeIf { it.link.value != Link.Ready && it.link.value != Link.Connecting }
            }
            if (toRetry.isEmpty()) continue
            toRetry.map { c -> launch { runCatching { c.connect() } } }.joinAll()
            refreshDriving()
        }
    }

    /** Пересчитывает, кто из участников сейчас реально на связи, и поправляет
     *  видимость уведомления по этому факту. Вызывается после каждой попытки
     *  подключения и на каждый обрыв ([BleClient.onLinkLost]) — то есть именно
     *  тогда, когда true-состояние группы могло измениться. */
    private fun refreshDriving() {
        drivingMembers = clients.filterValues { it.link.value == Link.Ready }.keys
        updateNotificationVisibility()
    }

    /** Показывает уведомление, когда у группы два участника и больше реально
     *  на связи, и прячет его иначе — не останавливая саму службу: она обязана
     *  продолжать сканирование/тикер и после того, как один из участников
     *  отвалился, поэтому это [stopForeground], а не [stopSelf]. */
    private fun updateNotificationVisibility() {
        val group = tracked
        if (group != null && drivingMembers.size >= 2) {
            startForegroundCompat(buildNotification(this, group.names))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /** Отменяет и паузу-перед-подключением, и сам тикер (см. [driveJob]),
     *  закрывает СВОИ соединения — и, если [sendStop], сначала гасит показ
     *  на них ([BleClient.stop]). Всегда завершает саму службу: экран уже
     *  забирает управление обратно (см. WheelVm.enterForeground/connect), и
     *  держать её живой без дела до следующего takeOverTicking незачем. */
    private fun stopDriving(sendStop: Boolean) {
        driveJob?.cancel(); driveJob = null
        val toClose = ArrayList(clients.values)
        clients.clear()
        drivingMembers = emptySet()
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.launch {
            for (c in toClose) {
                if (sendStop && c.link.value == Link.Ready) runCatching { c.stop() }
                c.close()
            }
            stopSelf()
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
        // Единственный путь, которым Stop может прийти, пока сам процесс с
        // ViewModel мёртв целиком (кнопка в уведомлении) — WheelVm.endSync()
        // тогда не выполнится и не почистит свою копию состава группы в
        // SharedPreferences (см. WheelVm.persistSyncGroup/clearPersistedSyncGroup);
        // без этой чистки следующий запуск приложения посчитал бы, что группа
        // всё ещё должна идти, и воскресил бы её сам, вопреки явному Stop.
        getSharedPreferences("pov", MODE_PRIVATE).edit()
            .remove("syncgroup_members").remove("syncgroup_files").remove("syncgroup_interval")
            .apply()
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
            NotificationManagerCompat.from(this@SyncSlideshowService).cancel(NOTIF_ID)
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
}
