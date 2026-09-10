package com.povwheel.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelUuid
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.povwheel.app.ble.BleClient
import com.povwheel.app.ble.DevFile
import com.povwheel.app.ble.FsInfo
import com.povwheel.app.ble.Link
import com.povwheel.app.ble.PreviewFrame
import com.povwheel.app.ble.Proto
import com.povwheel.app.ble.Settings
import com.povwheel.app.convert.Ani6
import com.povwheel.app.convert.Converter
import com.povwheel.app.convert.Fit
import com.povwheel.app.convert.PreviewClip
import com.povwheel.app.convert.PreviewClips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.TimeZone

/** Одно колесо глазами интерфейса — подключённое или просто замеченное. */
data class Found(
    val address: String,
    val name: String,
    val rssi: Int,
    val lastSeen: Long
)

/**
 * Строка списка колёс — всё, что о нём известно, одним значением.
 *
 * Экран подписан РОВНО на этот список и больше ни на что. Раньше он собирал
 * строку из трёх источников (найденные, карта соединений, поток телеметрии
 * клиента), причём два последних читались через `?.collectAsState()` внутри
 * элемента списка — то есть composable-вызов происходил условно, только когда
 * соединение существует. Появление или исчезновение клиента меняло состав
 * вызовов внутри элемента, а это в Compose запрещено: строка переставала
 * перерисовываться и показывала состояние, которого уже нет. Отсюда и жалоба
 * на «неактуальный статус».
 */
data class WheelEntry(
    val address: String,
    val name: String,
    /** Уровень сигнала последнего пакета рекламы; [Int.MIN_VALUE] — не видели. */
    val rssi: Int,
    /** Мс с последнего пакета рекламы; [Long.MAX_VALUE] — не видели ни разу. */
    val seenAgo: Long,
    val link: Link,
    val error: String?,
    /** Телеметрия — только когда [link] == [Link.Ready]. */
    val soc: Int,
    val rpm: Float,
    val usb: Boolean,
    val playing: String,
    /** Колесо уже добавляли в приложение (лежит в списке известных). */
    val known: Boolean
) {
    /** Реклама не приходила достаточно долго, чтобы считать колесо недоступным. */
    val stale: Boolean get() = link != Link.Ready && seenAgo > STALE_AFTER_MS

    companion object { const val STALE_AFTER_MS = 20_000L }
}

class WheelVm(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val btManager get() = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager.adapter

    private val prefs = ctx.getSharedPreferences("pov", Context.MODE_PRIVATE)

    val found = MutableStateFlow<List<Found>>(emptyList())
    val scanning = MutableStateFlow(false)

    /** Адрес → живое соединение. Несколько колёс держатся подключёнными сразу. */
    private val clients = LinkedHashMap<String, BleClient>()
    val connected = MutableStateFlow<List<BleClient>>(emptyList())

    /**
     * Сводный список для экрана: найденные в эфире, известные с прошлого раза и
     * подключённые — в одном месте и с актуальным состоянием каждого.
     * Пересобирается по любому поводу: пакет рекламы, смена состояния связи,
     * телеметрия, тик устаревания.
     */
    val wheels = MutableStateFlow<List<WheelEntry>>(emptyList())

    /** Переподключаться самим, пока пользователь не сказал «отключить». */
    private val wantConnected = HashSet<String>()

    // Наблюдение за потоками каждого клиента: соединение и телеметрия обязаны
    // попадать в сводный список сразу, а не когда экран о них спросит.
    private val watchJobs = HashMap<String, kotlinx.coroutines.Job>()

    /** Задачи переподключения, по одной на адрес. Снимаются в disconnect(). */
    private val reconnectJobs = HashMap<String, kotlinx.coroutines.Job>()

    /**
     * Последнее известное «Положение магнита» (Settings.angle) каждого колеса,
     * по адресу. Это механическая калибровка конкретного колеса — как и
     * калибровка датчиков Холла: магнит на вилке у каждого колеса стоит под
     * своим углом. В режиме зеркалирования её нельзя навязывать соседям, поэтому
     * блок настроек уходит на не-текущие колёса с их собственным углом, а не с
     * углом активного колеса. Заполняется при каждом чтении настроек колеса
     * (см. [adoptSettings]); в [pushSettings] служит источником этого угла.
     */
    private val magnetByAddr = HashMap<String, Int>()

    /** Какое колесо показывает экран управления. */
    val current = MutableStateFlow<String?>(null)

    val settings = MutableStateFlow(Settings())
    /**
     * Прочитаны ли настройки с ТЕКУЩЕГО колеса. Пока нет, [pushSettings] молчит:
     * иначе первое же движение ползунка отправило бы на устройство весь блок из
     * 14 полей, набитый значениями по умолчанию, — и стёрло бы настроенные
     * гамму, баланс белого и пороги оборотов.
     */
    val settingsLoaded = MutableStateFlow(false)
    val files = MutableStateFlow<List<DevFile>>(emptyList())
    val fsInfo = MutableStateFlow(FsInfo())
    val logLines = MutableStateFlow<List<String>>(emptyList())
    val toast = MutableStateFlow<String?>(null)

    /**
     * Группа синхронизации: адреса колёс, которые работают как одно целое —
     * воспроизведение, эффекты, настройки и заливка идут на всю группу разом,
     * но ТОЛЬКО когда открытое на экране колесо само входит в эту группу.
     * У велосипеда обычно два колеса (переднее и заднее), и на них нужна одна
     * картинка; отметить можно как эти два, так и любой другой набор. Меньше
     * двух подключённых колёс в группе — синхронизации нет, всё идёт только на
     * открытое колесо. Хранится набором адресов в prefs («mirror_set»).
     */
    val mirrorSet = MutableStateFlow(
        (prefs.getStringSet("mirror_set", emptySet()) ?: emptySet()).toSet()
    )

    // Пишется с Dispatchers.IO, читается из отрисовки списка — обычный HashMap
    // здесь может уйти в бесконечный цикл на рехэше.
    private val thumbs = ConcurrentHashMap<String, PreviewFrame>()

    // ---- Локальные анимированные превью ----
    // При заливке рядом с файлом кладётся компактный рендер (спрайт-лист ~0.5 МБ),
    // и библиотека потом крутит его локально, не дёргая кадры по BLE. Фото-пикер
    // Android отдаёт доступ к исходнику лишь на время жизни процесса, поэтому
    // сохраняется именно рендер, а не сам файл.
    private val CLIP_MEM_MAX = 20                        // разобранных клипов в памяти
    private val CLIP_DISK_MAX = 64L * 1024 * 1024        // потолок кэша на диске
    private val previewDir by lazy { File(ctx.filesDir, "prev").also { it.mkdirs() } }
    /** Ключ — имя файла на устройстве. Доступ и из IO, и из отрисовки списка,
     *  поэтому под `synchronized`. При переполнении самый давний просто выпадает
     *  из карты — утилизировать его битмапы нельзя, их ещё может рисовать строка
     *  списка; освободит сборщик, когда строка уедет с экрана. */
    private val clipMem = object : LinkedHashMap<String, PreviewClip>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreviewClip>): Boolean =
            size > CLIP_MEM_MAX
    }
    /** Бампается, когда фоновая задача дописала превью в кэш, — строка библиотеки
     *  по этому ключу перечитывает клип. */
    val previewVersion = MutableStateFlow(0)

    private var logTotal = 0L
    private var pollJob: kotlinx.coroutines.Job? = null
    private var scanStopJob: kotlinx.coroutines.Job? = null

    init {
        // Тик устаревания. Без него строка «видели 3 с назад» так и висит
        // после того, как колесо унесли: пакетов больше нет, а значит и повода
        // пересобрать список — тоже. Секунда достаточно мелкая, чтобы переход
        // в «недоступно» не выглядел залипанием, и достаточно крупная, чтобы
        // ничего не стоить.
        viewModelScope.launch {
            while (true) { delay(1000); rebuildWheels() }
        }
        found.value = knownDevices()
        rebuildWheels()
    }

    /** Пересобрать [wheels]. Дёшево: колёс единицы, а не сотни. */
    private fun rebuildWheels() {
        val now = System.currentTimeMillis()
        val known = prefs.getStringSet("known", emptySet()) ?: emptySet()
        val byAddr = LinkedHashMap<String, WheelEntry>()

        for (f in found.value) {
            val c = clients[f.address]
            val t = c?.tele?.value
            val lk = c?.link?.value ?: Link.Disconnected
            byAddr[f.address] = WheelEntry(
                address = f.address,
                name    = f.name,
                rssi    = if (f.lastSeen == 0L) Int.MIN_VALUE else f.rssi,
                seenAgo = if (f.lastSeen == 0L) Long.MAX_VALUE else now - f.lastSeen,
                link    = lk,
                error   = c?.lastError,
                soc     = if (lk == Link.Ready && t != null) t.soc else 0,
                rpm     = if (lk == Link.Ready && t != null) t.rpm else 0f,
                usb     = lk == Link.Ready && t != null && t.usb,
                playing = if (lk == Link.Ready && t != null) t.file else "",
                known   = known.contains(f.address)
            )
        }
        // Подключённое колесо рекламу не шлёт — NimBLE останавливает её на
        // время соединения. Без этой доборки собственное колесо исчезало бы из
        // списка ровно в тот момент, когда с ним работают.
        for ((addr, c) in clients) {
            if (byAddr.containsKey(addr)) continue
            val t = c.tele.value
            val lk = c.link.value
            byAddr[addr] = WheelEntry(
                address = addr, name = prefs.getString("name_" + addr, "POV wheel")!!,
                rssi = Int.MIN_VALUE, seenAgo = Long.MAX_VALUE,
                link = lk, error = c.lastError,
                soc = if (lk == Link.Ready) t.soc else 0,
                rpm = if (lk == Link.Ready) t.rpm else 0f,
                usb = lk == Link.Ready && t.usb,
                playing = if (lk == Link.Ready) t.file else "",
                known = known.contains(addr)
            )
        }

        // Порядок: сначала живые, потом слышимые, потом всё остальное. Внутри
        // группы — по имени, а НЕ по уровню сигнала: сортировка по RSSI
        // переставляла строки под пальцем на каждом пакете рекламы.
        wheels.value = byAddr.values.sortedWith(
            compareBy(
                { when (it.link) {
                    Link.Ready -> 0; Link.Connecting -> 1
                    else -> if (it.stale) 3 else 2
                } },
                { it.name.lowercase() },
                { it.address }
            )
        )
    }

    fun bluetoothReady(): Boolean = adapter?.isEnabled == true

    fun client(addr: String?): BleClient? = if (addr == null) null else clients[addr]
    fun currentClient(): BleClient? = client(current.value)

    /** Включить/выключить колесо [addr] в группе синхронизации. */
    fun toggleMirror(addr: String, on: Boolean) {
        val next = mirrorSet.value.toMutableSet()
        if (on) next.add(addr) else next.remove(addr)
        mirrorSet.value = next
        prefs.edit().putStringSet("mirror_set", next).apply()
    }

    /**
     * Колёса-получатели команды/заливки. Вся группа синхронизации — если
     * открытое колесо в неё входит и в группе есть ещё хотя бы одно колесо на
     * связи; иначе только открытое. Возвращаются только клиенты в состоянии
     * Link.Ready.
     */
    private fun mirrorTargets(): List<BleClient> {
        val cur = current.value
        if (cur != null && cur in mirrorSet.value) {
            val live = clients.values.filter {
                it.address in mirrorSet.value && it.link.value == Link.Ready
            }
            if (live.size >= 2) return live
        }
        return listOfNotNull(currentClient()).filter { it.link.value == Link.Ready }
    }

    fun say(msg: String) { toast.value = msg }

    // ---------------------------------------------------------------- поиск

    @SuppressLint("MissingPermission")
    fun startScan() {
        val a = adapter ?: return
        if (!a.isEnabled) { say("Turn Bluetooth on first"); return }
        val scanner = a.bluetoothLeScanner ?: return
        if (scanning.value) return
        scanning.value = true
        // Фильтр по UUID нашего сервиса: колесо его объявляет, и это оставляет
        // за бортом все остальные маячки на улице.
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SVC)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCb)
        } catch (e: Exception) {
            scanning.value = false
            say("Scan failed: " + (e.message ?: "unknown"))
            return
        }
        // Автостопа больше нет. Поиск идёт всё время, пока открыт экран со
        // списком, и его снимает уход с экрана (DisposableEffect), а не таймер.
        // Прежние пятнадцать секунд означали, что колесо, включённое чуть позже,
        // не появлялось вообще — а именно так с ним обычно и обращаются:
        // сначала достают телефон, потом встряхивают колесо.
        scanStopJob?.cancel()
        scanStopJob = null
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanStopJob?.cancel()
        scanStopJob = null
        if (!scanning.value) return
        scanning.value = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val nm = result.scanRecord?.deviceName ?: dev.name ?: "POV wheel"
            val rec = Found(dev.address, nm, result.rssi, System.currentTimeMillis())
            val cur = found.value.toMutableList()
            val i = cur.indexOfFirst { it.address == rec.address }
            if (i >= 0) cur[i] = rec else cur.add(rec)
            // Сортировка живёт в rebuildWheels(): здесь она переставляла бы
            // строки под пальцем на каждом принятом пакете рекламы.
            found.value = cur
            rememberName(dev.address, nm)
            // Через главный поток, а НЕ напрямую: этот колбэк приходит из
            // биндер-потока системы, а rebuildWheels() обходит карту клиентов,
            // которую правят корутины viewModelScope (то есть главный поток).
            // Обход на фоне правки — это ConcurrentModificationException в
            // лучшем случае и зацикливание на рехэше в худшем; ровно поэтому
            // кеш миниатюр рядом сделан на ConcurrentHashMap.
            viewModelScope.launch { rebuildWheels(); onSeenAgain(rec.address) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning.value = false
            say("Scan failed (code " + errorCode + ")")
        }
    }

    // Имена, уже лежащие в prefs. Кеш нужен, чтобы не читать SharedPreferences
    // на каждый принятый пакет рекламы — их приходят десятки в секунду.
    private val nameCache = HashMap<String, String>()

    /**
     * Запомнить имя. НЕ добавляет колесо в «известные»: это делает только
     * [connect], то есть осознанное действие пользователя.
     *
     * Раньше здесь же стоял `known.add(addr)`, и это ломало Forget: колесо
     * вычёркивалось из списка, а следующий же пакет рекламы — через доли
     * секунды — возвращал его обратно. Заодно «известным» становилось каждое
     * чужое колесо, попавшее в эфир.
     */
    private fun rememberName(addr: String, name: String) {
        if (nameCache[addr] == name) return          // ничего не изменилось — не трогаем флеш
        nameCache[addr] = name
        prefs.edit().putString("name_" + addr, name).apply()
    }

    /** Пользователь сам выбрал это колесо — вот теперь оно «известное». */
    private fun markKnown(addr: String) {
        val known = prefs.getStringSet("known", emptySet())!!.toMutableSet()
        if (known.add(addr)) prefs.edit().putStringSet("known", known).apply()
    }

    fun forget(addr: String) {
        disconnect(addr)
        val known = prefs.getStringSet("known", emptySet())!!.toMutableSet()
        known.remove(addr)
        prefs.edit().putStringSet("known", known).remove("name_" + addr).apply()
        nameCache.remove(addr)
        found.value = found.value.filter { it.address != addr }
        rebuildWheels()
    }

    /** Колёса, виденные раньше: список не пуст ещё до того, как поиск что-то найдёт. */
    @SuppressLint("MissingPermission")
    fun knownDevices(): List<Found> {
        val known = prefs.getStringSet("known", emptySet()) ?: emptySet()
        return known.map { Found(it, prefs.getString("name_" + it, "POV wheel")!!, -127, 0L) }
    }

    // ------------------------------------------------------------ соединение

    @SuppressLint("MissingPermission")
    fun connect(addr: String, name: String) {
        val existing = clients[addr]
        if (existing != null) {
            when (existing.link.value) {
                // Уже на связи или в процессе — просто показать.
                Link.Ready -> { selectWheel(addr); return }
                Link.Connecting -> return
                // Мёртвого клиента выбрасываем и пробуем заново. Раньше здесь
                // стоял безусловный выход: клиент оставался в карте после
                // обрыва, и строка списка становилась тупиком — переподключиться
                // можно было только через Forget или перезапуск приложения.
                else -> { watchJobs.remove(addr)?.cancel(); clients.remove(addr)?.close() }
            }
        }
        val a = adapter ?: return
        val dev: BluetoothDevice = try { a.getRemoteDevice(addr) } catch (e: Exception) { return }
        val c = BleClient(ctx, dev)
        clients[addr] = c
        wantConnected.add(addr)
        connected.value = clients.values.toList()
        rememberName(addr, name)
        markKnown(addr)
        // Состояние связи и телеметрия — прямо в сводный список, без участия
        // экрана. Отписываемся вместе с удалением клиента.
        watchJobs[addr]?.cancel()
        watchJobs[addr] = viewModelScope.launch {
            launch { c.link.collect { rebuildWheels() } }
            launch { c.tele.collect { rebuildWheels() } }
        }
        c.onLinkLost = { viewModelScope.launch { onLinkLost(addr) } }
        reconnectJobs.remove(addr)?.cancel()
        rebuildWheels()
        viewModelScope.launch {
            val ok = c.connect()
            connected.value = clients.values.toList()
            rebuildWheels()
            if (!ok) {
                // Клиент НЕ удаляется: в нём лежит причина отказа, и строка
                // списка её показывает (Link.Error + w.error). Тоста здесь нет
                // намеренно: авто-переподключение зовёт connect() по кругу, и
                // всплывашка «Could not connect…» лезла каждые несколько секунд,
                // пока уснувшее по простою колесо не встряхнут.
                return@launch
            }
            // Поиск НЕ останавливаем: у велосипеда колёс два, и второе должно
            // найтись, пока пользователь возится с первым.
            selectWheel(addr)
            // Часы переживают глубокий сон, но после полного обесточивания
            // взяться им неоткуда — любое соединение это повод их выставить.
            try {
                val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
                c.setTime(System.currentTimeMillis() / 1000, tz)
            } catch (_: Exception) {}
            refreshAll()
            startPolling()
        }
    }

    /**
     * Делает колесо активным. Всё, что показывает экран, принадлежит одному
     * устройству, поэтому переключение обязано сбросить и данные, и счётчики
     * опроса: два колеса легко стоят на одинаковых версиях состояния (счётчики
     * растут только при изменениях), и тогда опрос решил бы, что перечитывать
     * нечего, и оставил бы на экране чужие настройки.
     */
    fun selectWheel(addr: String) {
        if (current.value == addr && settingsLoaded.value) return
        livePushJob?.cancel(); livePushJob = null; livePushPending = null
        current.value = addr
        settingsLoaded.value = false
        settings.value = Settings()
        files.value = emptyList()
        fsInfo.value = FsInfo()
        logLines.value = emptyList()
        logTotal = 0
        refreshAll()
        startPolling()
    }

    /**
     * Связь пропала сама. Пока пользователь не сказал «отключить», пробуем
     * вернуться: колесо роняет соединение по совершенно бытовым поводам —
     * уехало за пределы дальности на повороте, ушло в сон по простою, — и
     * заставлять человека каждый раз тыкать в строку незачем.
     *
     * Интервал растёт: колесо, уснувшее по простою, не проснётся, пока его не
     * встряхнут, и долбиться в него раз в секунду значит греть телефон впустую.
     */
    private fun onLinkLost(addr: String) {
        rebuildWheels()
        if (!wantConnected.contains(addr)) return
        reconnectJobs[addr]?.cancel()
        reconnectJobs[addr] = viewModelScope.launch {
            var wait = 3_000L
            while (wantConnected.contains(addr)) {
                delay(wait)
                if (!wantConnected.contains(addr)) return@launch
                if (clients[addr]?.link?.value == Link.Ready) return@launch
                val name = prefs.getString("name_" + addr, "POV wheel")!!
                connect(addr, name)
                // Дожидаемся исхода попытки, иначе следующий виток стартует
                // поверх ещё живой и вышибет её из карты клиентов.
                val c = clients[addr]
                if (c != null) {
                    withTimeoutOrNull(25_000) {
                        c.link.first { it != Link.Connecting }
                    }
                    if (c.link.value == Link.Ready) return@launch
                }
                // Растёт до минуты: колесо, уснувшее по простою, не проснётся,
                // пока его не встряхнут, и долбиться в него раз в три секунды
                // значит греть телефон часами впустую.
                wait = minOf(wait * 2, 60_000L)
            }
        }
    }

    /**
     * Колесо, которого мы ждём, снова в эфире — значит оно уже проснулось, и
     * это куда более надёжный повод для попытки, чем слепой таймер. Именно этот
     * случай и есть основной: колесо засыпает через минуту простоя, а хозяин
     * встряхивает его когда придётся.
     */
    private fun onSeenAgain(addr: String) {
        if (!wantConnected.contains(addr)) return
        val lk = clients[addr]?.link?.value
        if (lk == Link.Ready || lk == Link.Connecting) return
        reconnectJobs[addr]?.cancel()
        reconnectJobs[addr] = viewModelScope.launch {
            connect(addr, prefs.getString("name_" + addr, "POV wheel")!!)
        }
    }

    fun disconnect(addr: String) {
        wantConnected.remove(addr)
        reconnectJobs.remove(addr)?.cancel()
        watchJobs.remove(addr)?.cancel()
        magnetByAddr.remove(addr)
        clients.remove(addr)?.also { it.onLinkLost = null; it.close() }
        connected.value = clients.values.toList()
        rebuildWheels()
        if (current.value == addr) {
            val next = clients.keys.firstOrNull()
            current.value = null
            settingsLoaded.value = false
            if (next != null) selectWheel(next)
        }
    }

    fun disconnectAll() {
        wantConnected.clear()
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        watchJobs.values.forEach { it.cancel() }
        watchJobs.clear()
        clients.values.forEach { it.onLinkLost = null; it.close() }
        clients.clear()
        magnetByAddr.clear()
        connected.value = emptyList()
        current.value = null
        rebuildWheels()
    }

    // ------------------------------------------------------------------ опрос

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            // Локальны для запуска: selectWheel() перезапускает цикл, и вместе
            // с ним обнуляются сравнения с версиями предыдущего колеса.
            var lastState = -1L
            var lastFile = -1L
            while (true) {
                val c = currentClient()
                if (c != null && c.link.value == Link.Ready) {
                    val t = c.tele.value
                    // Устройство увеличивает эти счётчики при любом изменении,
                    // поэтому правки со второго телефона (или самого колеса)
                    // подхватываются без постоянного опроса всего подряд.
                    if (t.stateVer != lastState) {
                        lastState = t.stateVer
                        val got = runCatching { c.getSettings() }.getOrNull()
                        if (got != null) {
                            adoptSettings(got)
                        } else {
                            // Не удалось прочитать — не отмечаем как загруженные,
                            // и пробуем ещё раз на следующем витке.
                            lastState = -1L
                        }
                    }
                    if (t.fileVer != lastFile) {
                        lastFile = t.fileVer
                        runCatching { files.value = c.list() }
                        runCatching { fsInfo.value = c.fsInfo() }
                    }
                }
                delay(1500)
            }
        }
    }

    fun refreshAll() {
        val c = currentClient() ?: return
        viewModelScope.launch {
            runCatching { c.getSettings() }.getOrNull()?.let { adoptSettings(it) }
            runCatching { files.value = c.list() }
            runCatching { fsInfo.value = c.fsInfo() }
            runCatching { c.telemetry() }
        }
    }

    /**
     * Принять настройки, прочитанные с колеса.
     *
     * Power Limit убран из интерфейса — пользователь его не трогает, — и держится
     * на 100 %. Если на устройстве застряло меньшее значение (его мог оставить
     * старый веб-интерфейс), чиним его один раз: иначе лента светила бы тусклее
     * без всякого объяснения в приложении.
     */
    private fun adoptSettings(got: Settings) {
        val fixed = if (got.ablX10 != 1000) got.copy(ablX10 = 1000) else got
        settings.value = fixed
        settingsLoaded.value = true
        // Запоминаем положение магнита этого колеса, чтобы зеркалирование чужих
        // настроек его не затирало.
        current.value?.let { magnetByAddr[it] = fixed.angle }
        if (fixed !== got) {
            val c = currentClient() ?: return
            viewModelScope.launch { runCatching { c.setSettings(fixed); c.save() } }
        }
    }

    // =================================================================
    //  Панель заливки
    //
    //  Состояние и сама работа живут ЗДЕСЬ, а не в composable. Раньше и то и
    //  другое сидело в UploadPanel, а корутина запускалась из
    //  rememberCoroutineScope(), привязанного к композиции. Стоило уйти с
    //  вкладки Library — панель покидала композицию, scope отменялся, и
    //  многоминутная конвертация с заливкой обрывались на середине. Причём
    //  именно тогда, когда уйти естественнее всего: заливка длинного ролика
    //  идёт минуты, и смотреть всё это время в одну вкладку незачем.
    //
    //  viewModelScope живёт, пока жив экран устройства, поэтому переключение
    //  вкладок и поворот экрана заливку больше не трогают.
    // =================================================================
    /**
     * Один выбранный для заливки файл со СВОИМИ параметрами конвертации. Пачку
     * больше нельзя гнать под одну гребёнку: в панели у каждого превью
     * настраивается отдельно кадрирование, зеркало задней стороны, а для видео —
     * fps и длина.
     */
    data class UpItem(
        val uri: Uri,
        val name: String,
        val isVideo: Boolean = false,
        val srcDur: Double = 0.0,          // длительность исходного ролика, с
        val fit: Int = Fit.CROP,
        val mirror: Boolean = false,       // зеркалить заднюю сторону луча
        val fps: Int = 10,
        val lengthSec: Double = 10.0,
        val lenTouched: Boolean = false,   // правил ли пользователь длину вручную
        val anim: Boolean = false,         // источник анимированный (GIF / WebP / видео)
        val poster: Bitmap? = null,        // круглое превью, null пока считается
        val ready: Boolean = false         // тип определён и постер отрисован
    )

    val upItems    = MutableStateFlow<List<UpItem>>(emptyList())
    val upSel      = MutableStateFlow(0)       // индекс превью, чьи настройки правит ряд пилюль
    val upStatus   = MutableStateFlow("Waiting for a file…")
    val upKind     = MutableStateFlow(0)       // 0 обычный, 1 успех, 2 ошибка
    val upProgress = MutableStateFlow(-1f)
    val upBusy     = MutableStateFlow(false)
    /** Uri файла, который льётся прямо сейчас — его ячейка в сетке рисует
     *  сматывающийся ободок по [upProgress]. null — ничего не льётся. */
    val upCurrentUri = MutableStateFlow<Uri?>(null)

    /** Анимированное превью ВЫБРАННОЙ ячейки сетки. Остальные остаются статичными
     *  постерами — держать в памяти клипы всех тридцати файлов ни к чему. */
    val upSelClip  = MutableStateFlow<PreviewClip?>(null)
    private var selClipJob: kotlinx.coroutines.Job? = null

    /** Фоновая подготовка превью. Новый выбор файлов отменяет прошлую. */
    private var prepJob: kotlinx.coroutines.Job? = null

    private val converter by lazy { Converter(ctx) }

    // Сторона квадратного постера-превью в пикселях. В сетке он показывается
    // мелко (34…104 dp), так что больше не нужно.
    private val POSTER_PX = 200

    /** Выбрали файлы — строим пачку и запускаем фоновую подготовку превью. */
    fun onFilesPicked(picked: List<Uri>) {
        if (picked.isEmpty()) return
        prepJob?.cancel()
        selClipJob?.cancel()
        upSelClip.value = null
        upItems.value = emptyList()
        upSel.value = 0
        upProgress.value = -1f
        upStatus.value = "Reading " + picked.size + " file(s)…"
        upKind.value = 0
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                picked.map { UpItem(it, converter.displayName(it)) }
            }
            upItems.value = items
            upStatus.value = if (items.size > 1) items.size.toString() + " files selected. Press Upload."
                             else items[0].name + " ready. Press Upload."
            upKind.value = 1
            startPrep(items.indices.toList())
        }
    }

    /**
     * Определяет тип и рисует круглое превью для перечисленных позиций — по
     * одной, чтобы не грузить процессор всей пачкой сразу. Тип (видео/картинка)
     * и длительность ролика проставляются раньше постера: ряд пилюль должен
     * знать, показывать ли fps/длину, ещё до готовности превью.
     */
    private fun startPrep(indices: List<Int>) {
        prepJob?.cancel()
        prepJob = viewModelScope.launch {
            for (i in indices) {
                if (!isActive) break
                val item0 = upItems.value.getOrNull(i) ?: continue
                val u = item0.uri
                if (!item0.ready) {
                    val kind = withContext(Dispatchers.IO) {
                        val sniff = try {
                            if (converter.mimeOf(u).startsWith("video/")) null
                            else converter.readBytes(u)
                        } catch (e: Exception) { null }
                        converter.kindOf(u, sniff)
                    }
                    val isVid = kind == Converter.Kind.VIDEO
                    val dur = if (isVid) withContext(Dispatchers.IO) { converter.videoDurationSec(u) } else 0.0
                    updateByUri(u) {
                        it.copy(isVideo = isVid, anim = kind != Converter.Kind.IMAGE, srcDur = dur,
                            lengthSec = if (isVid && !it.lenTouched) defaultLengthSec(it.fps, dur)
                                        else it.lengthSec)
                    }
                }
                val fit = upItems.value.firstOrNull { it.uri == u }?.fit ?: Fit.CROP
                val poster = withContext(Dispatchers.Default) { converter.posterOf(u, fit, POSTER_PX) }
                updateByUri(u) { it.copy(poster = poster, ready = true) }
                // Готова выбранная ячейка — заводим её анимированное превью.
                if (upItems.value.getOrNull(upSel.value)?.uri == u) refreshSelClip()
            }
        }
    }

    /**
     * Пересобирает анимированное превью выбранной ячейки. Клип строится только
     * для неё: держать в памяти по клипу на каждый из тридцати возможных файлов
     * незачем, а именно эту ячейку пользователь сейчас и разглядывает.
     */
    private fun refreshSelClip() {
        selClipJob?.cancel()
        val cur = upItems.value.getOrNull(upSel.value)
        // Прежний клип не утилизируем — его ещё может рисовать ячейка; освободит
        // сборщик. Один клип за раз, счёт идёт на мегабайты, не на десятки.
        upSelClip.value = null
        if (cur == null || !cur.ready || !cur.anim) return
        val uri = cur.uri
        val fit = cur.fit
        selClipJob = viewModelScope.launch {
            val clip = withContext(Dispatchers.Default) {
                runCatching {
                    converter.previewClip(uri, fit, PreviewClips.UPLOAD_PX, PreviewClips.UPLOAD_FRAMES)
                }.getOrNull()
            }
            val now = upItems.value.getOrNull(upSel.value)
            if (isActive && now != null && now.uri == uri && now.fit == fit) {
                upSelClip.value = clip
            } else {
                clip?.recycle()   // не показан — освобождаем сразу
            }
        }
    }

    private fun updateByUri(uri: Uri, f: (UpItem) -> UpItem) {
        val cur = upItems.value
        val i = cur.indexOfFirst { it.uri == uri }
        if (i < 0) return
        upItems.value = cur.toMutableList().also { it[i] = f(it[i]) }
    }

    private fun updateSel(f: (UpItem) -> UpItem) {
        val cur = upItems.value
        val i = upSel.value
        if (i !in cur.indices) return
        upItems.value = cur.toMutableList().also { it[i] = f(it[i]) }
    }

    fun selectUpItem(i: Int) {
        if (i in upItems.value.indices) { upSel.value = i; refreshSelClip() }
    }

    /** Убрать один файл из пачки. */
    fun removeUpItem(i: Int) {
        val cur = upItems.value
        if (i !in cur.indices) return
        val next = cur.toMutableList().also { it.removeAt(i) }
        upItems.value = next
        upSel.value = upSel.value.coerceIn(0, maxOf(0, next.size - 1))
        if (next.isEmpty()) {
            upStatus.value = "Waiting for a file…"
            upKind.value = 0
        }
        refreshSelClip()
    }

    /** Смена кадрирования выбранного файла — перерисовываем его превью. */
    fun setFit(f: Int) {
        updateSel { it.copy(fit = f) }
        val u = upItems.value.getOrNull(upSel.value)?.uri ?: return
        viewModelScope.launch {
            val poster = withContext(Dispatchers.Default) { converter.posterOf(u, f, POSTER_PX) }
            // Если кадрирование за это время снова сменили — отдаём ход более
            // свежей отрисовке, а не подсовываем устаревшую.
            updateByUri(u) { if (it.fit == f) it.copy(poster = poster) else it }
        }
        refreshSelClip()
    }

    fun setBackMirror(v: Boolean) = updateSel { it.copy(mirror = v) }

    /** Длина по умолчанию для данного fps: весь ролик, но не больше, чем влезает
     *  в PSRAM. Потолок — по числу кадров, поэтому в секундах он зависит от fps. */
    private fun defaultLengthSec(fps: Int, srcDur: Double): Double {
        val cap = fsInfo.value.maxFrames.toDouble() / fps
        return maxOf(0.5, minOf(if (srcDur > 0) srcDur else cap, cap))
    }

    fun setFps(n: Int) = updateSel {
        val cap = fsInfo.value.maxFrames.toDouble() / n
        // Длину руками не трогали — ведём за fps, и вверх и вниз. Тронутую
        // оставляем, но ужимаем, если перестала влезать. Правило то же, что в вебе.
        val len = if (!it.lenTouched || it.lengthSec > cap) defaultLengthSec(n, it.srcDur) else it.lengthSec
        it.copy(fps = n, lengthSec = len)
    }

    fun setLength(v: Double) = updateSel {
        // coerceIn(min,max) бросает при min > max, а потолок на забитом флеше
        // падает ниже половины секунды.
        val cap = fsInfo.value.maxFrames.toDouble() / it.fps
        it.copy(lengthSec = if (cap <= 0.5) 0.5 else v.coerceIn(0.5, cap), lenTouched = true)
    }

    /** Скопировать настройки выбранного файла на все остальные в пачке. */
    fun applyUpSettingsToAll() {
        val s = upItems.value.getOrNull(upSel.value) ?: return
        val changedFit = ArrayList<Int>()
        upItems.value = upItems.value.mapIndexed { i, item ->
            if (item.fit != s.fit) changedFit.add(i)
            val len = if (item.isVideo) {
                val cap = fsInfo.value.maxFrames.toDouble() / s.fps
                if (s.lengthSec > cap || cap <= 0.5) defaultLengthSec(s.fps, item.srcDur) else s.lengthSec
            } else item.lengthSec
            item.copy(
                fit = s.fit, mirror = s.mirror,
                fps = if (item.isVideo) s.fps else item.fps,
                lengthSec = len,
                lenTouched = if (item.isVideo) s.lenTouched else item.lenTouched
            )
        }
        if (changedFit.isNotEmpty()) {
            val pending = upItems.value.indices.filter { !upItems.value[it].ready }
            startPrep((changedFit + pending).distinct().sorted())
        }
    }

    fun startUpload() {
        if (upBusy.value) return
        val list = upItems.value
        if (list.isEmpty()) { upStatus.value = "Select a file first."; upKind.value = 2; return }

        // Только колёса НА СВЯЗИ и Link.Ready — mirrorTargets() уже это
        // фильтрует. Спящее колесо из группы раньше роняло весь пакет: файлы
        // честно уезжали на живое, но считались неудачей.
        //
        // Адреса, а не сами объекты: авто-переподключение создаёт НОВЫЙ
        // BleClient, и захваченная на всю пачку ссылка указывала бы на
        // закрытый.
        val targetAddrs = mirrorTargets().map { it.address }
        if (targetAddrs.isEmpty()) { upStatus.value = "Not connected."; upKind.value = 2; return }

        // Пачку снимаем целиком СЕЙЧАС. Настройки у каждого файла свои и лежат в
        // неизменяемом UpItem, а ряд пилюль всё равно заблокирован, пока upBusy, —
        // так что правка настроек на уже идущую заливку не влияет.
        val jobs = list.toList()

        // Сканирование и заливка делят одно радио: LOW_LATENCY-поиск поверх
        // передачи отбирает у неё эфир. Экран списка колёс сам возобновит
        // поиск, когда заливка кончится.
        stopScan()

        upBusy.value = true
        upKind.value = 0
        viewModelScope.launch {
            // Свежий fs_info: место во флеше могло измениться. Потолок кадров
            // устройство считает от ПОЛНОГО объёма PSRAM (играет всегда одна
            // анимация), так что он не зависит от того, что сейчас на ободе.
            runCatching { currentClient()?.fsInfo()?.let { fsInfo.value = it } }
            val jobMaxFrames = fsInfo.value.maxFrames
            var ok = 0
            var fail = 0
            // Uri тех файлов, что не уехали ни на одно колесо. По окончании в
            // сетке остаются только они — успешные убираем, чтобы можно было
            // повторить одной кнопкой, не разбираясь заново, что не долилось.
            val failedUris = ArrayList<Uri>()
            for (item in jobs) {
                val label = item.name
                upCurrentUri.value = item.uri
                try {
                    upStatus.value = label + " — converting…"
                    upProgress.value = -1f
                    val res = withContext(Dispatchers.Default) {
                        converter.convert(
                            item.uri, item.fit, jobMaxFrames,
                            Converter.VideoOpts(item.fps, item.lengthSec),
                            item.mirror,
                            object : Converter.Progress {
                                override fun stage(text: String) { upStatus.value = label + " — " + text }
                                override fun frames(done: Int, total: Int) {
                                    upStatus.value = label + " — converting " + done + "/" + total +
                                        " frames (" + (done * 100 / maxOf(total, 1)) + "%)"
                                    upProgress.value = done.toFloat() / maxOf(total, 1)
                                }
                            }
                        )
                    }
                    res.warning?.let { say(it) }
                    val crc = withContext(Dispatchers.Default) { Ani6.crc32(res.data) }

                    var sentTo = 0
                    for (addr in targetAddrs) {
                        // Клиента ищем по адресу на каждом шаге: за время
                        // конвертации связь могла оборваться и восстановиться
                        // уже другим объектом.
                        val c = client(addr)
                        if (c == null || c.link.value != Link.Ready) continue
                        // Все прочие подключённые колёса — на «мягкий» линк: два
                        // активных BLE-соединения делят радио телефона, и без
                        // этого заливка на активное колесо шла ~20 вместо ~110 кБ/с.
                        focusUploadLink(addr)
                        try {
                            val wire = withContext(Dispatchers.Default) {
                                Ani6.encodeForWire(res.data, c.hello?.hasDeflate ?: false)
                            }
                            val ratio = res.data.size.toDouble() / maxOf(wire.bytes.size, 1)
                            val started = System.currentTimeMillis()
                            // При зеркале — на какое колесо льём прямо сейчас.
                            val toWheel = if (targetAddrs.size > 1)
                                " → " + (c.hello?.name ?: addr) else ""
                            c.upload(res.fileName, wire.bytes, res.data.size, crc, wire.compressed) { p ->
                                upProgress.value = p.sent.toFloat() / maxOf(p.totalWire, 1L)
                                val kb = p.sent / 1024
                                val tot = p.totalWire / 1024
                                val secs = (System.currentTimeMillis() - started) / 1000.0
                                val rate = if (secs > 0.4) (p.sent / 1024.0 / secs) else 0.0
                                upStatus.value = label + toWheel + " — " +
                                    (p.sent * 100 / maxOf(p.totalWire, 1L)) + "%  ·  " +
                                    kb + " / " + tot + " kB" +
                                    (if (wire.compressed) String.format("  ·  x%.1f smaller", ratio) else "") +
                                    (if (rate > 0) String.format("  ·  %.0f kB/s", rate) else "")
                            }
                            sentTo++
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // Неудача на одном колесе не должна отменять успех
                            // на остальных и не делает файл непринятым.
                            say((c.hello?.name ?: addr) + ": " + (e.message ?: "upload failed"))
                        }
                    }
                    if (sentTo > 0) {
                        ok++
                        // Уехал хотя бы на одно колесо — рендерим и кладём в кэш
                        // компактное превью, пока доступ к исходнику ещё жив.
                        cachePreview(item, res.fileName)
                        // ...и сразу переносим ячейку из «ждёт заливки» в библиотеку:
                        // сначала показываем новый файл, потом убираем жёлтую ячейку.
                        runCatching { currentClient()?.let { files.value = it.list() } }
                        upItems.value = upItems.value.filter { it.uri != item.uri }
                        upSel.value = upSel.value.coerceIn(0, maxOf(0, upItems.value.size - 1))
                        refreshSelClip()
                    } else { fail++; failedUris.add(item.uri) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Отмену пробрасываем: иначе цикл продолжал бы крутиться
                    // после смерти scope, дописывая в мёртвые соединения.
                    throw e
                } catch (e: Throwable) {
                    // Throwable, а не Exception: длинная анимация — это 8 МБ
                    // исходника плюс столько же под сжатый поток, и
                    // OutOfMemoryError здесь вполне достижим. Он наследуется от
                    // Error, мимо catch(Exception) проходил насквозь и ронял
                    // приложение вместо сообщения об ошибке.
                    fail++
                    failedUris.add(item.uri)
                    val why = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                    upStatus.value = label + " — failed: " + why
                    upKind.value = 2
                }
            }
            upProgress.value = -1f
            upCurrentUri.value = null
            upBusy.value = false
            restoreUploadLinks()   // все колёса обратно на быстрый линк
            if (fail == 0) {
                val msg = if (ok == 1) "Uploaded." else ok.toString() + " files uploaded."
                upStatus.value = msg
                upKind.value = 1
                upItems.value = emptyList()
                upSel.value = 0
                // Полоса заливки уже скрылась (очередь пуста) — сообщаем тостом.
                say(msg)
            } else {
                upStatus.value = ok.toString() + " uploaded, " + fail + " failed."
                upKind.value = 2
                // В сетке оставляем только незагруженные.
                upItems.value = upItems.value.filter { it.uri in failedUris }
                upSel.value = 0
            }
            refreshSelClip()
            refreshFiles()
        }
    }

    /** Перед заливкой на [addr]: его линк — быстрый, все прочие подключённые —
     *  «мягкие». Два активных BLE-соединения на один радиомодуль телефона делят
     *  эфир, из-за чего скорость на активное колесо падала впятеро. */
    private suspend fun focusUploadLink(addr: String?) {
        val cs = clients.values.toList()
        if (cs.size < 2) return
        for (c in cs) if (c.link.value == Link.Ready) c.setLowPower(c.address != addr)
    }

    private suspend fun restoreUploadLinks() {
        for (c in clients.values.toList()) if (c.link.value == Link.Ready) c.setLowPower(false)
    }

    fun refreshFiles() {
        val c = currentClient() ?: return
        viewModelScope.launch {
            runCatching { files.value = c.list() }
            runCatching { fsInfo.value = c.fsInfo() }
            // Убрать превью удалённых файлов и удержать кэш в пределах потолка.
            prunePreviewCache(files.value.map { it.name }.toHashSet(), strict = connected.value.size <= 1)
        }
    }

    /**
     * Сносит превью, для которых на текущем колесе больше нет файла (только при
     * одном подключении — при зеркале у колёс разные библиотеки), и обрезает
     * кэш по размеру, начиная со самых давних.
     */
    private fun prunePreviewCache(keep: Set<String>, strict: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val list = previewDir.listFiles() ?: return@runCatching
                var total = 0L
                for (pf in list.sortedByDescending { it.lastModified() }) {
                    // .tmp-обрывок не трогаем: следующая запись того же имени его
                    // усечёт, а гонка с идущим save() тут ни к чему.
                    if (!pf.name.endsWith(".pvc")) continue
                    if (strict && pf.name.removeSuffix(".pvc") !in keep) { pf.delete(); continue }
                    total += pf.length()
                    if (total > CLIP_DISK_MAX) pf.delete()
                }
            }
        }
    }

    /**
     * Локальный анимированный клип для файла — из кэша, дописанного при заливке.
     * `null` — вызывающий откатывается на статичную миниатюру [thumb] (файл залит
     * с другого устройства, из веб-интерфейса или ещё до этой версии).
     */
    suspend fun localClip(f: DevFile): PreviewClip? {
        val key = f.name
        synchronized(clipMem) { clipMem[key] }?.let { return it }
        val file = PreviewClips.fileFor(previewDir, f.name)
        val clip = withContext(Dispatchers.IO) {
            if (!file.exists()) null
            else PreviewClips.load(file)?.also {
                runCatching { file.setLastModified(System.currentTimeMillis()) }
            }
        } ?: return null
        synchronized(clipMem) {
            val hit = clipMem[key]
            if (hit != null) { clip.recycle(); return hit }
            clipMem[key] = clip
        }
        return clip
    }

    /**
     * Рендерит компактное превью только что залитого файла и кладёт в кэш на
     * диске. Идёт в фоне: для видео это ещё десяток перемоток MMR, а доступ к
     * исходнику по BLE не нужен вовсе — превью строится из локального файла.
     */
    private fun cachePreview(item: UpItem, deviceName: String) {
        if (!item.anim) return
        viewModelScope.launch(Dispatchers.Default) {
            val clip = runCatching {
                converter.previewClip(item.uri, item.fit, PreviewClips.CACHE_PX, PreviewClips.CACHE_FRAMES)
            }.getOrNull()
            if (clip != null && clip.animated) {
                runCatching { PreviewClips.save(PreviewClips.fileFor(previewDir, deviceName), clip) }
            }
            clip?.recycle()   // построен здесь, в UI не попадал
            // Прежний разобранный клип (если файл перезаливают) просто убираем из
            // карты — вдруг его ещё рисует строка; следующее чтение возьмёт новый
            // с диска, а бамп версии это чтение и запустит.
            synchronized(clipMem) { clipMem.remove(deviceName) }
            previewVersion.value = previewVersion.value + 1
        }
    }

    // --------------------------------------------------------------- команды

    /**
     * Выполняет [block] на открытом колесе, а если оно входит в группу
     * синхронизации — на всей группе (см. [mirrorTargets]).
     */
    private fun onTargets(block: suspend (BleClient) -> Unit) {
        val targets = mirrorTargets()
        viewModelScope.launch {
            for (c in targets) {
                if (c.link.value != Link.Ready) continue
                try { block(c) } catch (e: Exception) {
                    say((c.hello?.name ?: c.address) + ": " + (e.message ?: "failed"))
                }
            }
        }
    }

    fun play(name: String) = onTargets { it.play(name) }.also {
        // «Playing …» с человекочитаемым именем и размером файла — по одному
        // взгляду видно, сколько какой ролик весит.
        val f = files.value.firstOrNull { it.name == name }
        val size = f?.size?.let { s ->
            if (s >= 1_048_576) String.format("%.1f MB", s / 1_048_576.0)
            else (s / 1024).toString() + " kB"
        }
        say("Playing " + (f?.pretty ?: name) + (if (size != null) "  ·  " + size else ""))
    }
    fun stopDisplay() = onTargets { it.stop() }.also { say("Display stopped") }
    fun effect(id: Int) = onTargets { it.effect(id) }
    fun album(start: Boolean, ms: Int) = onTargets { it.album(start, ms) }

    // ---------------------------------------------------------- слайдшоу

    private fun slideSelKey(addr: String) = "slidesel_" + addr

    /** Токены эффектов в наборе выбранного для слайдшоу: `@e1`..`@e6`. */
    val slideEffectTokens: List<String> = (1..6).map { "@e" + it }
    fun isSlideEffect(token: String) = token in slideEffectTokens

    /** Отмеченное для слайдшоу из прошлого раза: имена файлов (пересечённые с
     *  реально лежащими на колесе) плюс токены эффектов. Ничего не сохранено —
     *  все файлы, без эффектов (эффекты добавляются вручную). */
    fun savedSlideSelection(fileNames: List<String>): Set<String> {
        val addr = current.value ?: return fileNames.toSet()
        val raw = prefs.getString(slideSelKey(addr), null) ?: return fileNames.toSet()
        val saved = raw.split(",").filter { it.isNotEmpty() }.toSet()
        val kept = fileNames.filter { it in saved } + slideEffectTokens.filter { it in saved }
        return if (kept.isEmpty()) fileNames.toSet() else kept.toSet()
    }

    /** Запустить слайдшоу с отмеченным [checked] (имена файлов + токены эффектов). */
    fun startSlideshow(delaySecs: Int, checked: Set<String>, fileNames: List<String>) {
        if (checked.isEmpty()) { say("Tick at least one item"); return }
        current.value?.let {
            prefs.edit().putString(slideSelKey(it), checked.joinToString(",")).apply()
        }
        val effMask = slideEffectTokens.foldIndexed(0) { i, m, t -> if (t in checked) m or (1 shl i) else m }
        val incl = fileNames.filter { it in checked }
        val excl = fileNames.filter { it !in checked }
        // Одна ATT-посылка — до ~20 имён файлов; шлём тот список, что короче.
        val (mode, names) = when {
            excl.isEmpty() -> 0 to emptyList()              // exclude нечего = все файлы
            incl.size <= excl.size && incl.size <= 20 -> 1 to incl
            excl.size <= 20 -> 0 to excl
            else -> { say("Too many files to pick one by one — all files shown"); 0 to emptyList<String>() }
        }
        val ms = (delaySecs * 1000).coerceIn(1000, 300000)
        onTargets { it.album(true, ms, mode, names, effMask) }
        say("Slideshow started")
    }

    fun stopSlideshow() = onTargets { it.album(false, 0) }.also { say("Slideshow stopped") }

    /** Интервал: если слайдшоу идёт — устройство подхватит на лету, не сбрасывая позицию. */
    fun setSlideInterval(secs: Int) {
        val ms = (secs * 1000).coerceIn(1000, 300000)
        onTargets { c -> if (c.tele.value.slideshow) c.album(true, ms) }
    }
    /**
     * Переименовать ТЕКУЩЕЕ колесо. Намеренно мимо onTargets: зеркалирование
     * здесь бессмысленно — два колеса с одинаковым именем ровно та задача,
     * которую переименование и решает.
     */
    fun renameCurrent(name: String, onDone: (String) -> Unit) {
        val c = currentClient()
        val addr = current.value
        if (c == null || addr == null) { say("Not connected"); return }
        if (!Proto.nameOk(name)) {
            onDone("Latin letters, digits, - and _ only, up to " + Proto.NAME_MAX)
            return
        }
        viewModelScope.launch {
            try {
                c.setName(name)
                // Локальный кеш правим сами: пока телефон подключён, колесо
                // рекламы не шлёт, и нового имени из эфира взяться неоткуда.
                nameCache.remove(addr)
                rememberName(addr, name)
                // И в списке найденных тоже: строку колеса rebuildWheels()
                // подписывает именем из результатов сканирования, а подключённое
                // колесо рекламы не шлёт — новое имя оттуда не приедет до самого
                // отключения, и переименование выглядело бы как не сработавшее.
                found.value = found.value.map {
                    if (it.address == addr) it.copy(name = name) else it
                }
                rebuildWheels()
                onDone("Renamed to " + name)
            } catch (e: Exception) {
                onDone("Rename failed: " + (e.message ?: "unknown"))
            }
        }
    }

    fun reboot() = onTargets { it.reboot() }
    fun wifi(on: Boolean) = onTargets { it.wifi(on) }

    /**
     * Выключение в транспортный режим: колесо гаснет и до удержания кнопки уже
     * не проснётся — ни по тряске, ни по BLE. Если открытое колесо в группе
     * синхронизации — гасит всю группу разом, как и «Reboot».
     */
    fun powerOff() = onTargets { it.powerOff() }

    /**
     * Прошивка по BLE. Транспорт тот же, что у анимаций, но сжатия нет:
     * образ и так почти несжимаем, а устройство пишет его в раздел OTA
     * напрямую, минуя распаковщик (см. OP_OTA_BEGIN в povble.cpp).
     *
     * Идёт ТОЛЬКО на открытое колесо, даже когда оно в группе синхронизации:
     * залить одну прошивку сразу в два устройства значит на время передачи
     * ослепнуть на оба, а если образ окажется битым — остаться без обоих сразу.
     */
    fun updateFirmware(uri: android.net.Uri, onDone: (String) -> Unit) {
        val c = currentClient()
        if (c == null) { say("Not connected"); return }
        viewModelScope.launch {
            try {
                val img = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw Exception("cannot read the file")
                if (img.size < 64 * 1024) throw Exception("that file is too small to be firmware")
                // Магический байт образа ESP32. Залить в раздел OTA случайный
                // файл — это кирпич до кабеля, и проверка здесь стоит одного
                // сравнения.
                if (img[0] != 0xE9.toByte()) throw Exception("not an ESP32 firmware image")
                val crc = java.util.zip.CRC32().run { update(img); value }
                c.otaUpdate(img, crc) { p ->
                    fwProgress.value = if (p.totalWire > 0)
                        p.sent.toFloat() / p.totalWire.toFloat() else 0f
                }
                fwProgress.value = null
                onDone("Firmware sent — the wheel is rebooting")
            } catch (e: Exception) {
                fwProgress.value = null
                onDone("Update failed: " + (e.message ?: "unknown"))
            }
        }
    }

    /** Ход прошивки, 0..1. null — не идёт. */
    val fwProgress = MutableStateFlow<Float?>(null)

    fun pushSettings(s: Settings) {
        // Отпускание пальца — это авторитетная отправка: снимаем throttled
        // «живую», чтобы её хвост не перезаписал финальное значение.
        livePushJob?.cancel(); livePushJob = null; livePushPending = null
        if (!settingsLoaded.value) {
            // Отправить сейчас значило бы записать на устройство наши значения
            // по умолчанию вместо его собственных.
            say("Settings are still loading")
            refreshAll()
            return
        }
        settings.value = s
        val cur = current.value
        if (cur != null) magnetByAddr[cur] = s.angle
        onTargets { c ->
            // На активное колесо — блок как есть. На остальные (режим
            // зеркалирования) — с их собственным «Положением магнита»: это
            // калибровка под конкретное колесо, у соседа магнит стоит иначе.
            val out = if (c.address == cur) s
                      else s.copy(angle = mirrorMagnet(c, s.angle))
            c.setSettings(out)
        }
    }

    // --- Живое применение настройки во время перетаскивания ---
    // pushSettings (по отпусканию пальца) шлёт полный блок на всю группу и
    // сохраняет в NVS. pushSettingsLive применяет промежуточные значения СРАЗУ,
    // чтобы результат было видно на ободе прямо в движении пальца. Отличия:
    // только открытое колесо (калибруют по одному), не чаще ~10 раз в секунду —
    // иначе за один жест в очередь BLE встанут десятки записей, которые
    // продолжат уходить и после того, как палец убрали, — и без записи в NVS.
    private var livePushJob: kotlinx.coroutines.Job? = null
    @Volatile private var livePushPending: Settings? = null
    private val LIVE_PUSH_MS = 100L

    fun pushSettingsLive(s: Settings) {
        if (!settingsLoaded.value) return
        settings.value = s
        current.value?.let { magnetByAddr[it] = s.angle }
        livePushPending = s
        if (livePushJob?.isActive == true) return
        livePushJob = viewModelScope.launch {
            while (true) {
                val snap = livePushPending ?: break
                livePushPending = null
                val c = currentClient()
                if (c != null && c.link.value == Link.Ready) {
                    try { c.setSettings(snap) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) {}
                }
                delay(LIVE_PUSH_MS)
            }
        }
    }

    /**
     * «Положение магнита», которое нужно оставить не-текущему колесу при
     * зеркалировании настроек. Берём запомненное при последнем чтении его
     * настроек; если колесо ещё ни разу не открывали — дочитываем сейчас и
     * запоминаем. Совсем в крайнем случае (чтение не удалось) — [fallback],
     * то есть угол активного колеса: не идеально, но не хуже прежнего поведения.
     */
    private suspend fun mirrorMagnet(c: BleClient, fallback: Int): Int =
        magnetByAddr[c.address]
            ?: runCatching { c.getSettings().angle }.getOrNull()
                ?.also { magnetByAddr[c.address] = it }
            ?: fallback

    fun saveSettings() = onTargets { it.save() }

    fun delete(name: String) = deleteMany(listOf(name))

    /** Удаляет несколько файлов с ТЕКУЩЕГО колеса (не зеркалит: у колёс разные
     *  библиотеки), заодно чистит их локальные превью. */
    fun deleteMany(names: List<String>) {
        if (names.isEmpty()) return
        val c = currentClient() ?: return
        viewModelScope.launch {
            var ok = 0
            for (n in names) {
                try { c.delete(n); ok++ } catch (e: Exception) { say("Delete failed: " + n) }
                synchronized(clipMem) { clipMem.remove(n) }
                withContext(Dispatchers.IO) {
                    runCatching { PreviewClips.fileFor(previewDir, n).delete() }
                }
            }
            runCatching { files.value = c.list() }
            runCatching { fsInfo.value = c.fsInfo() }
            if (ok == 1) say("Deleted " + names.first())
            else if (ok > 1) say("Deleted " + ok + " files")
        }
    }

    // --------------------------------------------------------------- превью

    suspend fun thumb(f: DevFile): PreviewFrame? {
        val key = f.name + "|" + f.size
        thumbs[key]?.let { return it }
        val c = currentClient() ?: return null
        if (c.hello?.hasPreview == false) return null
        return withContext(Dispatchers.IO) {
            try {
                val p = c.preview(f.name)
                if (p != null) thumbs[key] = p
                p
            } catch (e: Exception) { null }
        }
    }

    // -------------------------------------------------------------------- лог

    /**
     * Номер самой первой строки, лежащей сейчас в [logLines]. Нужен экрану как
     * устойчивый ключ элементов списка: буфер обрезается сверху, поэтому индекс
     * одной и той же строки со временем уменьшается, и без ключа LazyColumn
     * держится за номер позиции — отчего прокрученный вверх лог уезжал вперёд
     * на каждом опросе.
     */
    val logFirstIdx = MutableStateFlow(0L)

    /** Опрос уже в полёте. Без этого медленный ответ дублировал бы строки. */
    private var logBusy = false

    fun pollLog() {
        val c = currentClient() ?: return
        if (logBusy) return
        logBusy = true
        viewModelScope.launch {
            try {
                val (total, lines) = c.logs(logTotal)
                if (total < logTotal) {
                    // Счётчик пошёл назад — колесо перезагрузилось.
                    logLines.value = logLines.value + "──────── DEVICE RESTARTED ────────"
                    logTotal = 0
                }
                if (lines.isNotEmpty()) {
                    val merged = logLines.value + lines
                    val capped = merged.takeLast(400)
                    logFirstIdx.value += (merged.size - capped.size)
                    logLines.value = capped
                    logTotal = total
                }
            } catch (_: Exception) {
            } finally {
                logBusy = false
            }
        }
    }

    fun clearLogView() {
        // Ключи обязаны остаться уникальными и после очистки: сдвигаем базу на
        // выброшенное, иначе новые строки получили бы номера уже показанных.
        logFirstIdx.value += logLines.value.size
        logLines.value = emptyList()
    }

    override fun onCleared() {
        stopScan()
        disconnectAll()
        synchronized(clipMem) { clipMem.clear() }
        super.onCleared()
    }
}
