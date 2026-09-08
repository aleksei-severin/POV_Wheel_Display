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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
     * Дублировать команды на все подключённые колёса. У велосипеда их два, и
     * почти всегда на них нужна одна и та же картинка, так что делать это по
     * очереди — лишняя возня.
     */
    val mirrorAll = MutableStateFlow(prefs.getBoolean("mirror", false))

    // Пишется с Dispatchers.IO, читается из отрисовки списка — обычный HashMap
    // здесь может уйти в бесконечный цикл на рехэше.
    private val thumbs = ConcurrentHashMap<String, PreviewFrame>()
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

    fun setMirror(v: Boolean) {
        mirrorAll.value = v
        prefs.edit().putBoolean("mirror", v).apply()
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
                // списка должна её показать. Следующее нажатие заменит его.
                say("Could not connect to " + name + " — " + (c.lastError ?: "unknown"))
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
    val upUris     = MutableStateFlow<List<Uri>>(emptyList())
    val upPoster   = MutableStateFlow<Bitmap?>(null)
    val upStatus   = MutableStateFlow("Waiting for a file…")
    val upKind     = MutableStateFlow(0)       // 0 обычный, 1 успех, 2 ошибка
    val upProgress = MutableStateFlow(-1f)
    val upBusy     = MutableStateFlow(false)
    val upFit      = MutableStateFlow(Fit.CROP)
    val upFps      = MutableStateFlow(10)
    val upLength   = MutableStateFlow(10.0)
    // Зеркалить заднюю сторону колеса. Выключено по умолчанию: спереди и сзади
    // горят те же пиксели. Включить — чтобы текст читался с обеих сторон.
    // (Не путать с mirrorAll — то про рассылку команд на все колёса.)
    val upBackMirror = MutableStateFlow(false)
    val upIsVideo  = MutableStateFlow(false)
    val upSrcDur   = MutableStateFlow(0.0)   // длительность исходного ролика, с
    // Правил ли пользователь поле Length вручную. Пока не правил — длина следует
    // за fps: потолок задаётся числом кадров в PSRAM, поэтому при 5 к/с в память
    // влезает втрое больше секунд, чем при 15, и поле должно это показывать —
    // и вверх, и вниз, а не залипать на самой короткой длительности.
    private var upLenTouched = false

    private val converter by lazy { Converter(ctx) }

    /** Выбрали файлы — определяем тип и готовим миниатюру. */
    fun onFilesPicked(picked: List<Uri>) {
        if (picked.isEmpty()) return
        upUris.value = picked
        upSrcDur.value = 0.0
        upLenTouched = false        // новый файл — длину снова ведёт fps
        viewModelScope.launch {
            val first = picked.first()
            val kind = withContext(Dispatchers.IO) {
                val sniff = try {
                    if (converter.mimeOf(first).startsWith("video/")) null
                    else converter.readBytes(first)
                } catch (e: Exception) { null }
                converter.kindOf(first, sniff)
            }
            upIsVideo.value = kind == Converter.Kind.VIDEO
            if (upIsVideo.value) {
                val dur = withContext(Dispatchers.IO) { converter.videoDurationSec(first) }
                upSrcDur.value = dur
                upLength.value = defaultLengthSec(upFps.value)
            }
            upPoster.value = withContext(Dispatchers.Default) {
                converter.posterOf(first, upFit.value, 216)
            }
            upStatus.value = if (picked.size > 1) picked.size.toString() + " files selected. Press Upload."
                             else converter.displayName(first) + " ready. Press Upload."
            upKind.value = 1
        }
    }

    /** Смена кадрирования — перерисовываем миниатюру, как это делал веб. */
    fun setFit(f: Int) {
        upFit.value = f
        val u = upUris.value.firstOrNull() ?: return
        viewModelScope.launch {
            upPoster.value = withContext(Dispatchers.Default) { converter.posterOf(u, f, 216) }
        }
    }

    fun setBackMirror(v: Boolean) { upBackMirror.value = v }

    /** Длина по умолчанию для данного fps: весь ролик, но не больше, чем влезает
     *  в PSRAM. Потолок — по числу кадров, поэтому в секундах он зависит от fps. */
    private fun defaultLengthSec(fps: Int): Double {
        val cap = fsInfo.value.maxFrames.toDouble() / fps
        val dur = upSrcDur.value
        return maxOf(0.5, minOf(if (dur > 0) dur else cap, cap))
    }

    fun setFps(n: Int) {
        upFps.value = n
        val cap = fsInfo.value.maxFrames.toDouble() / n
        // Длину руками не трогали — пересчитываем под новый fps, и вверх, и вниз.
        // Тронутую — оставляем как есть, но ужимаем до разумного максимума
        // (весь ролик / сколько влезает), если перестала влезать. Правило то же,
        // что в вебе.
        if (!upLenTouched || upLength.value > cap) upLength.value = defaultLengthSec(n)
    }

    fun setLength(v: Double) {
        upLenTouched = true
        // coerceIn(min, max) бросает IllegalArgumentException при min > max, а
        // потолок здесь считается из свободной памяти колеса и на забитом
        // флеше падает ниже половины секунды.
        val cap = fsInfo.value.maxFrames.toDouble() / upFps.value
        upLength.value = if (cap <= 0.5) 0.5 else v.coerceIn(0.5, cap)
    }

    fun startUpload() {
        if (upBusy.value) return
        val list = upUris.value
        if (list.isEmpty()) { upStatus.value = "Select a file first."; upKind.value = 2; return }

        // Только колёса НА СВЯЗИ. connected — это просто снимок карты клиентов:
        // клиент попадает туда до того, как соединение установлено, и остаётся
        // после обрыва (нарочно, чтобы строка списка показала причину). Все
        // прочие команды это фильтруют (см. onTargets), а заливка — нет, и в
        // зеркальном режиме одно спящее колесо роняло весь пакет: файлы честно
        // уезжали на живое, но считались неудачей.
        //
        // Адреса, а не сами объекты: авто-переподключение создаёт НОВЫЙ
        // BleClient, и захваченная на всю пачку ссылка указывала бы на
        // закрытый.
        val targetAddrs = (if (mirrorAll.value) connected.value
                           else listOfNotNull(currentClient()))
            .filter { it.link.value == Link.Ready }
            .map { it.address }
        if (targetAddrs.isEmpty()) { upStatus.value = "Not connected."; upKind.value = 2; return }

        // Параметры конвертации снимаем СЕЙЧАС: их регуляторы остаются
        // доступными, и правка fps в середине пачки иначе применилась бы к
        // части файлов, а к части нет.
        val jobFit = upFit.value
        val jobFps = upFps.value
        val jobLen = upLength.value
        val jobMirror = upBackMirror.value
        val jobMaxFrames = fsInfo.value.maxFrames

        // Сканирование и заливка делят одно радио: LOW_LATENCY-поиск поверх
        // передачи отбирает у неё эфир. Экран списка колёс сам возобновит
        // поиск, когда заливка кончится.
        stopScan()

        upBusy.value = true
        upKind.value = 0
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            for (u in list) {
                val label = converter.displayName(u)
                try {
                    upStatus.value = label + " — converting…"
                    upProgress.value = -1f
                    val res = withContext(Dispatchers.Default) {
                        converter.convert(
                            u, jobFit, jobMaxFrames,
                            Converter.VideoOpts(jobFps, jobLen),
                            jobMirror,
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
                        try {
                            val wire = withContext(Dispatchers.Default) {
                                Ani6.encodeForWire(res.data, c.hello?.hasDeflate ?: false)
                            }
                            val ratio = res.data.size.toDouble() / maxOf(wire.bytes.size, 1)
                            val started = System.currentTimeMillis()
                            c.upload(res.fileName, wire.bytes, res.data.size, crc, wire.compressed) { p ->
                                upProgress.value = p.sent.toFloat() / maxOf(p.totalWire, 1L)
                                val kb = p.sent / 1024
                                val tot = p.totalWire / 1024
                                val secs = (System.currentTimeMillis() - started) / 1000.0
                                val rate = if (secs > 0.4) (p.sent / 1024.0 / secs) else 0.0
                                upStatus.value = label + " — " +
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
                    if (sentTo > 0) ok++ else fail++
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
                    val why = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                    upStatus.value = label + " — failed: " + why
                    upKind.value = 2
                }
            }
            upProgress.value = -1f
            upBusy.value = false
            if (fail == 0) {
                upStatus.value = if (ok == 1) "Uploaded." else ok.toString() + " files uploaded."
                upKind.value = 1
                upUris.value = emptyList()
                upPoster.value = null
            } else {
                upStatus.value = ok.toString() + " uploaded, " + fail + " failed."
                upKind.value = 2
            }
            refreshFiles()
        }
    }

    fun refreshFiles() {
        val c = currentClient() ?: return
        viewModelScope.launch {
            runCatching { files.value = c.list() }
            runCatching { fsInfo.value = c.fsInfo() }
        }
    }

    // --------------------------------------------------------------- команды

    /** Выполняет [block] на текущем колесе, а в режиме дублирования — на всех. */
    private fun onTargets(block: suspend (BleClient) -> Unit) {
        val targets = if (mirrorAll.value) clients.values.toList()
                      else listOfNotNull(currentClient())
        viewModelScope.launch {
            for (c in targets) {
                if (c.link.value != Link.Ready) continue
                try { block(c) } catch (e: Exception) {
                    say((c.hello?.name ?: c.address) + ": " + (e.message ?: "failed"))
                }
            }
        }
    }

    fun play(name: String) = onTargets { it.play(name) }.also { say("Playing " + name) }
    fun stopDisplay() = onTargets { it.stop() }.also { say("Display stopped") }

    /**
     * Кнопка «Пуск», когда ничего явно не выбрано: возобновляем то, что было
     * загружено в колесо, иначе первый файл из библиотеки.
     */
    fun currentPlayAgain() {
        val c = currentClient() ?: return
        val name = c.tele.value.file.ifEmpty { files.value.firstOrNull()?.name ?: "" }
        if (name.isEmpty()) { say("Pick a file in the library first"); return }
        play(name)
    }
    fun effect(id: Int) = onTargets { it.effect(id) }
    fun album(start: Boolean, ms: Int) = onTargets { it.album(start, ms) }
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
     * не проснётся — ни по тряске, ни по BLE. В зеркальном режиме гасит все
     * подключённые колёса разом, как и «Reboot».
     */
    fun powerOff() = onTargets { it.powerOff() }

    /**
     * Прошивка по BLE. Транспорт тот же, что у анимаций, но сжатия нет:
     * образ и так почти несжимаем, а устройство пишет его в раздел OTA
     * напрямую, минуя распаковщик (см. OP_OTA_BEGIN в povble.cpp).
     *
     * Идёт ТОЛЬКО на текущее колесо, даже во включённом зеркале: залить одну
     * прошивку сразу в два устройства значит на время передачи ослепнуть на
     * оба, а если образ окажется битым — остаться без обоих сразу.
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

    fun delete(name: String) {
        val c = currentClient() ?: return
        viewModelScope.launch {
            try {
                c.delete(name)
                say("Deleted " + name)
                files.value = c.list()
                fsInfo.value = c.fsInfo()
            } catch (e: Exception) {
                say("Delete failed: " + (e.message ?: ""))
            }
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
        super.onCleared()
    }
}
