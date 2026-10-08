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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelUuid
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.povwheel.app.ble.BleClient
import com.povwheel.app.ble.BleException
import com.povwheel.app.ble.DevFile
import com.povwheel.app.ble.FsInfo
import com.povwheel.app.ble.FxParams
import com.povwheel.app.ble.Link
import com.povwheel.app.ble.PreviewFrame
import com.povwheel.app.ble.Proto
import com.povwheel.app.ble.Settings
import com.povwheel.app.ble.TextStyle
import com.povwheel.app.convert.Ani6
import com.povwheel.app.convert.Converter
import com.povwheel.app.convert.Fit
import com.povwheel.app.convert.PreviewClip
import com.povwheel.app.convert.PreviewClips
import com.povwheel.app.convert.TextMask
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
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

    /** На связи или хотя бы в эфире — колесо, на которое имеет смысл переключиться
     *  (тап по имени, свайп). Отсеивает известные, но давно не слышные. */
    val reachable: Boolean get() =
        link == Link.Ready || link == Link.Connecting || (!stale && rssi != Int.MIN_VALUE)

    companion object { const val STALE_AFTER_MS = 20_000L }
}

/** Номер эффекта «Текст» — EFF_TEXT в include/effects.h. */
const val TEXT_EFFECT_ID = 7

/** Текст эффекта «Текст», пока пользователь не задал свой (радугой; эмодзи —
 *  своим цветом). У ❤️ обязателен U+FE0F: без него сердце — чёрно-белый
 *  текстовый символ и красилось бы, как буквы. */
const val DEFAULT_TEXT = "🔥😃😍😎Hello World!👍😈❤️👀"

class WheelVm(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()

    /** «Render POV Video» — склейка видео колеса по тикам, см. povvideo/. */
    val povVideo = com.povwheel.app.povvideo.PovVideoController(app, viewModelScope)
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
     * Последнее известное состояние каждого колеса по адресу — библиотека,
     * место на флеше, настройки. При возврате на колесо (тап/свайп) показываем
     * это мгновенно, а `refreshAll` освежает в фоне; без кэша при каждом
     * переключении библиотека на доли секунды пустела, пока не придёт ответ по
     * BLE. Наполняется снимком при уходе с колеса ([selectWheel]) и фоновым
     * префетчем при подключении соседнего колеса ([prefetchWheel]).
     */
    private val filesByAddr    = HashMap<String, List<DevFile>>()
    private val fsInfoByAddr   = HashMap<String, FsInfo>()
    private val settingsByAddr = HashMap<String, Settings>()
    private val prefetched     = HashSet<String>()

    /**
     * Тот же кэш — на диске (`files/wheelcache/<адрес>.json`): библиотека, место
     * и настройки последнего, что колесо показало, пока было на связи. Нужен,
     * чтобы экран колеса без связи (даже после перезапуска приложения) остался
     * тем, с чем человек работал, — серым и без управления, но не пустым
     * «Connecting to…». Пишется фоном, только когда снимок изменился.
     */
    private val cacheDir by lazy { File(ctx.filesDir, "wheelcache").also { it.mkdirs() } }
    private val cacheWritten = HashMap<String, String>()

    private fun cacheFile(addr: String) = File(cacheDir, addr.replace(":", "") + ".json")

    private fun cacheJson(fl: List<DevFile>, fs: FsInfo, s: Settings?, t: TextFx?, fx: FxParams?): String {
        val o = JSONObject()
        o.put("files", JSONArray().apply { fl.forEach { put(JSONArray().put(it.name).put(it.size)) } })
        o.put("fs", JSONArray().put(fs.total).put(fs.used).put(fs.free).put(fs.psramFree)
            .put(fs.frameStride).put(fs.maxFrames))
        if (s != null) o.put("set", Base64.encodeToString(s.pack(), Base64.NO_WRAP))
        // Текст — как лежит на колесе: цвет и сжатый блоб (строка + маска).
        val tb = t?.blob
        if (t != null && tb != null) o.put("txt", JSONArray()
            .put(Base64.encodeToString(t.style.pack(), Base64.NO_WRAP))
            .put(Base64.encodeToString(tb, Base64.NO_WRAP)))
        if (fx != null) o.put("fx", Base64.encodeToString(fx.pack(), Base64.NO_WRAP))
        return o.toString()
    }

    /** Поднять с диска то, чего нет в памяти (после перезапуска приложения). */
    private fun loadCache(addr: String) {
        if (filesByAddr.containsKey(addr) && fsInfoByAddr.containsKey(addr) && settingsByAddr.containsKey(addr)) return
        val f = cacheFile(addr)
        if (!f.exists()) return
        runCatching {
            val text = f.readText()
            val o = JSONObject(text)
            if (!filesByAddr.containsKey(addr)) o.optJSONArray("files")?.let { a ->
                filesByAddr[addr] = (0 until a.length()).map { val r = a.getJSONArray(it); DevFile(r.getString(0), r.getLong(1)) }
            }
            if (!fsInfoByAddr.containsKey(addr)) o.optJSONArray("fs")?.let { a ->
                fsInfoByAddr[addr] = FsInfo(a.getLong(0), a.getLong(1), a.getLong(2), a.getLong(3), a.getInt(4), a.getInt(5))
            }
            if (!settingsByAddr.containsKey(addr)) o.optString("set").takeIf { it.isNotEmpty() }?.let {
                settingsByAddr[addr] = Settings.parse(Base64.decode(it, Base64.NO_WRAP))
            }
            if (!textFxByAddr.containsKey(addr)) o.optJSONArray("txt")?.let { a ->
                val st = TextStyle.parse(Base64.decode(a.getString(0), Base64.NO_WRAP))
                val blob = Base64.decode(a.getString(1), Base64.NO_WRAP)
                TextMask.unpack(blob)?.let { (txt, img) -> textFxByAddr[addr] = TextFx(txt, st, img.mask, blob, img.rgb) }
            }
            if (!fxByAddr.containsKey(addr)) o.optString("fx").takeIf { it.isNotEmpty() }?.let {
                fxByAddr[addr] = FxParams.parse(Base64.decode(it, Base64.NO_WRAP))
            }
            cacheWritten[addr] = text
        }
    }

    /** Сбросить на диск изменившиеся снимки. Открытое колесо — из живых потоков экрана. */
    private suspend fun saveCaches() {
        val cur = current.value
        val addrs = LinkedHashSet<String>().apply { addAll(filesByAddr.keys); cur?.let { add(it) } }
        for (addr in addrs) {
            val isCur = addr == cur
            val fl = if (isCur) files.value else filesByAddr[addr] ?: continue
            val fs = if (isCur) fsInfo.value else fsInfoByAddr[addr] ?: FsInfo()
            val s = if (isCur && settingsLoaded.value) settings.value else settingsByAddr[addr]
            val t = if (isCur) textFx.value else textFxByAddr[addr]
            // Колесо, о котором ещё ничего не узнали, пустым снимком не затираем.
            if (fl.isEmpty() && fs.total == 0L && s == null) continue
            val js = cacheJson(fl, fs, s, t, fxByAddr[addr])
            if (cacheWritten[addr] == js) continue
            cacheWritten[addr] = js
            withContext(Dispatchers.IO) { runCatching { cacheFile(addr).writeText(js) } }
        }
    }

    // ------------------------------------------------------- синхронизация слайдшоу
    //
    // Показ идёт на самих колёсах по абсолютным часам — см. slideClockStep() в
    // прошивке. Телефон вручает всем участникам одно и то же расписание
    // (OP_ALBUM: отбор в общем порядке, интервал и t0 — момент начала нулевого
    // слота), а дальше только держит часы колёс выставленными (HallSync: точная
    // установка при подключении и подстройка на ходу). Каждое колесо само
    // считает пункт слота: (сейчас − t0) / интервал по модулю длины круга, —
    // границы у всех приходятся на один момент с точностью до хода часов, а не
    // до задержки радио. Фаза анимации тоже считается от начала слота, так что
    // и кадры у колёс совпадают, сколько бы каждое ни грузило файл.
    //
    // Раньше было иначе, и это и давало «показывают разное по нескольку
    // секунд»: телефон раз в интервал слал каждому колесу OP_SYNC_TICK, ждал по
    // телеметрии, пока ВСЕ дочитают файл, и только потом отсчитывал интервал, а
    // под этим у каждого колеса шёл ещё и свой таймер с паузой на тёмную
    // ленту. Задержки BLE, разное время загрузки и два спорящих хода давали
    // разнобой в секунды.
    //
    // Расписание вручается при старте, после каждой правки (интервал, отбор —
    // с новым t0, иначе нумерация слотов перескочила бы посреди круга) и после
    // каждого переподключения колеса ([armSyncMember]): та же присылка повторно
    // ничего не стоит — колесо её узнаёт, ничего не перегружает и не считает
    // активностью. Пока экран открыт, этим занимается ViewModel на своих
    // соединениях; когда Activity закрывают (onCleared), группу забирает
    // SyncSlideshowService — подключается к участникам С НУЛЯ, ПОСЛЕ того как
    // ViewModel отпустила свои соединения: второе, параллельное соединение к
    // ещё занятому адресу ненадёжно (Bluetooth не держит два по-настоящему
    // разных ACL-канала к одному устройству с одного телефона). Если экран
    // открывают заново, restoreSyncStateIfNeeded забирает группу обратно.
    // Без телефона колёса продолжают показ сами; расходятся только часы —
    // десятки мс за час наяву и секунды за час сна, — пока телефон снова не
    // подключится.
    //
    // Группа — два и больше колёс: у велосипеда бывает и третье (запасное,
    // прицеп) — список, а не пара, ничего не усложняет.

    private class SyncGroup(val members: List<String>, var files: List<String>, var intervalMs: Int, var t0Ms: Long) {
        // Общие для группы поля — пороги оборотов, диапазон авто-яркости и
        // весь Color Correction (гамма/насыщенность/контраст/баланс RGB) —
        // общий «источник правды», по которому решаем, действительно ли
        // что-то поменялось (не важно, с какого колеса группы) и надо ли
        // разослать новое значение остальным. -1 — ещё ни разу не выставляли.
        var bmin = -1; var bmax = -1; var rpmOn = -1; var rpmOff = -1
        var gammaX100 = -1; var satX100 = -1; var contrastX10 = -1
        var rgX10 = -1; var ggX10 = -1; var bgX10 = -1
    }
    /** По адресу — группа, если это колесо сейчас с кем-то синхронизировано.
     *  Все адреса группы указывают на один и тот же объект. */
    private val syncGroups = HashMap<String, SyncGroup>()
    /** Адрес → остальные адреса его группы, для реактивного UI. */
    val syncPartners = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /** Адреса, для которых уже пробовали восстановить состояние группы из
     *  SyncSlideshowService в этом запуске ViewModel (см. restoreSyncStateIfNeeded) —
     *  once per address, дальше состояние уже ведёт сама реактивная логика. */
    private val restoredSyncFor = HashSet<String>()

    // ---- Состав группы синхронного показа, переживающий убийство процесса ----
    //
    // SyncSlideshowService.tracked (см. класс там) — это statics самого
    // процесса: они переживают закрытие Activity (ViewModel.onCleared()
    // успевает передать ей группу), но НЕ переживают, если систему убьёт сам
    // процесс целиком, — а именно так Android обычно и убирает надолго
    // свёрнутое приложение (LMK по памяти, батарейные менеджеры некоторых
    // прошивок), в отличие от штатного закрытия задачи. onCleared() в этом
    // случае просто не вызывается — Android не обещает его вызвать при убийстве
    // процесса, только при управляемом уничтожении ViewModelStore. Ни один
    // сервис тогда не поднимается вообще: показ на колёсах идёт и так, но
    // часы колёс никто не подстраивает и расписание никто не вручает
    // заново — со временем колёса расходятся.
    //
    // Чтобы новый процесс (следующий открытый экран) мог немедленно
    // восстановить группу, не дожидаясь, пока пользователь заново нажмёт
    // Start, состав/отбор/интервал/t0 последней активной группы дублируются сюда,
    // в SharedPreferences — при каждом изменении, теми же местами кода, что
    // уже обновляют SyncSlideshowService.track()/updateConfig(). restoreSyncStateIfNeeded
    // читает эту копию, когда SyncSlideshowService ничего не помнит (то есть
    // процесс был убит и это первый запуск ViewModel после этого).
    private fun persistSyncGroup(group: SyncGroup) {
        prefs.edit()
            .putString("syncgroup_members", group.members.joinToString(","))
            .putString("syncgroup_files", group.files.joinToString(","))
            .putInt("syncgroup_interval", group.intervalMs)
            .putLong("syncgroup_t0", group.t0Ms)
            .apply()
    }

    private fun clearPersistedSyncGroup() {
        prefs.edit()
            .remove("syncgroup_members").remove("syncgroup_files").remove("syncgroup_interval")
            .remove("syncgroup_t0")
            .apply()
    }

    private data class PersistedSyncGroup(
        val members: List<String>, val files: List<String>, val intervalMs: Int, val t0Ms: Long
    )

    private fun loadPersistedSyncGroup(): PersistedSyncGroup? {
        val members = prefs.getString("syncgroup_members", null)
            ?.split(",")?.filter { it.isNotEmpty() } ?: return null
        val files = prefs.getString("syncgroup_files", null)
            ?.split(",")?.filter { it.isNotEmpty() } ?: return null
        if (members.size < 2 || files.isEmpty()) return null
        val ms = prefs.getInt("syncgroup_interval", 10000)
        return PersistedSyncGroup(members, files, ms, prefs.getLong("syncgroup_t0", 0L))
    }
    /**
     * Адрес → интервал слайдшоу, мс — ОДНО значение что для синхронного, что
     * для обычного показа, а не два разных: `setSlideInterval` пишет сюда при
     * каждой смене независимо от того, идёт ли сейчас синхронный показ, и
     * экран читает интервал отсюда всегда, а не из `tele.slideSecs`
     * (собственного показания устройства, которое синхронный показ никогда не
     * обновляет — он двигает кадры сам, минуя `OP_ALBUM`). Пока группа жива,
     * запись общая для всех её участников, так что смена на ЛЮБОМ из колёс
     * группы сразу видна на экране ЛЮБОГО другого. Не снимается ни в
     * [endSync], ни где-либо ещё: это ПОСЛЕДНЕЕ использованное значение для
     * данного адреса, и оно обязано остаться таким и после остановки показа —
     * следующий запуск (синхронный или нет) продолжает именно с него.
     */
    val slideIntervalMs = MutableStateFlow<Map<String, Int>>(emptyMap())

    data class PendingSyncDelete(val names: List<String>, val partners: List<Pair<String, String>>)
    /** Удаление на синхронизированном колесе задело общий файл — ждём ответа,
     *  удалять ли его и у остальных участников группы тоже. */
    val pendingSyncDelete = MutableStateFlow<PendingSyncDelete?>(null)

    /** Имена файлов, совпадающих у [addr] и КАЖДОГО из [others] по имени И
     *  размеру — ровно то, что безопасно проигрывать в одной
     *  последовательности на всех сразу. Всегда освежает список по BLE для
     *  каждого участника: кэш фоновых колёс ([prefetchWheel]) обновляется
     *  только раз при подключении. */
    suspend fun commonFileNames(addr: String, others: Set<String>): Set<String> {
        val ca = clients[addr] ?: return emptySet()
        val fa = runCatching { ca.list() }.getOrNull() ?: filesByAddr[addr] ?: emptyList()
        filesByAddr[addr] = fa
        if (current.value == addr) files.value = fa
        var common = fa.map { it.name to it.size }.toHashSet()
        for (other in others) {
            val co = clients[other] ?: continue
            val fo = runCatching { co.list() }.getOrNull() ?: filesByAddr[other] ?: emptyList()
            filesByAddr[other] = fo
            if (current.value == other) files.value = fo
            val keyO = fo.map { it.name to it.size }.toHashSet()
            common = common.filterTo(HashSet()) { it in keyO }
        }
        return fa.filter { (it.name to it.size) in common }.map { it.name }.toCollection(LinkedHashSet())
    }

    /** Прекратить синхронизацию, в которой участвует [addr] (если есть) —
     *  снимает всю группу, не только этот адрес, отпускает службу (см.
     *  SyncSlideshowService.stop) и гасит уведомление. Показ на колёсах
     *  остаётся как есть — его останавливает вызывающий, если нужно. */
    private fun endSync(addr: String?) {
        val group = addr?.let { syncGroups[it] } ?: return
        for (m in group.members) syncGroups.remove(m)
        syncPartners.value = syncPartners.value - group.members.toSet()
        // slideIntervalMs НЕ трогаем — см. комментарий у него: это последнее
        // использованное значение, оно должно пережить остановку показа.
        SyncSlideshowService.stop(ctx)
        clearPersistedSyncGroup()
    }

    /**
     * Видимость уведомления синхронного показа — только пока эта ViewModel
     * (экран открыт) сама ведёт группу [addr]. Показывает его РОВНО когда у
     * группы реально на связи ([Link.Ready], а не просто «числится в группе»)
     * два участника и больше — не когда пользователь нажал «Start», а когда
     * синхронизация действительно идёт. Один участник онлайн (второе колесо
     * ещё не проснулось/спит) — уведомления нет; как только их снова стало
     * два — оно появляется само, без отдельного действия пользователя.
     * Вызывается из коллектора [BleClient.link] на каждое изменение связи
     * ЛЮБОГО участника — пересчитывает состояние группы целиком, так что не
     * важно, чей именно `link` сейчас поменялся.
     *
     * Пока группу держит [SyncSlideshowService] (экран закрыт), видимость
     * решает она сама, тем же правилом, через свой updateNotificationVisibility —
     * это единственное место, где решение принимает ViewModel.
     */
    private fun updateSyncNotification(addr: String) {
        val group = syncGroups[addr] ?: return
        val readyNames = group.members
            .filter { clients[it]?.link?.value == Link.Ready }
            .map { m -> wheels.value.firstOrNull { it.address == m }?.name ?: m }
        SyncSlideshowService.setLiveVisible(ctx, if (readyNames.size >= 2) readyNames else null)
    }

    /** Токен эффекта («@e3») → его номер, иначе null (значит это имя файла). */
    private fun slideEffectId(token: String): Int? =
        if (isSlideEffect(token)) token.removePrefix("@e").toIntOrNull() else null

    /** Насколько вперёд ставить начало нового расписания (t0): за это время
     *  OP_ALBUM успевает дойти до всех колёс группы, и первое переключение
     *  случается у всех в один и тот же момент, а не по мере прихода команды. */
    private val SYNC_START_LEAD_MS = 1500L

    private fun newSyncT0(): Long = System.currentTimeMillis() + SYNC_START_LEAD_MS

    /** Вручить расписание группы всем её участникам, кто сейчас на связи. */
    private fun armSyncGroup(group: SyncGroup) {
        for (m in group.members) armSyncMember(group, m)
    }

    /**
     * Вручить расписание группы колесу [addr] — при старте, после каждой правки
     * и после каждого переподключения (колесо могло проспать правку или
     * потерять расписание вместе с питанием). Сначала ждём, пока HallSync
     * выставит часы колеса (см. [SyncSlideshowService.armMember]).
     */
    private fun armSyncMember(group: SyncGroup, addr: String) {
        val c = clients[addr]?.takeIf { it.link.value == Link.Ready } ?: return
        viewModelScope.launch {
            if (c.hello?.hasSlideClock != true) {
                say((c.hello?.name ?: addr) + ": update the firmware — synced slideshow needs it")
                return@launch
            }
            SyncSlideshowService.armMember(c, group.files, group.intervalMs, group.t0Ms)
        }
    }

    /**
     * Отражение группы синхронного показа для [addr] могло не пережить
     * закрытие приложения — сама ViewModel новая, `syncGroups` пуст. Два
     * источника, в порядке предпочтения:
     *
     * 1. [SyncSlideshowService] — пока жив процесс (а именно это и означает
     *    «служба пережила закрытие приложения»), она хранит состав/отбор/
     *    интервал/t0 последней активной группы в памяти, обновлённый вплоть до
     *    самого последнего изменения. Если служба что-то помнит для этого
     *    адреса — забираем группу обратно сюда: она отпускает СВОИ соединения
     *    (см. release и комментарий в начале секции про то, почему второе
     *    соединение к тому же адресу ненадёжно), и как только это
     *    подтверждено, расписание вручается уже с соединений этой ViewModel.
     *
     * 2. Копия в SharedPreferences ([loadPersistedSyncGroup]) — на случай,
     *    если процесс был не штатно закрыт, а убит целиком (LMK, батарейный
     *    менеджер прошивки): тогда ViewModel.onCleared() не вызывается вовсе,
     *    ни один сервис не поднимается, и SyncSlideshowService ничего не
     *    помнит, хотя показ должен был продолжаться. Без этой копии часы
     *    колёс никто бы больше не подстраивал — до тех пор, пока пользователь
     *    не нажмёт Start заново, — вместо того чтобы просто снова оказаться в
     *    группе, как только колёса окажутся на связи. Здесь никого не нужно ни о чём просить отпустить: раз служба
     *    ничего не помнит, значит она и не подключалась.
     *
     * В обоих случаях подключаемся заодно к остальным участникам группы,
     * которых сама эта ViewModel ещё не открывала. Однократно на адрес за
     * время жизни ViewModel — дальше состоянием заведуют сами
     * [startSyncedSlideshow]/[endSync].
     */
    private fun restoreSyncStateIfNeeded(addr: String) {
        if (!restoredSyncFor.add(addr)) return
        if (syncGroups.containsKey(addr)) return
        val fromService = SyncSlideshowService.trackedGroupFor(addr)
        val members: List<String>
        val files: List<String>
        val ms: Int
        val t0: Long
        if (fromService != null) {
            members = fromService.members; files = fromService.files; ms = fromService.intervalMs
            t0 = fromService.t0Ms
        } else {
            val persisted = loadPersistedSyncGroup()?.takeIf { addr in it.members } ?: return
            members = persisted.members; files = persisted.files; ms = persisted.intervalMs
            t0 = persisted.t0Ms
        }
        // t0 нет — группу сохранила прежняя версия (показ по тикам): новое расписание.
        val group = SyncGroup(members, files, ms, if (t0 > 0) t0 else newSyncT0())
        if (t0 <= 0) persistSyncGroup(group)
        for (m in members) syncGroups[m] = group
        syncPartners.value = syncPartners.value + members.associateWith { m -> members - m }
        slideIntervalMs.value = slideIntervalMs.value + members.associateWith { ms }
        viewModelScope.launch {
            if (fromService != null) {
                SyncSlideshowService.release(ctx)
                withTimeoutOrNull(1500) { while (SyncSlideshowService.isDrivingAddress(addr)) delay(50) }
            }
            armSyncGroup(group)
            for (m in members) {
                if (m != addr && clients[m] == null) {
                    connect(m, prefs.getString("name_" + m, "POV wheel")!!)
                }
            }
        }
    }

    /**
     * Скопировать пороги оборотов (start/stop speed), диапазон авто-яркости и
     * весь Color Correction (гамма/насыщенность/контраст/баланс RGB) с
     * текущего колеса на [targets]. Все дисплеи синхронного показа должны не
     * только разгораться/гаснуть/притемняться на одной скорости, но и
     * выглядеть одинаково — иначе один и тот же кадр читается по-разному на
     * двух колёсах одного велосипеда. Угол, окружность колеса и реверс лучей —
     * это калибровка конкретного физического крепления/корпуса, их не трогаем
     * и не читаем.
     */
    private fun syncGroupFields(targets: List<String>, src: Settings) {
        for (to in targets) {
            val c = clients[to] ?: continue
            viewModelScope.launch {
                val base = runCatching { c.getSettings() }.getOrNull() ?: settingsByAddr[to] ?: return@launch
                if (base.bmin == src.bmin && base.bmax == src.bmax &&
                    base.rpmOn == src.rpmOn && base.rpmOff == src.rpmOff &&
                    base.gammaX100 == src.gammaX100 && base.satX100 == src.satX100 &&
                    base.contrastX10 == src.contrastX10 && base.rgX10 == src.rgX10 &&
                    base.ggX10 == src.ggX10 && base.bgX10 == src.bgX10) return@launch
                val merged = base.copy(
                    bmin = src.bmin, bmax = src.bmax, rpmOn = src.rpmOn, rpmOff = src.rpmOff,
                    gammaX100 = src.gammaX100, satX100 = src.satX100, contrastX10 = src.contrastX10,
                    rgX10 = src.rgX10, ggX10 = src.ggX10, bgX10 = src.bgX10
                )
                runCatching { c.setSettings(merged); c.save() }
                settingsByAddr[to] = merged
                if (current.value == to) settings.value = merged
            }
        }
    }

    /**
     * Пороги оборотов, диапазон авто-яркости или Color Correction группы
     * поменялись (не важно, с какого именно колеса — [src] это настройки
     * того колеса, что сейчас `current`) — сверяем с тем, что группа уже
     * разослала в прошлый раз, и если правда поменялось, рассылаем остальным
     * участникам и запоминаем как текущее групповое. Вызывается и при старте
     * показа (тогда `-1` в группе гарантированно не совпадёт, и рассылка/
     * запоминание пройдёт всегда), и из [pushSettings] на каждую последующую
     * авторитетную запись — так что смену любого из этих полей на ЛЮБОМ
     * колесе группы во время показа сразу подхватывают все остальные.
     */
    private fun applyGroupSyncedFields(group: SyncGroup, src: Settings) {
        if (group.bmin == src.bmin && group.bmax == src.bmax &&
            group.rpmOn == src.rpmOn && group.rpmOff == src.rpmOff &&
            group.gammaX100 == src.gammaX100 && group.satX100 == src.satX100 &&
            group.contrastX10 == src.contrastX10 && group.rgX10 == src.rgX10 &&
            group.ggX10 == src.ggX10 && group.bgX10 == src.bgX10) return
        group.bmin = src.bmin; group.bmax = src.bmax
        group.rpmOn = src.rpmOn; group.rpmOff = src.rpmOff
        group.gammaX100 = src.gammaX100; group.satX100 = src.satX100; group.contrastX10 = src.contrastX10
        group.rgX10 = src.rgX10; group.ggX10 = src.ggX10; group.bgX10 = src.bgX10
        val addr = current.value
        syncGroupFields(group.members.filter { it != addr }, src)
    }

    /**
     * Запустить синхронное слайдшоу текущего колеса с [partners] (два и
     * больше адресов). [checked] — общие для всех участников файлы плюс
     * токены эффектов `@eN`; колёса крутят их сами по общему расписанию (см.
     * комментарий в начале секции).
     */
    fun startSyncedSlideshow(partners: Set<String>, delaySecs: Int, checked: Set<String>) {
        val addr = current.value ?: return
        val others = (partners - addr).toList()
        if (others.isEmpty()) return
        val members = listOf(addr) + others
        val outdated = members.mapNotNull { m -> clients[m]?.hello?.takeIf { !it.hasSlideClock }?.name }
        if (outdated.isNotEmpty()) {
            say("Update the firmware on " + outdated.joinToString(", ") + " first")
            return
        }
        // Порядок отбора — общий для всех колёс: по нему каждое считает пункт
        // слота, и список у всех обязан быть одним и тем же. Прошивка с
        // FEAT_ALBUM_LONG принимает его любой длины (кусками, OP_ALBUM_LIST).
        // Если хоть одно колесо её не умеет, список должен целиком влезть в
        // ОДНУ запись OP_ALBUM на каждом колесе (MTU согласуется на каждое
        // соединение отдельно), — тогда режем один раз, по самому тесному.
        val longList = members.all { clients[it]?.hello?.hasAlbumLong == true }
        val payload = members.mapNotNull { clients[it]?.payloadSize }.minOrNull() ?: 244
        // Файлы — в порядке библиотеки, как у обычного слайдшоу: отбор с экрана —
        // множество (порядок нажатий или вовсе хеша), и показ начинался «со
        // случайного» файла.
        val libOrder = (if (current.value == addr) files.value else filesByAddr[addr] ?: emptyList()).map { it.name }
        val wanted = libOrder.filter { it in checked } + checked.filter { !isSlideEffect(it) && it !in libOrder }
        val fileNames = if (longList) wanted else fitNamesToOneWrite(wanted, payload)
        val list = fileNames + checked.filter { isSlideEffect(it) }
        if (list.isEmpty()) { say("Tick at least one shared animation"); return }
        endSync(addr); others.forEach { endSync(it) }
        val ms = (delaySecs * 1000).coerceIn(1000, 300000)
        val group = SyncGroup(members, list, ms, newSyncT0())
        for (m in members) syncGroups[m] = group
        syncPartners.value = syncPartners.value + members.associateWith { m -> members - m }
        slideIntervalMs.value = slideIntervalMs.value + members.associateWith { ms }
        if (settingsLoaded.value) applyGroupSyncedFields(group, settings.value)
        // Настройки выбранных в показ эффектов (кроме «Текста») — с этого колеса
        // на все остальные: один и тот же эффект должен выглядеть одинаково.
        viewModelScope.launch {
            val c = clients[addr]?.takeIf { it.hello?.hasFx == true } ?: return@launch
            val src = runCatching { c.fxGet() }.getOrNull()?.also { adoptFx(addr, it) }
                ?: fxByAddr[addr] ?: return@launch
            syncFxToGroup(addr, src, freshBase = true)
        }
        armSyncGroup(group)
        val names = members.map { m -> wheels.value.firstOrNull { it.address == m }?.name ?: m }
        SyncSlideshowService.track(members, names, list, ms, group.t0Ms)
        persistSyncGroup(group)
        updateSyncNotification(addr)
        say(if (fileNames.size < wanted.size) "Synced slideshow started — only " + fileNames.size + " files fit"
            else "Synced slideshow started")
    }

    /** Остановить синхронное слайдшоу И погасить ленту на ВСЕХ колёсах группы —
     *  нажатие Stop не должно требовать повторного нажатия на каждом.
     *  [endSync] уже отпускает группу и просит [SyncSlideshowService]
     *  разослать стоп; следующий цикл дублирует
     *  `stop()` через соединения этой ViewModel как подстраховку — повторный
     *  `stop()` на уже остановленном колесе безвреден. */
    fun stopSyncedSlideshow(addr: String) {
        val group = syncGroups[addr] ?: return
        val members = group.members
        endSync(addr)
        viewModelScope.launch {
            for (m in members) {
                clients[m]?.takeIf { it.link.value == Link.Ready }?.let { c ->
                    runCatching { c.stop() }
                }
            }
        }
    }

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
    /** Эффект «Текст» открытого колеса — миниатюра в плитке и редактор (см.
     *  TextFx ниже). Объявлены ДО init: открытие последнего колеса при старте
     *  (loadCache) уже читает карту. */
    val textFx = MutableStateFlow(TextFx())
    private val textFxByAddr = HashMap<String, TextFx>()
    /** Настройки Speed/Rainbow/Clock открытого колеса (см. «параметры эффектов» ниже). */
    val fxParams = MutableStateFlow(FxParams())
    private val fxByAddr = HashMap<String, FxParams>()
    val logLines = MutableStateFlow<List<String>>(emptyList())
    val toast = MutableStateFlow<String?>(null)

    /** Замок «Magnet Position» — защита от случайной правки на ходу. Закрыт
     *  всегда, когда колесо открывают и когда оно заново выходит на связь (в
     *  том числе после перепрошивки): разблокировка — на время одной правки, до
     *  отключения, и нигде не хранится. Раньше хранилась в prefs по адресу, с
     *  «открыто» по умолчанию, — и любое колесо без записи (новое, забытое и
     *  найденное снова, после очистки данных) открывалось разблокированным. */
    val magnetLocked = MutableStateFlow(true)
    fun setMagnetLocked(v: Boolean) {
        magnetLocked.value = v
    }

    // Пишется с Dispatchers.IO, читается из отрисовки списка — обычный HashMap
    // здесь может уйти в бесконечный цикл на рехэше.
    private val thumbs = ConcurrentHashMap<String, PreviewFrame>()
    private val THUMB_MAGIC = 0x31465650                 // "PVF1", см. saveThumb

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
    private var btStateReceiver: BroadcastReceiver? = null
    // Объявлено ДО init: инициализатор `= null` идущей ниже переменной иначе
    // выполнился бы уже ПОСЛЕ startConnectSweep() (см. её же порядок в init) —
    // Kotlin прогоняет инициализаторы полей и блоки init строго в текстовом
    // порядке — и затирал бы обратно в null только что запущенную задачу.
    private var connectSweepJob: kotlinx.coroutines.Job? = null

    init {
        // Тик устаревания. Без него строка «видели 3 с назад» так и висит
        // после того, как колесо унесли: пакетов больше нет, а значит и повода
        // пересобрать список — тоже. Секунда достаточно мелкая, чтобы переход
        // в «недоступно» не выглядел залипанием, и достаточно крупная, чтобы
        // ничего не стоить.
        viewModelScope.launch {
            while (true) { delay(1000); rebuildWheels() }
        }
        // Кэш последнего экрана на диск — раз в несколько секунд, только изменения.
        viewModelScope.launch {
            while (true) { delay(3000); runCatching { saveCaches() } }
        }
        found.value = knownDevices()
        rebuildWheels()
        registerBtStateReceiver()
        startConnectSweep()

        // «Render POV Video»: свежий хвост лога Холла со всех колёс на связи —
        // и пока колесо крутится (кольцо в PSRAM читается всегда). Колесо, которое
        // как раз переподключается, недолго ждём: ролик сняли, пока приложение
        // было в фоне, и связь могла только-только вернуться.
        povVideo.logRefresher = {
            coroutineScope {
                clients.values.toList().map { c ->
                    async {
                        withTimeoutOrNull(8_000) { c.link.first { it != Link.Connecting } }
                        if (c.link.value == Link.Ready && c.hello?.hasHallLog == true) c.pullHallLogNow()
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * Пробует подключиться к КАЖДОМУ известному колесу без связи — считай что
     * сразу при старте (первый проход отложен лишь на 50 мс — см. комментарий
     * внутри, — а не до момента, когда колесо засветится сканом) и затем
     * регулярно, страховкой к [onSeenAgain]. Специально БЕЗ проверки [WheelEntry.reachable]
     * (то есть без ожидания, что колесо уже засветилось сканом): `connect()`
     * открывает GATT напрямую по MAC-адресу — ровно так же, как
     * [openLastWheel] всегда подключала последнее колесо, ни разу не дожидаясь
     * рекламного пакета, — и это и есть единственный способ начать попытку
     * СРАЗУ после открытия приложения, а не только когда/если повезёт поймать
     * рекламу. Колесо не в радиусе просто получит отказ по таймауту (~20 с,
     * см. [openLastWheel]) и будет переспрошено на следующем проходе.
     *
     * `connect()` безопасно звать повторно — для Connecting/Ready он выходит
     * сразу же, ничего не задваивая, так что частый тик ничего не стоит для
     * уже подключённых/подключающихся колёс.
     */
    private fun startConnectSweep() {
        if (connectSweepJob?.isActive == true) return
        connectSweepJob = viewModelScope.launch {
            // Обязательная задержка ПЕРЕД первым проходом, а не после: launch
            // на Dispatchers.Main.immediate, вызванный уже с главного потока
            // (как здесь, из init), выполняется СИНХРОННО вплоть до первой
            // настоящей точки приостановки. Без неё тело цикла — и, значит,
            // весь connect() с записью в clients/wantConnected/watchJobs и
            // конструированием BleClient — отработало бы прямо ВНУТРИ
            // конструктора WheelVm, до того как отработали инициализаторы
            // полей, объявленных ниже по файлу (например, uploadSessions и
            // соседей в разделе заливки) — то есть на ещё не до конца
            // построенном объекте. Ровно поэтому тикер устаревания чуть выше
            // тоже начинает с delay(), а не с самого дела. delay(), в отличие
            // от yield(), гарантированно уходит через таймер и не может
            // схлопнуться в синхронный вызов на immediate-диспетчере.
            delay(50)
            while (true) {
                for (w in wheels.value) {
                    // Проверяем ЖИВОЕ состояние клиента, а не снимок w.link из
                    // wheels.value: тот мог устареть на доли секунды между
                    // пересборкой списка и этой строкой. connect() для уже
                    // готового клиента синхронно зовёт selectWheel(addr) —
                    // это ПРАВИЛЬНО для тапа по строке (пользователь явно так
                    // и просил), но регулярный фоновый тик не должен вслед за
                    // устаревшим снимком вырывать экран из-под того, что
                    // человек прямо сейчас смотрит (например, ход синхронной
                    // заливки на другом колесе).
                    val live = clients[w.address]?.link?.value
                    if (live == Link.Ready || live == Link.Connecting) continue
                    if (isIgnored(w.address)) continue
                    connect(w.address, w.name)
                }
                delay(4000)
            }
        }
    }

    /**
     * Выключение Bluetooth на телефоне роняет весь адаптер разом — это не то
     * же самое, что обрыв одного GATT-соединения, и `onConnectionStateChange`
     * в этом случае у многих версий Android/производителей вообще не
     * приходит: стеку, у которого только что выключили радио, уже некому
     * сказать «отключено». Без этой подписки клиент так и оставался бы
     * Ready навсегда — расписание синхронной группы вручалось бы в мёртвую
     * пустоту, и ни это, ни onLinkLost
     * никогда не узнали бы, что связь пропала, а значит и переподключение
     * никогда бы не началось. Именно это и было настоящей причиной жалобы
     * «после подключения обратно синхронизация не возобновляется» — телефон
     * реально переподключался, а приложение об этом не знало, потому что
     * никогда не замечало, что связь вообще пропадала.
     */
    private fun registerBtStateReceiver() {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> onBluetoothTurnedOff()
                    BluetoothAdapter.STATE_ON -> onBluetoothTurnedOn()
                }
            }
        }
        btStateReceiver = rx
        try {
            ContextCompat.registerReceiver(
                ctx, rx, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}
    }

    /** Адаптер выключили — принудительно считаем мёртвым каждое ещё не
     *  закрытое соединение и пускаем его тем же путём восстановления, что и
     *  настоящий обрыв ([onLinkLost]), вместо того чтобы ждать колбэк,
     *  который в этом случае может не прийти вовсе (см. [registerBtStateReceiver]). */
    private fun onBluetoothTurnedOff() {
        for (addr in clients.keys.toList()) {
            val c = clients[addr] ?: continue
            if (c.link.value == Link.Disconnected) continue
            c.close()
            viewModelScope.launch { onLinkLost(addr) }
        }
    }

    /** Адаптер снова включили — куда более надёжный повод пробовать
     *  переподключиться прямо сейчас, чем ждать растущий таймер [onLinkLost]
     *  (тот доходит до минуты простоя между попытками). */
    private fun onBluetoothTurnedOn() {
        for (addr in wantConnected.toList()) {
            val lk = clients[addr]?.link?.value
            if (lk == Link.Ready || lk == Link.Connecting) continue
            reconnectJobs[addr]?.cancel()
            connect(addr, prefs.getString("name_" + addr, "POV wheel")!!)
        }
    }

    /** Пересобрать [wheels]. Дёшево: колёс единицы, а не сотни. */
    private fun rebuildWheels() {
        val now = System.currentTimeMillis()
        val order = knownOrder()
        val known = order.toHashSet()
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

        // Порядок — по тому, когда колесо стало известным ([knownOrder]),
        // и НИКОГДА по статусу связи: строка раньше перескакивала местами
        // при каждом подключении/обрыве (живые — в начало, offline — в
        // конец), и в шапке с двумя-тремя колёсами это выглядело так, будто
        // порядок вообще случаен. Новое известное колесо всегда уходит в
        // конец ([markKnown]) и остаётся там, пока его не забудут. Ещё не
        // известные (увидены сканом, но подключение впервые ещё не начато)
        // идут следом, по имени — их там доли секунды, markKnown срабатывает
        // уже на первой попытке автоподключения (см. [onSeenAgain]).
        wheels.value = byAddr.values.sortedWith(
            compareBy(
                { val i = order.indexOf(it.address); if (i < 0) Int.MAX_VALUE else i },
                { it.name.lowercase() },
                { it.address }
            )
        )
    }

    /**
     * Порядок «известных» колёс — устойчивый, не связанный со статусом связи:
     * вновь известное всегда дописывается в КОНЕЦ ([markKnown]), а порядок
     * уже известных не меняется, пока их не забудут ([forget]). Раньше это
     * был `Set<String>` в prefs — Set не хранит порядок вставки, и строка в
     * шапке экрана перестраивалась то так, то этак просто от пересохранения.
     * Хранится строкой с разделителем, тем же приёмом, что и
     * `syncgroup_members` ([persistSyncGroup]).
     */
    private fun knownOrder(): List<String> {
        val raw = prefs.getString("known_order", null)
        if (raw != null) return if (raw.isEmpty()) emptyList() else raw.split(",")
        // Миграция со старого Set<String>, один раз: там порядка никогда не
        // было, поэтому сортируем по имени — не идеально, но хотя бы
        // детерминированно, а не «как повезёт хешу», и дальше уже не дёргается.
        val old = prefs.getStringSet("known", emptySet()) ?: emptySet()
        val migrated = old.sortedBy { (prefs.getString("name_" + it, null) ?: it).lowercase() }
        saveKnownOrder(migrated)
        return migrated
    }

    private fun saveKnownOrder(order: List<String>) {
        prefs.edit().putString("known_order", order.joinToString(",")).apply()
    }

    fun bluetoothReady(): Boolean = adapter?.isEnabled == true

    fun client(addr: String?): BleClient? = if (addr == null) null else clients[addr]
    fun currentClient(): BleClient? = client(current.value)

    /** Открытый клиент, но только если он на связи — получатель всех команд. */
    private fun readyClient(): BleClient? =
        currentClient()?.takeIf { it.link.value == Link.Ready }

    /**
     * Открыть колесо по тапу в строке: если оно уже на связи — просто показать,
     * иначе показать «подключаемся» и запустить соединение.
     */
    /**
     * Тап по строке колеса.
     *
     * Уже на связи — просто показать. Иначе подключение и так само пытается
     * идти (см. [startConnectSweep]) — тап лишь подталкивает его немедленно,
     * не дожидаясь ближайшего тика. Экран при этом уводим на это колесо
     * ТОЛЬКО если сейчас не на чем оставаться (ничего не открыто, либо
     * открытое само не на связи) — иначе тап по офлайн-соседу или по тому, с
     * которым только пытается установиться связь, распахивал бы пустой
     * IdleContent взамен уже живой картинки активного дисплея, хотя
     * показывать там пока нечего и, возможно, никогда не будет (колесо вне
     * радиуса). `connect()` сама разберётся, показывать ли экран, как только
     * реально подключится (см. её же комментарий про свободный экран).
     */
    fun openWheel(addr: String, name: String) {
        setIgnored(addr, false)
        val c = clients[addr]
        if (c != null && c.link.value == Link.Ready) { selectWheel(addr); return }
        // Колесо без связи тоже открываем: экран показывает его последний снимок
        // (серым, без управления — см. DeviceScreen), а подключение идёт само.
        selectWheel(addr)
        connect(addr, name)
    }

    /**
     * При старте приложения — сразу открыть последнее колесо, а не ждать тапа.
     * Если оно не отвечает (уехало из зоны действия, спит транспортным сном),
     * `connect()` доходит до отказа только через таймаут в 20 с — слишком
     * долго сидеть с мёртвым колесом на экране, если рядом крутится другое,
     * уже известное или просто найденное сканом. Короткая пауза даёт
     * последнему колесу честный шанс откликнуться (обычное GATT-соединение
     * укладывается в секунду-две), а дальше раз в секунду проверяем, не
     * появилось ли рядом что-то доступное — и тут же переключаемся.
     */
    fun openLastWheel() {
        val addr = prefs.getString("last_wheel", null) ?: return
        // Остаёмся на нём, даже если рядом на связи другое: человек работал с
        // этим, и его экран (последний снимок) показывается и без связи.
        openWheel(addr, prefs.getString("name_" + addr, "POV wheel")!!)
    }

    /**
     * Свайп между колёсами: +1 — следующее, −1 — предыдущее, по кругу. Список —
     * колёса реально НА СВЯЗИ ([Link.Ready]), в том же порядке, что в строке
     * шапки. Не просто «достижимые» ([WheelEntry.reachable] — это ещё и
     * Connecting, и просто увиденное сканом): подключение к соседнему колесу
     * теперь и так идёт само по себе (см. [startConnectSweep]), а свайп на
     * то, что ещё не подключилось, распахивал бы пустой IdleContent взамен
     * живой картинки — свайпаться должно иметь смысл только между тем, что
     * уже реально можно показать. Меньше двух — свайп ничего не делает.
     */
    fun cycleWheel(dir: Int) {
        // Все колёса строки шапки — и без связи тоже: у них показывается
        // последний снимок экрана (см. openWheel).
        val list = wheels.value
        if (list.size < 2) return
        val i = list.indexOfFirst { it.address == current.value }.coerceAtLeast(0)
        val next = list[(i + dir).mod(list.size)]
        openWheel(next.address, next.name)
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

    /** Пользователь сам выбрал это колесо — вот теперь оно «известное».
     *  Новое — в конец [knownOrder]; уже известное своё место не меняет. */
    private fun markKnown(addr: String) {
        val order = knownOrder()
        if (addr !in order) saveKnownOrder(order + addr)
    }

    /**
     * Адреса, которые сканирование НЕ должно само подключать (см. [onSeenAgain]
     * и комментарий там про автоподключение к любому впервые увиденному
     * колесу). Единственный, кто сюда попадает, — [forget]: колесо продолжает
     * рекламировать себя и без этого флага переподключилось бы обратно в
     * течение секунды после «Forget», сделав кнопку бессмысленной. Тап по
     * строке (см. [openWheel]) снимает флаг — раз пользователь сам попросил
     * это колесо, автоподключение к нему снова уместно.
     */
    private fun isIgnored(addr: String): Boolean =
        (prefs.getStringSet("ignored", emptySet()) ?: emptySet()).contains(addr)

    private fun setIgnored(addr: String, v: Boolean) {
        val ignored = prefs.getStringSet("ignored", emptySet())!!.toMutableSet()
        val changed = if (v) ignored.add(addr) else ignored.remove(addr)
        if (changed) prefs.edit().putStringSet("ignored", ignored).apply()
    }

    fun forget(addr: String) {
        disconnect(addr)
        setIgnored(addr, true)
        saveKnownOrder(knownOrder() - addr)
        val edit = prefs.edit().remove("name_" + addr)
        if (prefs.getString("last_wheel", null) == addr) edit.remove("last_wheel")
        edit.apply()
        nameCache.remove(addr)
        filesByAddr.remove(addr); fsInfoByAddr.remove(addr); settingsByAddr.remove(addr)
        uploadSessions.remove(addr); recomputeUploadBusyAddrs()
        prefs.edit().remove("maglock_" + addr).apply()   // след прежней версии
        found.value = found.value.filter { it.address != addr }
        rebuildWheels()
        cacheWritten.remove(addr)
        runCatching { cacheFile(addr).delete() }
        // Архив лога Холла (HallArchive, files/hall/<адрес>) здесь НЕ трогаем
        // намеренно: «забыть» — это про список колёс, а лог — история поездок,
        // по которой склеиваются уже снятые ролики. При повторном подключении
        // колесо с тем же адресом продолжает ту же папку архива.
    }

    /** Колёса, виденные раньше: список не пуст ещё до того, как поиск что-то найдёт. */
    @SuppressLint("MissingPermission")
    fun knownDevices(): List<Found> =
        knownOrder().map { Found(it, prefs.getString("name_" + it, "POV wheel")!!, -127, 0L) }

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
            launch { c.link.collect { lk ->
                rebuildWheels()
                // Соседнее колесо вышло на связь — тихо прогреваем кэш, чтобы
                // свайп на него открывал библиотеку сразу, а не с задержкой.
                if (lk == Link.Ready) {
                    if (addr == current.value) magnetLocked.value = true   // новое подключение — замок закрыт
                    prefetchWheel(addr); restoreSyncStateIfNeeded(addr)
                    syncGroups[addr]?.let { armSyncMember(it, addr) }
                }
                else prefetched.remove(addr)
                updateSyncNotification(addr)
            } }
            launch { c.tele.collect { rebuildWheels() } }
        }
        c.onLinkLost = { viewModelScope.launch { onLinkLost(addr) } }
        reconnectJobs.remove(addr)?.cancel()
        rebuildWheels()
        viewModelScope.launch {
            // SyncSlideshowService или WheelConnectivityService могли подключиться
            // к этому же адресу сами, пока экран был закрыт или свёрнут (см.
            // комментарий в начале секции про синхронизацию слайдшоу и класс
            // WheelConnectivityService) — второе, параллельное соединение с
            // этого же телефона ненадёжно, поэтому просим их отпустить и
            // недолго ждём подтверждения, прежде чем пробовать сами. Обычно
            // (см. enterForeground) это уже сделано заранее и здесь — просто
            // проверка вхолостую, но connect() зовут и другие пути (тап по
            // строке, openLastWheel, авто-подключение по рекламе), которым
            // такой явной подготовки не досталось.
            if (SyncSlideshowService.isDrivingAddress(addr)) {
                SyncSlideshowService.release(ctx)
                withTimeoutOrNull(1500) { while (SyncSlideshowService.isDrivingAddress(addr)) delay(50) }
            }
            if (WheelConnectivityService.isDrivingAddress(addr)) {
                WheelConnectivityService.release(ctx)
                withTimeoutOrNull(1500) { while (WheelConnectivityService.isDrivingAddress(addr)) delay(50) }
            }
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
            // найтись, пока пользователь возится с первым. Открывать его на
            // экране, впрочем, не нужно, если пользователь и так занят другим,
            // активным колесом — иначе появление или пробуждение соседнего
            // колеса в эфире перебрасывало бы экран с того, что человек и так
            // смотрит. Показываем это подключение, только если экран свободен
            // (ничего не выбрано, либо выбранное само не на связи) либо это
            // как раз то колесо, что уже открыто (переподключение того же).
            // Экран занимаем, только если он пуст. Открытое колесо — то, с
            // которым человек работает, даже если оно сейчас без связи: его
            // последний снимок показывается серым, и вырывать экран из-под него
            // ради только что проснувшегося соседа не надо (переключиться на
            // соседа — тап по имени или свайп).
            if (addr == current.value || current.value == null) {
                selectWheel(addr)
                refreshAll()
                startPolling()
            }
            // Часы переживают глубокий сон, но после полного обесточивания
            // взяться им неоткуда — любое соединение это повод их выставить,
            // даже фоновому колесу, которое сейчас не на экране.
            // Колесо с логом Холла часы выставляет HallSync — точно и только
            // ПОСЛЕ того, как измерит, насколько они ушли во сне: грубая
            // установка по секундам до этого стёрла бы само измерение.
            if (c.hello?.hasHallLog != true) try {
                val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
                c.setTime(System.currentTimeMillis() / 1000, tz)
            } catch (_: Exception) {}
        }
    }

    /**
     * Делает колесо активным. Всё, что показывает экран, принадлежит одному
     * устройству. Данные подставляем из кэша ([filesByAddr] и т.д.) — сразу,
     * без пустого экрана на время ответа по BLE; `startPolling` всё равно
     * перечитает всё на первой же итерации (счётчики версий стартуют с −1).
     */
    fun selectWheel(addr: String) {
        if (current.value == addr && settingsLoaded.value) return
        // Очередь записи настроек — для уходящего колеса. Отмена здесь безопасна:
        // следующая запись пойдёт на ДРУГОЙ клиент (другое GATT-соединение), а не
        // сразу на тот же — коллизии, из-за которой падало «radio refused», нет.
        settingsJob?.cancel(); settingsJob = null
        settingsPending = null; savePending = false

        // Снимок уходящего колеса — на нём держится мгновенный возврат.
        current.value?.let { old ->
            filesByAddr[old]  = files.value
            fsInfoByAddr[old] = fsInfo.value
            if (settingsLoaded.value) settingsByAddr[old] = settings.value
        }

        current.value = addr
        prefs.edit().putString("last_wheel", addr).apply()   // открыть его при следующем старте
        loadCache(addr)                                      // после перезапуска — с диска

        // Кэш — ТОЛЬКО для мгновенного показа; settingsLoaded держим false, пока
        // не придёт настоящее чтение с устройства (adoptSettings). Иначе первое
        // же касание ползунка/спиннера отправило бы на колесо кэш — а он мог
        // оказаться значениями по умолчанию (колесо в дефолтном состоянии, или
        // старый снимок), и настройки на устройстве затёрлись бы дефолтами.
        settings.value       = settingsByAddr[addr] ?: Settings()
        settingsLoaded.value = false
        files.value  = filesByAddr[addr] ?: emptyList()
        fsInfo.value = fsInfoByAddr[addr] ?: FsInfo()
        textFx.value = textFxByAddr[addr] ?: TextFx()
        fxParams.value = fxByAddr[addr] ?: FxParams()
        magnetLocked.value = true
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
        // Колесо могло за время обрыва перезагрузиться и потерять RTC-настройки —
        // после реконнекта перечитываем их заново, а не доверяем старой копии.
        if (addr == current.value) {
            // Экран остаётся на этом колесе: его последний снимок показывается
            // серым, пока связь не вернётся (см. DeviceScreen). Раньше экран
            // перескакивал на любое другое доступное колесо — теперь переход
            // только по воле человека (тап, свайп).
            settingsLoaded.value = false
        }
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
     * Колесо в эфире — подключаемся сами, не дожидаясь тапа по строке. Раньше
     * это было исключением: автоподключение работало только для уже известных
     * колёс ([wantConnected] заполняет только [connect], то есть осознанный
     * выбор пользователя), а впервые увиденное просто лежало в списке, пока
     * его не тронут руками. Колесо у велосипеда — не случайный чужой маячок
     * (фильтр сканирования уже отсеял всё, кроме нашего сервиса), и ждать тап
     * незачем: единственная причина НЕ подключаться сама — [isIgnored], то
     * есть колесо, которое только что явно забыли ([forget]) и оно ещё не
     * успело перестать рекламировать себя.
     *
     * Тот же путь и для уже известного, но отвалившегося колеса: оно снова в
     * эфире — значит проснулось, и это куда более надёжный повод для попытки,
     * чем слепой таймер (см. [onLinkLost]).
     */
    private fun onSeenAgain(addr: String) {
        if (isIgnored(addr)) return
        val lk = clients[addr]?.link?.value
        if (lk == Link.Ready || lk == Link.Connecting) return
        reconnectJobs[addr]?.cancel()
        reconnectJobs[addr] = viewModelScope.launch {
            connect(addr, prefs.getString("name_" + addr, "POV wheel")!!)
        }
    }

    fun disconnect(addr: String) {
        endSync(addr)
        wantConnected.remove(addr)
        reconnectJobs.remove(addr)?.cancel()
        watchJobs.remove(addr)?.cancel()
        prefetched.remove(addr)
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
        prefetched.clear()
        clients.values.forEach { it.onLinkLost = null; it.close() }
        clients.clear()
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
                val addr = current.value
                if (c != null && addr != null && c.link.value == Link.Ready) {
                    val t = c.tele.value
                    // Устройство увеличивает эти счётчики при любом изменении,
                    // поэтому правки со второго телефона (или самого колеса)
                    // подхватываются без постоянного опроса всего подряд.
                    if (t.stateVer != lastState) {
                        lastState = t.stateVer
                        val got = runCatching { c.getSettings() }.getOrNull()
                        if (got != null && current.value == addr) {
                            adoptSettings(got)
                        } else {
                            // Не удалось прочитать — не отмечаем как загруженные,
                            // и пробуем ещё раз на следующем витке.
                            lastState = -1L
                        }
                        // Настройки эффектов могли поменять со второго телефона
                        // или рассылкой синхронной группы (OP_FX_SET взводит версию).
                        fetchFx(addr, c)
                    }
                    if (t.fileVer != lastFile) {
                        lastFile = t.fileVer
                        runCatching { c.list() }.getOrNull()?.let {
                            filesByAddr[addr] = it
                            if (current.value == addr) files.value = it
                        }
                        runCatching { c.fsInfo() }.getOrNull()?.let {
                            fsInfoByAddr[addr] = it
                            if (current.value == addr) fsInfo.value = it
                        }
                    }
                }
                delay(1500)
            }
        }
    }

    fun refreshAll() {
        val addr = current.value ?: return
        val c = clients[addr] ?: return
        viewModelScope.launch {
            // Быстрые свайпы между колёсами: ответ мог прийти уже после того,
            // как открыли другое колесо — тогда не применяем его к чужому экрану.
            runCatching { c.getSettings() }.getOrNull()?.let {
                if (current.value == addr) adoptSettings(it)
            }
            runCatching { c.list() }.getOrNull()?.let {
                filesByAddr[addr] = it
                if (current.value == addr) files.value = it
            }
            runCatching { c.fsInfo() }.getOrNull()?.let {
                fsInfoByAddr[addr] = it
                if (current.value == addr) fsInfo.value = it
            }
            runCatching { c.telemetry() }
            fetchTextFx(addr, c)
            fetchFx(addr, c)
        }
    }

    /**
     * Тихо прочитать библиотеку/место/настройки соседнего (не открытого) колеса
     * в кэш — один раз на подключение. Тогда свайп на него открывает всё сразу.
     */
    private fun prefetchWheel(addr: String) {
        if (addr == current.value || !prefetched.add(addr)) return
        val c = clients[addr] ?: return
        viewModelScope.launch {
            runCatching { c.getSettings() }.getOrNull()?.let {
                var f = if (it.ablX10 != 1000) it.copy(ablX10 = 1000) else it
                if (f.bmin < 6) f = f.copy(bmin = 6, bmax = f.bmax.coerceAtLeast(6))
                settingsByAddr[addr] = f
            }
            runCatching { c.list() }.getOrNull()?.let { filesByAddr[addr] = it }
            runCatching { c.fsInfo() }.getOrNull()?.let { fsInfoByAddr[addr] = it }
            fetchTextFx(addr, c)
            fetchFx(addr, c)
        }
    }

    /**
     * Принять настройки, прочитанные с колеса.
     *
     * Мелкие несоответствия правим ТОЛЬКО в локальной копии для показа
     * (Power Limit держим на 100 %, пол яркости 6 — шкала 1..25 = байт 6..31):
     * `pushSettings` при первой же правке пользователя всё равно отправит на
     * колесо весь блок целиком, уже с поправками.
     *
     * Раньше здесь был автоматический `setSettings + save`. Это опасно: если
     * колесо на дефолтах (свежая перепрошивка, сброшенный NVS-blob), приложение
     * тут же цементировало эти дефолты в NVS — калибровку было не вернуть. Теперь
     * приложение НИЧЕГО не пишет само; только показывает прочитанное.
     */
    private fun adoptSettings(got: Settings) {
        var fixed = if (got.ablX10 != 1000) got.copy(ablX10 = 1000) else got
        if (fixed.bmin < 6) fixed = fixed.copy(bmin = 6, bmax = fixed.bmax.coerceAtLeast(6))
        settings.value = fixed
        settingsLoaded.value = true
        current.value?.let { settingsByAddr[it] = fixed }
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
    //
    //  СОСТОЯНИЕ — НА КОЛЕСО, А НЕ ОДНО НА ВСЕХ. Раньше upItems/upStatus/
    //  upProgress/upBusy и т. д. были одним общим MutableStateFlow на всё
    //  приложение — тем самым, который показывает библиотека ТЕКУЩЕГО
    //  открытого колеса. Заливка на колесо A и свайп на экран колеса B, пока
    //  она идёт, показывали заливку и на B тоже — B ничего не грузили, а его
    //  плитка всё равно рисовала жёлтые ячейки и сматывающийся ободок чужой
    //  передачи. [UploadSession] — правильная единица состояния: один объект
    //  на адрес, со своими items/status/progress/busy, и экран каждого колеса
    //  видит только свою сессию (см. upItems и соседей ниже — теперь это
    //  вычисляемые свойства от [current]). Синхронная заливка (см.
    //  [startUpload]) поэтому не «одалживает» чужую сессию, а заводит СВОЮ на
    //  каждой из выбранных партнёрских плиток — вот что и рисует независимый
    //  прогресс на каждой странице.
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
        val ready: Boolean = false,        // тип определён и постер отрисован
        /** Задержки кадров GIF/WebP — для оценки размера на колесе ([upEstimatedBytes]). */
        val srcDelays: IntArray? = null,
        /** Префикс имени на колесе (img_/gif_/anm_/vid_) — по нему видно, что файл
         *  там уже есть и заливка его пропустит. Пусто, пока тип не определён. */
        val prefix: String = ""
    )

    /** Заливка одного колеса: своя очередь, свой статус, свой прогресс. См.
     *  комментарий выше — раньше это было одно состояние на всё приложение. */
    private class UploadSession {
        val items    = MutableStateFlow<List<UpItem>>(emptyList())
        val sel      = MutableStateFlow(0)
        val status   = MutableStateFlow("Waiting for a file…")
        val kind     = MutableStateFlow(0)
        val progress = MutableStateFlow(-1f)
        val busy     = MutableStateFlow(false)
        val currentUri = MutableStateFlow<Uri?>(null)
        /** Анимированные превью ждущих заливки файлов, по Uri — у КАЖДОЙ жёлтой
         *  ячейки, а не только у выбранной. */
        val clips    = MutableStateFlow<Map<Uri, PreviewClip>>(emptyMap())
        /** Другие колёса, на которые уйдёт та же пачка (см. [startUpload]) —
         *  живёт до нажатия Upload, редактируется только на странице-источнике. */
        val syncTargets = MutableStateFlow<Set<String>>(emptySet())
        var prepJob: kotlinx.coroutines.Job? = null
    }

    private val uploadSessions = HashMap<String, UploadSession>()
    private val noWheelUploadSession = UploadSession()   // заглушка, пока ни одно колесо не открыто
    private fun session(addr: String?): UploadSession =
        if (addr == null) noWheelUploadSession else uploadSessions.getOrPut(addr) { UploadSession() }

    /** Идёт ли заливка хоть на ОДНОМ колесе — этим гасят LOW_LATENCY-поиск
     *  (делит радио с заливкой) и держат экран разбуженным, независимо от
     *  того, чья именно страница открыта сейчас. */
    val anyUploadBusy = MutableStateFlow(false)
    /** Адреса колёс, чью очередь заливки сейчас лучше не трогать: либо в неё
     *  уже что-то льётся, либо на ней лежит СВОЙ, ещё не отправленный набор
     *  файлов. Экран берёт отсюда список тех, кого можно предложить в
     *  партнёры синхронной заливки (см. [startUpload]) — иначе выбор второго
     *  колеса в разгар его собственной заливки затёр бы её. */
    val uploadBusyAddrs = MutableStateFlow<Set<String>>(emptySet())
    private fun recomputeUploadBusyAddrs() {
        anyUploadBusy.value = uploadSessions.values.any { it.busy.value }
        uploadBusyAddrs.value = uploadSessions.filterValues { it.busy.value || it.items.value.isNotEmpty() }.keys
    }

    // ---- Свойства для ТЕКУЩЕГО (открытого) колеса — их и читает экран ----
    val upItems: StateFlow<List<UpItem>> get() = session(current.value).items
    val upSel: StateFlow<Int> get() = session(current.value).sel
    val upStatus: StateFlow<String> get() = session(current.value).status
    val upKind: StateFlow<Int> get() = session(current.value).kind
    val upProgress: StateFlow<Float> get() = session(current.value).progress
    val upBusy: StateFlow<Boolean> get() = session(current.value).busy
    /** Uri файла, который льётся прямо сейчас на ЭТО колесо — его ячейка в
     *  сетке рисует сматывающийся ободок по [upProgress]. null — ничего не льётся. */
    val upCurrentUri: StateFlow<Uri?> get() = session(current.value).currentUri
    /** Анимированные превью ждущих заливки ячеек сетки, по Uri. Размер — как у
     *  миниатюр библиотеки (PreviewClips.CACHE_*): клипы держатся у всех ячеек
     *  разом, и на тридцати файлах больший размер стоил бы под сотню мегабайт. */
    val upClips: StateFlow<Map<Uri, PreviewClip>> get() = session(current.value).clips

    /**
     * Сколько займёт файл на колесе после заливки: заголовок ANI6 и кадры по
     * 16608 байт — ровно столько кадров, сколько выйдет из конвертации при
     * потолке открытого колеса. null — тип ещё не определён.
     */
    fun upEstimatedBytes(item: UpItem, maxFrames: Int = fsInfo.value.maxFrames): Long? {
        if (!item.ready) return null
        val cap = maxOf(1, maxFrames)
        val n = when {
            item.isVideo -> {
                val dur = if (item.srcDur > 0) item.srcDur else item.lengthSec
                val len = item.lengthSec.coerceIn(0.1, maxOf(0.1, dur))
                maxOf(1, minOf(Math.round(len * item.fps).toInt(), cap))
            }
            item.srcDelays != null && item.srcDelays.isNotEmpty() -> converter.animOutFrames(item.srcDelays, cap)
            else -> 1
        }
        return 8L + n.toLong() * 16608L
    }

    /** Имя файла на колесе — чтобы узнать уже залитый дубль. Оно функция имени
     *  исходника при любом имени (см. Ani6.buildFileName); null — тип ещё не определён. */
    private fun expectedName(item: UpItem): String? {
        if (item.prefix.isEmpty()) return null
        val base = item.name.substringBeforeLast('.', item.name)
        return Ani6.buildFileName(item.prefix, base).name
    }

    /**
     * Раскладка очереди: [order] — порядок заливки (он же порядок жёлтых ячеек в
     * плитке), [wontFit] — кому места не хватит, [onWheel] — точные копии уже
     * залитого (то же имя на колесе и тот же размер — заливка их пропустит, не
     * конвертируя), [freeAfter] — сколько останется свободным после заливки всех,
     * кто влезает (null — оценивать нечего).
     */
    class QueuePlan(
        val order: List<UpItem>,
        val wontFit: Set<Uri>,
        val onWheel: Set<Uri>,
        val freeAfter: Long?
    ) {
        /** Сколько файлов реально уйдёт на колесо. */
        val toUpload: Int get() = order.size - wontFit.size - onWheel.size
    }

    /**
     * Порядок — по возрастанию места на колесе: сначала картинки, потом анимации
     * от лёгких к тяжёлым. Тогда в забитую память влезает как можно больше
     * файлов, а не влезает предсказуемо только тяжёлый хвост. Проход идёт с тем
     * же запасом, что требует прошивка ([UPLOAD_MARGIN]), с округлением занятого
     * до блоков ФС и с учётом дублей — точная копия уже залитого пропускается и
     * места не берёт, другая версия того же имени освобождает место старой.
     *
     * Порядок плитки: то, что зальётся, → ещё не разобранные (тип не определён,
     * размер неизвестен) → уже лежащие на колесе → кому не хватило места, в самом
     * хвосте. Последние две группы заливка пропустит, а место они не занимают, так
     * что и на остальных это не влияет.
     */
    private fun planQueue(items: List<UpItem>, fs: FsInfo, existing: List<DevFile>): QueuePlan {
        // sortedBy устойчив: файлы одного размера остаются в порядке выбора.
        val sized = items.mapNotNull { it -> upEstimatedBytes(it, fs.maxFrames)?.let { b -> it to b } }
            .sortedBy { it.second }
        val unknown = items.filter { upEstimatedBytes(it, fs.maxFrames) == null }
        // Уже на колесе — сравнение с библиотекой, о свободном месте знать не нужно.
        val same = sized.filter { (it, b) -> sameOnWheel(it, b, existing) }.map { it.first }
        val onWheel = same.mapTo(HashSet()) { it.uri }
        val rest = sized.filter { it.first.uri !in onWheel }
        // О месте на колесе ещё ничего не знаем — просто по размеру.
        if (fs.total <= 0L) return QueuePlan(rest.map { it.first } + unknown + same, emptySet(), onWheel, null)
        var avail = fs.free
        val fit = ArrayList<UpItem>()
        val red = ArrayList<UpItem>()
        for ((it, b) in rest) {
            // Другая версия того же имени: старый файл будет перезаписан.
            val freed = expectedName(it)?.let { n -> existing.firstOrNull { f -> f.name == n } }?.size ?: 0L
            if (b + UPLOAD_MARGIN > avail + freed) { red.add(it); continue }
            avail += roundUp4K(freed) - roundUp4K(b)
            fit.add(it)
        }
        return QueuePlan(fit + unknown + same + red, red.mapTo(HashSet()) { it.uri }, onWheel,
            if (sized.isEmpty()) null else maxOf(0L, avail))
    }

    /**
     * Файл уже лежит на колесе: то же имя (оно у ANI6 детерминировано от исходника,
     * см. Ani6.buildFileName) и тот же размер. Размер на колесе оценка знает точно —
     * те же кадры, что даст конвертер. Кадрирование и зеркало размер не меняют,
     * поэтому та же картинка с другим Crop/Fit тоже считается копией — как и
     * раньше при заливке; перезалить её можно, удалив старую с колеса.
     */
    private fun sameOnWheel(item: UpItem, bytes: Long, existing: List<DevFile>): Boolean {
        val n = expectedName(item) ?: return false
        return existing.any { it.name == n && it.size == bytes }
    }

    private fun roundUp4K(b: Long) = (b + 4095) / 4096 * 4096

    /** Место и библиотека колеса [addr]. У открытого — те же значения, что видит экран. */
    private fun fsOf(addr: String) = if (addr == current.value) fsInfo.value else fsInfoByAddr[addr] ?: FsInfo()
    private fun filesOf(addr: String) = if (addr == current.value) files.value else filesByAddr[addr] ?: emptyList()

    /** План очереди открытого колеса — для плитки и строки свободного места. */
    fun upPlan(items: List<UpItem>): QueuePlan = planQueue(items, fsInfo.value, files.value)

    /**
     * Держит очередь [addr] в порядке [planQueue] — плитка показывает её ровно так,
     * как она пойдёт, ещё до нажатия Upload. Выбранный файл остаётся выбранным,
     * куда бы он ни переехал. Во время заливки порядок заморожен: там очередь —
     * уже проход по файлам ([runUploadTarget]).
     */
    private fun resortQueue(addr: String) {
        val s = session(addr)
        if (s.busy.value) return
        val cur = s.items.value
        if (cur.size < 2) return
        val next = planQueue(cur, fsOf(addr), filesOf(addr)).order
        if (next.indices.all { next[it] === cur[it] }) return
        val selUri = cur.getOrNull(s.sel.value)?.uri
        s.items.value = next
        s.sel.value = next.indexOfFirst { it.uri == selUri }.coerceAtLeast(0)
    }

    /** Пересортировать очередь открытого колеса — после правки длины ролика
     *  (во время набора её не двигаем, см. [setLength]). */
    fun resortUpQueue() { current.value?.let { resortQueue(it) } }
    /** Отмеченные партнёры синхронной заливки для очереди, стоящей на этой
     *  странице прямо сейчас (см. [SyncTargetsRow]-подобный ряд в UploadStrip). */
    val upSyncTargets: StateFlow<Set<String>> get() = session(current.value).syncTargets
    fun toggleUpSyncTarget(addr: String) {
        val s = session(current.value)
        s.syncTargets.value = if (addr in s.syncTargets.value) s.syncTargets.value - addr else s.syncTargets.value + addr
    }

    private val converter by lazy { Converter(ctx) }

    // Сторона квадратного постера-превью в пикселях. В сетке он показывается
    // мелко (34…104 dp), так что больше не нужно.
    private val POSTER_PX = 200

    /** Запас свободного места, который прошивка требует сверх размера файла
     *  (OP_UP_BEGIN: raw_size + 128 кБ) — служебные блоки LittleFS. */
    private val UPLOAD_MARGIN = 128L * 1024
    /** Повторов заливки одного файла после неудачи. */
    private val UPLOAD_RETRIES = 2
    private val ST_BAD_ARG = 2
    private val ST_BUSY = 3
    private val ST_NO_SPACE = 5

    // Отдельный init — после объявления сессий заливки и констант выше:
    // viewModelScope стартует корутину сразу, и первый же collect дошёл бы до
    // ещё не созданных полей.
    init {
        // Сменилось место или библиотека открытого колеса (в том числе при
        // переключении на другое колесо) — кто влезает, а кто нет, тоже мог
        // смениться, а с ним и порядок очереди.
        viewModelScope.launch {
            combine(fsInfo, files) { _, _ -> }.collect { resortUpQueue() }
        }
    }

    /** Выбрали файлы — строим пачку и запускаем фоновую подготовку превью.
     *  Пачка ложится в сессию ТОГО колеса, чья страница открыта сейчас: именно
     *  оно и есть источник — тут же настраиваются кадрирование/fps/длина и
     *  отсюда, при желании, пачка ещё и разлетится на других колёс. */
    fun onFilesPicked(picked: List<Uri>) {
        if (picked.isEmpty()) return
        val addr = current.value ?: return
        val s = session(addr)
        s.prepJob?.cancel()
        s.clips.value = emptyMap()
        s.items.value = emptyList()
        s.sel.value = 0
        s.progress.value = -1f
        // Подсказок «N files selected / name ready. Press Upload.» нет: что выбрано,
        // видно по жёлтым ячейкам, а кнопка Upload и так прямо над ними. Строка
        // статуса — только для хода заливки и ошибок.
        s.status.value = ""
        s.kind.value = 0
        viewModelScope.launch {
            // Uri в очереди уникальны: по нему ячейка плитки держит свой ключ, а
            // подготовка и выбор находят файл после пересортировки.
            val list = withContext(Dispatchers.IO) {
                picked.distinct().map { UpItem(it, converter.displayName(it)) }
            }
            s.items.value = list
            recomputeUploadBusyAddrs()
            startPrep(addr, list.map { it.uri })
        }
    }

    /**
     * Определяет тип и рисует круглое превью для перечисленных позиций — по
     * одной, чтобы не грузить процессор всей пачкой сразу. Тип (видео/картинка)
     * и длительность ролика проставляются раньше постера: ряд пилюль должен
     * знать, показывать ли fps/длину, ещё до готовности превью.
     *
     * [addr] передаётся явно, а не берётся из [current]: подготовка растянута
     * на много `await`, и пока она идёт, пользователь вполне может свайпнуть
     * на другое колесо — превью обязаны дописаться в ТУ сессию, откуда пачка
     * пришла, а не в ту, что случайно окажется открыта к моменту готовности.
     */
    private fun startPrep(addr: String, uris: List<Uri>) {
        val s = session(addr)
        s.prepJob?.cancel()
        s.prepJob = viewModelScope.launch {
            // По Uri, не по индексам: очередь пересортировывается по мере того, как
            // у файлов становится известен размер.
            for (u in uris) {
                if (!isActive) break
                val item0 = s.items.value.firstOrNull { it.uri == u } ?: continue
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
                    val delays = if (kind == Converter.Kind.GIF || kind == Converter.Kind.WEBP_ANIM)
                        withContext(Dispatchers.IO) { converter.animDelays(u) } else null
                    val prefix = when (kind) {
                        Converter.Kind.VIDEO -> "vid_"
                        Converter.Kind.GIF -> "gif_"
                        Converter.Kind.WEBP_ANIM -> "anm_"
                        Converter.Kind.IMAGE -> "img_"
                    }
                    updateByUri(addr, u) {
                        it.copy(isVideo = isVid, anim = kind != Converter.Kind.IMAGE, srcDur = dur,
                            srcDelays = delays, prefix = prefix,
                            lengthSec = if (isVid && !it.lenTouched) defaultLengthSec(addr, it.fps, dur)
                                        else it.lengthSec)
                    }
                }
                // Кадрирование могло смениться — прежний клип уже не тот.
                s.clips.value = s.clips.value - u
                val fit = s.items.value.firstOrNull { it.uri == u }?.fit ?: Fit.CROP
                val poster = withContext(Dispatchers.Default) { converter.posterOf(u, fit, POSTER_PX) }
                updateByUri(addr, u) { it.copy(poster = poster, ready = true) }
                // Размер на колесе стал известен — файл встаёт на своё место в очереди.
                resortQueue(addr)
            }
            // Сначала постеры у всех (видно, что выбрано), потом анимированные
            // превью — по одному, чтобы не грузить процессор и память всей пачкой.
            for (u in uris) {
                if (!isActive) break
                buildClip(addr, u)
            }
        }
    }

    /**
     * Анимированное превью одной ждущей ячейки (GIF, WebP, видео; у картинки
     * хватает постера). Строится в размере миниатюр библиотеки и кладётся в
     * карту сессии по Uri; если кадрирование за это время сменили — выброшено.
     */
    private suspend fun buildClip(addr: String, uri: Uri) {
        val s = session(addr)
        val item = s.items.value.firstOrNull { it.uri == uri } ?: return
        if (!item.ready || !item.anim || s.clips.value.containsKey(uri)) return
        val fit = item.fit
        val clip = withContext(Dispatchers.Default) {
            runCatching {
                converter.previewClip(uri, fit, PreviewClips.CACHE_PX, PreviewClips.CACHE_FRAMES)
            }.getOrNull()
        } ?: return
        val now = s.items.value.firstOrNull { it.uri == uri }
        // Прежний клип не утилизируем — его ещё может рисовать ячейка; освободит сборщик.
        if (now != null && now.fit == fit) s.clips.value = s.clips.value + (uri to clip)
        else clip.recycle()
    }

    private fun updateByUri(addr: String, uri: Uri, f: (UpItem) -> UpItem) {
        val s = session(addr)
        val cur = s.items.value
        val i = cur.indexOfFirst { it.uri == uri }
        if (i < 0) return
        s.items.value = cur.toMutableList().also { it[i] = f(it[i]) }
    }

    private fun updateSel(addr: String, f: (UpItem) -> UpItem) {
        val s = session(addr)
        val cur = s.items.value
        val i = s.sel.value
        if (i !in cur.indices) return
        s.items.value = cur.toMutableList().also { it[i] = f(it[i]) }
    }

    fun selectUpItem(i: Int) {
        val addr = current.value ?: return
        val s = session(addr)
        if (i in s.items.value.indices) s.sel.value = i
        // Длину прежнего выбранного могли править, не закончив ввод (Done) — её
        // поле уже ушло с экрана, и встать на место в очереди файл должен сейчас.
        resortQueue(addr)
    }

    /** Убрать один файл из пачки. */
    fun removeUpItem(i: Int) {
        val addr = current.value ?: return
        val s = session(addr)
        val cur = s.items.value
        if (i !in cur.indices) return
        val gone = cur[i].uri
        val selUri = cur.getOrNull(s.sel.value)?.uri
        val next = cur.toMutableList().also { it.removeAt(i) }
        s.items.value = next
        s.clips.value = s.clips.value - gone
        // Выбор остаётся на том же файле; убрали сам выбранный — на соседе.
        val keep = next.indexOfFirst { it.uri == selUri }
        s.sel.value = (if (keep >= 0) keep else i).coerceIn(0, maxOf(0, next.size - 1))
        if (next.isEmpty()) {
            s.status.value = "Waiting for a file…"
            s.kind.value = 0
        }
        recomputeUploadBusyAddrs()
        // Освободилось место — кто-то из красных мог начать влезать.
        resortQueue(addr)
    }

    /** Убрать из очереди всё разом — когда заливать в ней нечего (только уже
     *  залитое и не влезающее). */
    fun clearUpQueue() {
        val addr = current.value ?: return
        val s = session(addr)
        if (s.busy.value) return
        s.prepJob?.cancel()
        s.items.value = emptyList()
        s.clips.value = emptyMap()
        s.sel.value = 0
        s.status.value = "Waiting for a file…"
        s.kind.value = 0
        recomputeUploadBusyAddrs()
    }

    /** Смена кадрирования выбранного файла — перерисовываем его превью. */
    fun setFit(f: Int) {
        val addr = current.value ?: return
        updateSel(addr) { it.copy(fit = f) }
        val s = session(addr)
        val u = s.items.value.getOrNull(s.sel.value)?.uri ?: return
        s.clips.value = s.clips.value - u            // старый клип — от прежнего кадрирования
        viewModelScope.launch {
            val poster = withContext(Dispatchers.Default) { converter.posterOf(u, f, POSTER_PX) }
            // Если кадрирование за это время снова сменили — отдаём ход более
            // свежей отрисовке, а не подсовываем устаревшую.
            updateByUri(addr, u) { if (it.fit == f) it.copy(poster = poster) else it }
            buildClip(addr, u)
        }
    }

    fun setBackMirror(v: Boolean) {
        val addr = current.value ?: return
        updateSel(addr) { it.copy(mirror = v) }
    }

    /** Длина по умолчанию для данного fps: весь ролик, но не больше, чем влезает
     *  в PSRAM. Потолок — по числу кадров, поэтому в секундах он зависит от fps.
     *  Кадровый потолок берём из кэша ИМЕННО этого колеса — у партнёров
     *  синхронной заливки он может быть другим (см. [startUpload]), редактор
     *  же всегда один, на странице-источнике. */
    private fun defaultLengthSec(addr: String, fps: Int, srcDur: Double): Double {
        val cap = (fsInfoByAddr[addr] ?: FsInfo()).maxFrames.toDouble() / fps
        return maxOf(0.5, minOf(if (srcDur > 0) srcDur else cap, cap))
    }

    fun setFps(n: Int) {
        val addr = current.value ?: return
        updateSel(addr) {
            val cap = (fsInfoByAddr[addr] ?: FsInfo()).maxFrames.toDouble() / n
            // Длину руками не трогали — ведём за fps, и вверх и вниз. Тронутую
            // оставляем, но ужимаем, если перестала влезать. Правило то же, что в вебе.
            val len = if (!it.lenTouched || it.lengthSec > cap) defaultLengthSec(addr, n, it.srcDur) else it.lengthSec
            it.copy(fps = n, lengthSec = len)
        }
        resortQueue(addr)
    }

    /**
     * Длина выбранного ролика. Поле ввода шлёт её на каждое изменение текста с
     * [resort] = false — размер, красный ободок и «after upload» пересчитываются
     * сразу, но ячейка не прыгает по очереди на промежуточных значениях
     * («1» по пути к «15»); на место она встаёт по Done ([resortUpQueue]).
     */
    fun setLength(v: Double, resort: Boolean = true) {
        val addr = current.value ?: return
        updateSel(addr) {
            // coerceIn(min,max) бросает при min > max, а потолок на забитом флеше
            // падает ниже половины секунды.
            val cap = (fsInfoByAddr[addr] ?: FsInfo()).maxFrames.toDouble() / it.fps
            it.copy(lengthSec = if (cap <= 0.5) 0.5 else v.coerceIn(0.5, cap), lenTouched = true)
        }
        if (resort) resortQueue(addr)
    }

    /** Скопировать настройки выбранного файла на все остальные в пачке. */
    fun applyUpSettingsToAll() {
        val addr = current.value ?: return
        val s = session(addr)
        val sel = s.items.value.getOrNull(s.sel.value) ?: return
        val changedFit = ArrayList<Uri>()
        s.items.value = s.items.value.map { item ->
            if (item.fit != sel.fit) changedFit.add(item.uri)
            val len = if (item.isVideo) {
                val cap = (fsInfoByAddr[addr] ?: FsInfo()).maxFrames.toDouble() / sel.fps
                if (sel.lengthSec > cap || cap <= 0.5) defaultLengthSec(addr, sel.fps, item.srcDur) else sel.lengthSec
            } else item.lengthSec
            item.copy(
                fit = sel.fit, mirror = sel.mirror,
                fps = if (item.isVideo) sel.fps else item.fps,
                lengthSec = len,
                lenTouched = if (item.isVideo) sel.lenTouched else item.lenTouched
            )
        }
        resortQueue(addr)                            // fps/длина у видео могли смениться
        if (changedFit.isNotEmpty()) {
            val pending = s.items.value.filter { !it.ready }.map { it.uri }
            startPrep(addr, (changedFit + pending).distinct())
        }
    }

    /**
     * Запускает заливку очереди, стоящей на ТЕКУЩЕЙ странице, — и, если в этой
     * же сессии отмечены партнёры синхронной заливки ([toggleUpSyncTarget]),
     * той же пачкой файлов на них тоже. Источник — просто первый среди равных:
     * у каждой цели (включая само исходное колесо) заводится своя копия
     * очереди в её собственной [UploadSession] и свой независимый проход по
     * файлам — так у каждой цели своя картинка «что льётся прямо сейчас»,
     * своя скорость и свой список «уже есть / не влезло», и они не ждут друг
     * друга барьером на каждом файле: колесо с плохим сигналом просто отстаёт
     * само по себе, не придерживая остальных.
     */
    /** Как часто во время заливки напоминать колёсам пачки, что они нужны (см.
     *  [startUpload]) — с запасом короче их 5-минутного порога сна при связи. */
    private val UPLOAD_KEEPAWAKE_MS = 60_000L

    fun startUpload() {
        val origin = current.value ?: return
        val originSession = session(origin)
        if (originSession.busy.value) return
        // Тот же порядок, что уже стоит в плитке (см. planQueue): лёгкие вперёд,
        // не влезающие — в хвосте.
        val jobs = planQueue(originSession.items.value, fsOf(origin), filesOf(origin)).order
        if (jobs.isEmpty()) { originSession.status.value = "Select a file first."; originSession.kind.value = 2; return }
        if (client(origin)?.link?.value != Link.Ready) {
            originSession.status.value = "Not connected."; originSession.kind.value = 2; return
        }

        // Партнёров берём по адресу, а не по объекту клиента: авто-переподключение
        // создаёт НОВЫЙ BleClient, и захваченная на всю пачку ссылка указывала бы
        // на закрытый. Отфильтровываем тех, кто успел отвалиться или у кого уже
        // есть своя, ещё не отправленная очередь — трогать её нельзя.
        val extra = originSession.syncTargets.value.filter {
            client(it)?.link?.value == Link.Ready && session(it).items.value.isEmpty()
        }
        val targetAddrs = (listOf(origin) + extra).distinct()

        // Сканирование и заливка делят одно радио: LOW_LATENCY-поиск поверх
        // передачи отбирает у неё эфир. Экран списка колёс сам возобновит
        // поиск, когда заливка кончится везде (см. [anyUploadBusy]).
        stopScan()

        viewModelScope.launch {
            // Свежие fs_info/list по КАЖДОЙ цели — место на флеше и содержимое
            // библиотеки могли измениться с прошлого раза, и у разных колёс они
            // никак не обязаны совпадать. Потолок кадров устройство считает от
            // ПОЛНОГО объёма PSRAM (играет всегда одна анимация), так что он не
            // зависит от того, что сейчас на ободе, — только от самого колеса.
            coroutineScope {
                for (addr in targetAddrs) launch infoFetch@{
                    val c = client(addr) ?: return@infoFetch
                    runCatching { c.fsInfo() }.getOrNull()?.let {
                        fsInfoByAddr[addr] = it; if (current.value == addr) fsInfo.value = it
                    }
                    runCatching { c.list() }.getOrNull()?.let {
                        filesByAddr[addr] = it; if (current.value == addr) files.value = it
                    }
                }
            }
            val validTargets = targetAddrs.filter { client(it)?.link?.value == Link.Ready }
            if (validTargets.isEmpty()) {
                originSession.status.value = "Not connected."; originSession.kind.value = 2
                return@launch
            }

            // У каждой цели — своя копия очереди и свой прогресс с этого момента.
            for (addr in validTargets) {
                val s = session(addr)
                s.items.value = jobs
                s.sel.value = 0
                // Анимированные превью — по Uri, так что у партнёров синхронной
                // заливки те же клипы, что на странице-источнике.
                if (s !== originSession) s.clips.value = originSession.clips.value
                s.currentUri.value = null
                s.progress.value = -1f
                s.status.value = "Starting…"
                s.kind.value = 0
                s.busy.value = true
            }
            recomputeUploadBusyAddrs()

            // Оба (или больше) целевых линка держим быстрыми — заливка правда
            // идёт на все разом; «мягким» становится только колесо, случайно
            // подключённое, но не участвующее в этой пачке.
            focusUploadLinks(validTargets.toSet())

            // Конвертация — дорогая (median cut по десяткам тысяч пикселей на
            // кадр), а у большинства пар колёс потолок кадров совпадает, так что
            // результат делят все цели с одинаковым maxFrames. Кэш и общий
            // прогресс конвертации — на весь вызов, конвертируем каждую пару
            // (файл, потолок) ровно один раз.
            val convCache = HashMap<Pair<Uri, Int>, Deferred<Converter.Result>>()
            val targetsByCap = validTargets.groupBy { (fsInfoByAddr[it] ?: FsInfo()).maxFrames }
            val cachedPreviews = HashSet<Uri>()

            suspend fun getConverted(item: UpItem, cap: Int): Converter.Result {
                val key = item.uri to cap
                val deferred = convCache.getOrPut(key) {
                    val sharers = targetsByCap[cap].orEmpty()
                    async(Dispatchers.Default) {
                        converter.convert(
                            item.uri, item.fit, cap,
                            Converter.VideoOpts(item.fps, item.lengthSec),
                            item.mirror,
                            object : Converter.Progress {
                                override fun stage(text: String) {
                                    sharers.forEach { session(it).status.value = item.name + " — " + text }
                                }
                                override fun frames(done: Int, total: Int) {
                                    sharers.forEach { a ->
                                        val s = session(a)
                                        s.status.value = item.name + " — converting " + done + "/" + total +
                                            " frames (" + (done * 100 / maxOf(total, 1)) + "%)"
                                        s.progress.value = done.toFloat() / maxOf(total, 1)
                                    }
                                }
                            }
                        )
                    }
                }
                return deferred.await()
            }

            // Радио у телефона одно на оба соединения — параллельны только
            // независимые ПРОХОДЫ целей и конвертация (CPU, см. getConverted
            // выше), а сама передача байт по BLE — нет: без общего замка два
            // одновременных upload() делят один радиомодуль пополам-и-хуже
            // (неуправляемо, в зависимости от качества каждой связи, а не
            // поровну), и то колесо, чей сигнал слабее, могло реально почти
            // не продвигаться, пока другое улетало вперёд по всей очереди —
            // именно это и читалось как «жёлтый ободок навсегда завис на
            // одном и том же файле», хотя на самом деле файл просто и правда
            // ещё не долился. radioMutex превращает это в явные ПО ОЧЕРЕДИ
            // передачи вместо непредсказуемой делёжки эфира — так у каждой
            // цели прогресс её текущего файла реально движется, пока её ход,
            // а не имитирует движение чужим трафиком.
            val radioMutex = Mutex()
            // Пока идёт пачка, ВСЕ её колёса держим бодрыми. Сами байты сбрасывают
            // колесу таймер сна, но у пачки есть долгие паузы без обмена: радио
            // отдаётся целям по очереди (radioMutex), и колесо ждёт, пока тяжёлый
            // файл льётся на соседа; телефон минутами конвертирует ролик; колесо,
            // закончившее свою часть, ждёт остальных. С подключённым телефоном
            // колесо засыпает после 5 минут без команд — и засыпало посреди
            // большой синхронной заливки. OP_HELLO ничего не меняет на колесе, а
            // активностью считается (и старой прошивкой тоже).
            val keepAwake = launch {
                val pings = HashMap<String, kotlinx.coroutines.Job>()
                while (isActive) {
                    delay(UPLOAD_KEEPAWAKE_MS)
                    for (addr in validTargets) {
                        val c = client(addr)?.takeIf { it.link.value == Link.Ready } ?: continue
                        // Предыдущий ещё ждёт своей очереди за передачей — не копим.
                        if (pings[addr]?.isActive == true) continue
                        pings[addr] = launch { runCatching { c.keepAwake() } }
                    }
                }
            }
            try {
                coroutineScope {
                    for (addr in validTargets) launch {
                        runUploadTarget(addr, jobs, ::getConverted, cachedPreviews, radioMutex)
                    }
                }
            } finally {
                keepAwake.cancel()
            }

            restoreUploadLinks()   // все колёса обратно на быстрый линк
        }
    }

    /**
     * Проводит ОДНУ цель через всю пачку [jobs] от начала до конца, независимо
     * от того, как идут дела у остальных целей той же заливки (см. [startUpload]).
     * Свою очередь, статус и прогресс ведёт только в СВОЕЙ [UploadSession] —
     * соседняя страница узнаёт о заливке только если она сама входит в число
     * целей. [getConverted] — общий с другими целями кэш конвертации по
     * (файл, потолок кадров); [cachedPreviews] — общий набор «превью уже
     * отрендерено», чтобы не строить один и тот же клип для каждой цели заново;
     * [radioMutex] — общий на всю заливку замок вокруг самой передачи байт по
     * BLE (см. комментарий в [startUpload]), без него прогресс на более
     * слабой связи выглядел зависшим на одном файле, пока сильная связь
     * забирала себе почти весь эфир.
     */
    private suspend fun runUploadTarget(
        addr: String,
        jobs: List<UpItem>,
        getConverted: suspend (UpItem, Int) -> Converter.Result,
        cachedPreviews: MutableSet<Uri>,
        radioMutex: Mutex
    ) = coroutineScope {
        val s = session(addr)
        // Держит именно ЭТО колесо на связи своим собственным 5-минутным
        // таймером простоя (idle_limit_ms в main.cpp) на всё время его участия
        // в заливке — не только пока в него реально льются байты. Конвертация
        // — чистое CPU-время на телефоне, ни одного байта по радио; а при
        // синхронной заливке на несколько колёс сюда добавляется ожидание
        // своей очереди на [radioMutex] — оба случая легко растягиваются за
        // 5 минут на большой пачке файлов, и колесо, к которому в этот момент
        // ничего не шло, засыпало посреди загрузки. Прошивка нарочно не
        // считает активностью OP_TELE/OP_FRAG (см. handleCmd в povble.cpp —
        // иначе забытая открытая вкладка держала бы колесо бодрым вечно), а
        // fsInfo() — обычная команда, которая идёт в счёт, и заодно не
        // бесполезная: свежее свободное место и так нужно для проверки,
        // влезает ли следующий файл.
        val keepAliveJob = launch {
            while (isActive) {
                delay(90_000)
                val c = client(addr) ?: continue
                if (c.link.value == Link.Ready) {
                    runCatching { c.fsInfo() }.getOrNull()?.let {
                        fsInfoByAddr[addr] = it
                        if (current.value == addr) fsInfo.value = it
                    }
                }
            }
        }

        var ok = 0
        var skip = 0
        var fail = 0

        // Очередь на экране — ЖИВАЯ: пока льётся один файл, остальные можно убрать
        // длинным тапом (removeUpItem). Поэтому прошедший файл вычёркиваем из
        // текущего списка сессии, а не пересобираем список из снимка [jobs] —
        // иначе убранный файл возвращался в очередь и заливался следом.
        fun dropItem(item: UpItem) {
            val left = s.items.value.filter { it.uri != item.uri }
            s.items.value = left
            s.sel.value = s.sel.value.coerceIn(0, maxOf(0, left.size - 1))
        }

        try {
        for (item in jobs) {
            // Убран из очереди, пока заливались предыдущие, — пропускаем.
            if (s.items.value.none { it.uri == item.uri }) continue
            val c = client(addr)
            if (c == null || c.link.value != Link.Ready) {
                fail++
                s.status.value = item.name + " — lost connection"; s.kind.value = 2
                dropItem(item)
                continue
            }
            s.currentUri.value = item.uri
            s.progress.value = -1f

            val cap = (fsInfoByAddr[addr] ?: FsInfo()).maxFrames
            // Заведомо не влезет — пропускаем ДО конвертации: размер файла на колесе
            // известен заранее точно (те же кадры, что даст конвертер), и тратить
            // минуту на тяжёлое видео ради отказа незачем.
            val est = upEstimatedBytes(item, cap)
            // Уже на колесе (плитка показывает такой файл серым с «✓ on wheel») —
            // пропускаем тоже до конвертации, а не после неё, как раньше.
            if (est != null && sameOnWheel(item, est, filesByAddr[addr] ?: emptyList())) {
                ok++; skip++
                s.status.value = item.name + " — already there, skipped"; s.kind.value = 1
                dropItem(item)
                continue
            }
            val fs0 = fsInfoByAddr[addr]
            if (est != null && fs0 != null && fs0.total > 0L) {
                val dup0 = expectedName(item)?.let { n -> (filesByAddr[addr] ?: emptyList()).firstOrNull { it.name == n } }
                val free0 = fs0.free + (dup0?.size ?: 0L)
                // Точная копия уже залитого места не берёт — её пропустят ниже.
                if (!(dup0 != null && dup0.size == est) && est + UPLOAD_MARGIN > free0) {
                    fail++
                    s.status.value = item.name + " — not enough free memory (" + fmtKb(est) +
                        " > " + fmtKb(maxOf(0L, free0 - UPLOAD_MARGIN)) + " free)"
                    s.kind.value = 2
                    dropItem(item)
                    continue
                }
            }
            s.status.value = item.name + " — converting…"
            val res = try {
                getConverted(item, cap)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, а не Exception: длинная анимация — это 8 МБ исходника
                // плюс столько же под сжатый поток, и OutOfMemoryError здесь вполне
                // достижим. Он наследуется от Error, мимо catch(Exception) проходил
                // насквозь и ронял приложение вместо сообщения об ошибке.
                fail++
                val why = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                s.status.value = item.name + " — failed: " + why; s.kind.value = 2
                dropItem(item)
                continue
            }
            res.warning?.let { say(it) }

            // «Уже есть» — то же имя (оно у ANI6 детерминировано от исходного
            // файла, см. Ani6.buildFileName) И тот же размер: библиотека этого
            // колеса уже содержит ровно то, что мы собрались туда лить.
            val existing = filesByAddr[addr] ?: emptyList()
            val dup = existing.firstOrNull { it.name == res.fileName }
            if (dup != null && dup.size == res.data.size.toLong()) {
                ok++; skip++
                s.status.value = item.name + " — already there, skipped"; s.kind.value = 1
                dropItem(item)
                continue
            }

            // Разные колёса — разное свободное место на флеше; переливаемый файл
            // может влезть на одно и не влезть на другое.
            val fs = fsInfoByAddr[addr] ?: FsInfo()
            val freeAfter = fs.free + (dup?.size ?: 0L)   // старый файл того же имени будет перезаписан
            // Тот же запас, что требует прошивка (OP_UP_BEGIN): иначе файл, прошедший
            // эту проверку впритык, колесо всё равно отвергло бы.
            if (res.data.size + UPLOAD_MARGIN > freeAfter) {
                fail++
                s.status.value = item.name + " — not enough free memory (" + fmtKb(res.data.size.toLong()) +
                    " > " + fmtKb(maxOf(0L, freeAfter - UPLOAD_MARGIN)) + " free)"
                s.kind.value = 2
                dropItem(item)
                continue
            }

            try {
                val crc = withContext(Dispatchers.Default) { Ani6.crc32(res.data) }
                val wire = withContext(Dispatchers.Default) {
                    Ani6.encodeForWire(res.data, c.hello?.hasDeflate ?: false)
                }
                val ratio = res.data.size.toDouble() / maxOf(wire.bytes.size, 1)
                val label = item.name
                // Сама передача — под общим radioMutex (см. комментарии в
                // startUpload/runUploadTarget): пока эфир занят другой целью,
                // статус честно говорит об этом, а не показывает 0%/тишину,
                // которую легко принять за зависание.
                if (radioMutex.isLocked) s.status.value = label + " — waiting for radio…"
                // До UPLOAD_RETRIES повторов: связь по BLE рвётся и по бытовым
                // поводам (колесо отъехало, помеха), а колесо при ошибке само
                // закрывает недолитый файл (OP_UP_ABORT, обрыв — см. streamBody),
                // так что заливать заново безопасно. Не повторяем то, что повтор не
                // исправит: нет места, неверное имя/размер.
                var attempt = 0
                while (true) {
                    val cc = waitReady(addr)
                        ?: throw BleException("lost connection")
                    try {
                radioMutex.withLock {
                    val started = System.currentTimeMillis()
                    cc.upload(res.fileName, wire.bytes, res.data.size, crc, wire.compressed) { p ->
                        s.progress.value = p.sent.toFloat() / maxOf(p.totalWire, 1L)
                        val kb = p.sent / 1024
                        val tot = p.totalWire / 1024
                        val secs = (System.currentTimeMillis() - started) / 1000.0
                        val rate = if (secs > 0.4) (p.sent / 1024.0 / secs) else 0.0
                        s.status.value = label + " — " +
                            (p.sent * 100 / maxOf(p.totalWire, 1L)) + "%  ·  " +
                            kb + " / " + tot + " kB" +
                            (if (wire.compressed) String.format("  ·  x%.1f smaller", ratio) else "") +
                            (if (rate > 0) String.format("  ·  %.0f kB/s", rate) else "")
                    }
                }
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val st = (e as? BleException)?.status ?: -1
                        if (attempt >= UPLOAD_RETRIES || st == ST_NO_SPACE || st == ST_BAD_ARG) throw e
                        attempt++
                        s.progress.value = -1f
                        s.status.value = label + " — retry " + attempt + "/" + UPLOAD_RETRIES +
                            " (" + (e.message ?: "upload failed") + ")"
                        // «Занято» — на колесе ещё висит прошлая передача; её
                        // сторож снимает за 10 с (XFER_STALL_MS в прошивке).
                        delay(if (st == ST_BUSY) 11_000L else 2_000L * attempt)
                    }
                }
                ok++
                val fl = existing.toMutableList()
                if (dup != null) fl.remove(dup)
                fl.add(DevFile(res.fileName, res.data.size.toLong()))
                filesByAddr[addr] = fl
                fsInfoByAddr[addr] = fs.copy(free = fs.free - res.data.size + (dup?.size ?: 0L))
                // Точное свободное место — у самого колеса (блоки ФС, служебное):
                // по нему пересчитывается, кому из оставшихся в очереди не хватит места.
                client(addr)?.takeIf { it.link.value == Link.Ready }?.let { cl ->
                    runCatching { cl.fsInfo() }.getOrNull()?.let { fsInfoByAddr[addr] = it }
                }
                if (current.value == addr) { files.value = fl; fsInfo.value = fsInfoByAddr[addr]!! }
                withContext(Dispatchers.IO) { dropThumb(res.fileName) }
                // Рендерим и кладём в кэш компактное превью, пока доступ к
                // исходнику ещё жив — один раз на файл, не на каждую цель.
                if (cachedPreviews.add(item.uri)) cachePreview(item, res.fileName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail++
                say((c.hello?.name ?: addr) + ": " + (e.message ?: "upload failed"))
                s.status.value = item.name + " — failed: " + (e.message ?: "upload failed"); s.kind.value = 2
            }
            dropItem(item)
        }

        s.progress.value = -1f
        s.currentUri.value = null
        s.busy.value = false
        val msg = when {
            fail > 0 -> ok.toString() + " uploaded, " + fail + " failed."
            skip > 0 && ok > skip -> (ok - skip).toString() + " uploaded, " + skip + " already there."
            skip > 0 -> "Already there — nothing to upload."
            ok == 1 -> "Uploaded."
            else -> ok.toString() + " files uploaded."
        }
        s.status.value = msg
        s.kind.value = if (fail > 0) 2 else 1
        recomputeUploadBusyAddrs()
        // Тостом сообщаем только когда всё прошло чисто — иначе строка статуса
        // под самой сеткой и так на виду, повторять её всплывающим тостом незачем.
        if (fail == 0) say((client(addr)?.hello?.name ?: addr) + ": " + msg)
        } finally {
            keepAliveJob.cancel()
        }
    }

    /** Связь с колесом [addr] — дождаться, если она переподключается (до 20 с). */
    private suspend fun waitReady(addr: String): BleClient? {
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            val c = client(addr)
            if (c != null && c.link.value == Link.Ready) return c
            if (System.currentTimeMillis() > deadline) return null
            delay(250)
        }
    }

    private fun fmtKb(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        else -> (bytes / 1024).coerceAtLeast(1L).toString() + " kB"
    }

    /** Перед заливкой: линки всех целей [targets] — быстрые, все прочие
     *  подключённые (в этой заливке не участвующие) — «мягкие». Два активных
     *  BLE-соединения на один радиомодуль телефона делят эфир, из-за чего
     *  скорость на активное колесо падала впятеро. */
    private suspend fun focusUploadLinks(targets: Set<String>) {
        val cs = clients.values.toList()
        if (cs.size < 2) return
        for (c in cs) if (c.link.value == Link.Ready) c.setLowPower(c.address !in targets)
    }

    private suspend fun restoreUploadLinks() {
        for (c in clients.values.toList()) if (c.link.value == Link.Ready) c.setLowPower(false)
    }

    fun refreshFiles() {
        val addr = current.value ?: return
        val c = clients[addr] ?: return
        viewModelScope.launch {
            runCatching { c.list() }.getOrNull()?.let {
                filesByAddr[addr] = it
                if (current.value == addr) files.value = it
            }
            runCatching { c.fsInfo() }.getOrNull()?.let {
                fsInfoByAddr[addr] = it
                if (current.value == addr) fsInfo.value = it
            }
            // Убрать превью удалённых файлов и удержать кэш в пределах потолка.
            prunePreviewCache(files.value.map { it.name }.toHashSet(), strict = connected.value.size <= 1)
        }
    }

    /**
     * Сносит превью, для которых на текущем колесе больше нет файла (только при
     * одном подключении — у двух подключённых колёс разные библиотеки, и файла,
     * которого нет на одном, может не быть превью зря удалённого), и обрезает
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
                    if (!pf.name.endsWith(".pvc") && !pf.name.endsWith(".pvf")) continue
                    if (strict && pf.name.dropLast(4) !in keep) { pf.delete(); continue }
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

    /** Выполняет [block] на открытом колесе, если оно на связи. */
    private fun onTargets(block: suspend (BleClient) -> Unit) {
        val c = readyClient() ?: return
        viewModelScope.launch {
            try { block(c) } catch (e: Exception) {
                say((c.hello?.name ?: c.address) + ": " + (e.message ?: "failed"))
            }
        }
    }

    fun play(name: String) {
        endSync(current.value)   // ручной выбор файла рвёт синхронизацию — иначе следующий тик перебьёт его обратно
        onTargets { it.play(name) }
        // «Playing …» с человекочитаемым именем и размером файла — по одному
        // взгляду видно, сколько какой ролик весит.
        val f = files.value.firstOrNull { it.name == name }
        val size = f?.size?.let { s ->
            if (s >= 1_048_576) String.format("%.1f MB", s / 1_048_576.0)
            else (s / 1024).toString() + " kB"
        }
        say("Playing " + (f?.pretty ?: name) + (if (size != null) "  ·  " + size else ""))
    }
    fun stopDisplay() { endSync(current.value); onTargets { it.stop() }; say("Display stopped") }
    fun effect(id: Int) { endSync(current.value); onTargets { it.effect(id) } }

    // ------------------------------------------------------------ эффект «Текст»
    //
    // Маску строки рисует телефон (convert/TextMask), колесо только красит её.
    // Правки из редактора уходят на колесо сразу, пока человек печатает: каждая
    // новая строка — свежая маска, каждое касание палитры — новый цвет.

    /** Текст на колесе: строка, цвет, маска 360 × 44 и сжатый блоб — ровно то,
     *  что лежит на колесе (для кэша экрана без связи). mask == null — не задан.
     *  rgb — слой эмодзи своим цветом (TextMask.TextImage), null — их нет. */
    data class TextFx(
        val text: String = "",
        val style: TextStyle = TextStyle(),
        val mask: ByteArray? = null,
        val blob: ByteArray? = null,
        val rgb: ByteArray? = null
    )

    private fun adoptTextFx(addr: String, t: TextFx) {
        textFxByAddr[addr] = t
        if (current.value == addr) textFx.value = t
    }

    /** Прочитать текст с колеса, если прошивка его знает. Пока открыт
     *  редактор, не затираем набранное пришедшим с колеса старым. */
    private suspend fun fetchTextFx(addr: String, c: BleClient) {
        if (c.hello?.hasText != true) return
        val (st, blob) = runCatching { c.textGet() }.getOrNull() ?: return
        if (textEditing && addr == current.value) return
        if (blob.isEmpty() && textProvisioned.add(addr)) {
            provisionDefaultText(addr, c, st)
            return
        }
        val un = withContext(Dispatchers.Default) { TextMask.unpack(blob) }
        adoptTextFx(addr, TextFx(un?.first ?: "", st, un?.second?.mask, blob.takeIf { un != null }, un?.second?.rgb))
    }

    /** Колёса, которым в этом запуске уже отправляли текст по умолчанию. */
    private val textProvisioned = HashSet<String>()

    /**
     * Текст на колесе ни разу не задавали (блоб пустой) — ставим [DEFAULT_TEXT]
     * радугой. Само колесо до этого показывает голое «Hello World!» своим шрифтом
     * 5×7 (эмодзи в нём нет); отсюда строка уходит нарисованной телефоном, с
     * эмодзи своим цветом, и дальше хранится на колесе, как любой текст. Цвет трогаем, только если он заводской старой прошивки (белый):
     * новая и так начинает с радуги, а выбранный руками цвет не перезаписываем.
     */
    private suspend fun provisionDefaultText(addr: String, c: BleClient, st: TextStyle) {
        val colour = c.hello?.hasTextRgb == true
        val (img, blob) = withContext(Dispatchers.Default) { TextMask.build(DEFAULT_TEXT, colour) }
        val style = if (!st.rainbow && st.rgb == 0xFFFFFF && st.speed == 30) st.copy(rainbow = true) else st
        try {
            c.textSet(blob)
            if (style != st) c.textStyle(style)
            adoptTextFx(addr, TextFx(DEFAULT_TEXT, style, img.mask, blob, img.rgb))
        } catch (_: Exception) {
            adoptTextFx(addr, TextFx("", st, null, null))
        }
    }

    // Правки — последним значением: StateFlow сам пропускает промежуточные,
    // пока уходит предыдущее, и отправка не отстаёт от набора. Счётчик — чтобы
    // одинаковая строка, набранная снова, всё равно ушла.
    private data class TextEdit(val addr: String, val text: String, val n: Long)
    private data class StyleEdit(val addr: String, val style: TextStyle, val n: Long)
    private val textEdits = MutableStateFlow<TextEdit?>(null)
    private val styleEdits = MutableStateFlow<StyleEdit?>(null)
    private var editSeq = 0L
    @Volatile private var textEditing = false
    // На каком колесе в этом сеансе редактора эффект уже запущен — второй раз
    // OP_EFFECT не шлём: колесо гасит ленту на время перезапуска эффекта.
    private var textStartedFor: String? = null

    init {
        viewModelScope.launch {
            textEdits.collect { e ->
                if (e == null) return@collect
                // Слой эмодзи своим цветом — только колесу, которое его понимает;
                // прежняя прошивка красит их выбранным цветом, как буквы.
                val colour = client(e.addr)?.hello?.hasTextRgb ?: true
                val (img, blob) = withContext(Dispatchers.Default) { TextMask.build(e.text, colour) }
                adoptTextFx(e.addr, (textFxByAddr[e.addr] ?: TextFx())
                    .copy(text = e.text, mask = img.mask, blob = blob, rgb = img.rgb))
                val c = client(e.addr)?.takeIf { it.link.value == Link.Ready && it.hello?.hasText == true }
                    ?: return@collect
                try {
                    c.textSet(blob)
                    ensureTextPlaying(e.addr, c)
                } catch (ex: Exception) {
                    say((c.hello?.name ?: e.addr) + ": " + (ex.message ?: "text not sent"))
                }
            }
        }
        viewModelScope.launch {
            styleEdits.collect { e ->
                if (e == null) return@collect
                val c = client(e.addr)?.takeIf { it.link.value == Link.Ready && it.hello?.hasText == true }
                    ?: return@collect
                try {
                    c.textStyle(e.style)
                    ensureTextPlaying(e.addr, c)
                } catch (ex: Exception) {
                    say((c.hello?.name ?: e.addr) + ": " + (ex.message ?: "colour not sent"))
                }
            }
        }
    }

    /** Первая правка в редакторе включает эффект на колесе — набранное сразу
     *  видно на ободе. */
    private suspend fun ensureTextPlaying(addr: String, c: BleClient) {
        if (textStartedFor == addr) return
        textStartedFor = addr
        if (c.tele.value.effect != TEXT_EFFECT_ID) {
            endSync(addr)
            c.effect(TEXT_EFFECT_ID)
        }
    }

    fun beginTextEdit() { textEditing = true; textStartedFor = null }
    fun endTextEdit() { textEditing = false }

    fun editText(text: String) {
        val addr = current.value ?: return
        val t = TextMask.clip(text)
        // Строка в состоянии — сразу (подпись), маска догонит после отрисовки.
        adoptTextFx(addr, (textFxByAddr[addr] ?: TextFx()).copy(text = t))
        textEdits.value = TextEdit(addr, t, ++editSeq)
    }

    fun editTextStyle(st: TextStyle) {
        val addr = current.value ?: return
        adoptTextFx(addr, (textFxByAddr[addr] ?: TextFx()).copy(style = st))
        styleEdits.value = StyleEdit(addr, st, ++editSeq)
    }

    /** Колесо открытого экрана умеет эффект «Текст» (или он уже знаком из кэша). */
    fun hasTextEffect(): Boolean =
        currentClient()?.hello?.hasText == true || textFx.value.mask != null

    // ------------------------------------------------------ параметры эффектов
    //
    // Красная точка Speed, скорость и резкость Rainbow, цвет Clock (OP_FX_GET /
    // OP_FX_SET). Правки из диалогов уходят на колесо сразу, как у «Текста», —
    // последним значением, промежуточные StateFlow пропускает сам. В синхронной
    // группе правка тех эффектов, что выбраны в показ, расходится и по остальным
    // колёсам (см. [syncFxToGroup]); «Текст» не синхронизируется никогда — у
    // каждого колеса своя строка и свой цвет.

    private fun adoptFx(addr: String, p: FxParams) {
        fxByAddr[addr] = p
        if (current.value == addr) fxParams.value = p
    }

    /** Прочитать с колеса, если прошивка умеет. Пока открыт диалог — не затираем. */
    private suspend fun fetchFx(addr: String, c: BleClient) {
        if (c.hello?.hasFx != true) return
        val p = runCatching { c.fxGet() }.getOrNull() ?: return
        if (fxEditing && addr == current.value) return
        adoptFx(addr, p)
    }

    private data class FxEdit(val addr: String, val p: FxParams, val effectId: Int, val n: Long)
    private val fxEdits = MutableStateFlow<FxEdit?>(null)
    @Volatile private var fxEditing = false
    // На каком колесе в этом сеансе диалога эффект уже запущен (как у «Текста»).
    private var fxStartedFor: String? = null

    init {
        viewModelScope.launch {
            fxEdits.collect { e ->
                if (e == null) return@collect
                val c = client(e.addr)?.takeIf { it.link.value == Link.Ready && it.hello?.hasFx == true }
                    ?: return@collect
                try {
                    c.fxSet(e.p)
                    ensureFxPlaying(e.addr, c, e.effectId)
                } catch (ex: Exception) {
                    say((c.hello?.name ?: e.addr) + ": " + (ex.message ?: "effect settings not sent"))
                }
                syncFxToGroup(e.addr, e.p)
            }
        }
    }

    /**
     * Первая правка в диалоге включает сам эффект — результат сразу виден на
     * ободе. Кроме идущего слайдшоу (обычного или синхронного): правка настроек
     * не должна его рвать — эффект покажется в свою очередь уже новым.
     */
    private suspend fun ensureFxPlaying(addr: String, c: BleClient, effectId: Int) {
        if (fxStartedFor == addr) return
        fxStartedFor = addr
        if (syncGroups.containsKey(addr) || c.tele.value.slideshow) return
        if (c.tele.value.effect != effectId) c.effect(effectId)
    }

    fun beginFxEdit() { fxEditing = true; fxStartedFor = null }
    fun endFxEdit() { fxEditing = false }

    /** Правка из диалога эффекта [effectId] (Speed, Rainbow или Clock). */
    fun editFx(effectId: Int, p: FxParams) {
        val addr = current.value ?: return
        adoptFx(addr, p)
        fxEdits.value = FxEdit(addr, p, effectId, ++editSeq)
    }

    /** Колесо открытого экрана умеет настройки эффектов (OP_FX_*). */
    fun hasFxSettings(): Boolean = currentClient()?.hello?.hasFx == true

    /**
     * Из [src] — только поля эффектов, выбранных в показ ([tokens] — `@eN`):
     * у партнёра остаются его собственные настройки эффектов, которых в
     * синхронном показе нет.
     */
    private fun mergeFx(base: FxParams, src: FxParams, tokens: Collection<String>): FxParams {
        var r = base
        if ("@e1" in tokens) r = r.copy(speedRed = src.speedRed)
        if ("@e3" in tokens) r = r.copy(rbSpeed = src.rbSpeed, rbSharp = src.rbSharp)
        if ("@e6" in tokens) r = r.copy(clock = src.clock)
        return r
    }

    /**
     * Колесо [addr] в синхронной группе и в её показе есть Speed/Rainbow/Clock —
     * их настройки с [src] расходятся по остальным участникам. Все сразу, а не по
     * очереди: слабая связь с одним колесом не должна задерживать остальные.
     */
    private suspend fun syncFxToGroup(addr: String, src: FxParams, freshBase: Boolean = false) {
        val group = syncGroups[addr] ?: return
        val tokens = group.files.filter { isSlideEffect(it) }
        if (tokens.none { it == "@e1" || it == "@e3" || it == "@e6" }) return
        coroutineScope {
            for (m in group.members) {
                if (m == addr) continue
                val c = clients[m]?.takeIf { it.link.value == Link.Ready && it.hello?.hasFx == true } ?: continue
                launch {
                    // Во время перетаскивания ползунка — по кэшу (лишний круг по
                    // радио на каждое значение ни к чему), на старте показа — с колеса.
                    val cached = if (freshBase) null else fxByAddr[m]
                    val base = cached ?: runCatching { c.fxGet() }.getOrNull() ?: fxByAddr[m] ?: return@launch
                    val merged = mergeFx(base, src, tokens)
                    if (merged == base) return@launch
                    if (runCatching { c.fxSet(merged) }.isSuccess) adoptFx(m, merged)
                }
            }
        }
    }

    fun album(start: Boolean, ms: Int) {
        if (start) endSync(current.value)
        onTargets { it.album(start, ms) }
    }

    // ---------------------------------------------------------- слайдшоу

    private fun slideSelKey(addr: String) = "slidesel_" + addr

    /** Токены эффектов в наборе выбранного для слайдшоу: `@e1`, `@e3`… — по
     *  существующим эффектам (2 и 5 удалены из прошивки, их токены из старого
     *  сохранённого отбора просто отсеиваются). */
    val slideEffectTokens: List<String> = com.povwheel.app.ui.EFFECT_IDS.map { "@e" + it }
    fun isSlideEffect(token: String) = token in slideEffectTokens

    /** Маска эффектов для OP_ALBUM: бит N-1 — эффект N (EFF_SLIDE_MASK). */
    private fun effectMaskOf(checked: Collection<String>): Int =
        checked.mapNotNull { slideEffectId(it) }.fold(0) { m, id -> m or (1 shl (id - 1)) }

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

    /** Отбор, готовый уйти в `BleClient.album()`. [overflowed] — оба списка,
     *  include и exclude, длиннее, чем одна ATT-посылка выдержит (~20 имён),
     *  а колесо не умеет длинный отбор; тогда шлём "все файлы" и вызывающий
     *  решает, стоит ли об этом сказать. */
    private data class AlbumSelection(
        val mode: Int, val names: List<String>, val effectMask: Int, val overflowed: Boolean
    )

    /** [checked] (имена файлов + токены эффектов `@eN`) → [AlbumSelection]:
     *  маска эффектов плюс include/exclude-список файлов, тот что короче. */
    private fun albumSelectionFor(checked: Set<String>, fileNames: List<String>, longList: Boolean): AlbumSelection {
        val effMask = effectMaskOf(checked)
        val incl = fileNames.filter { it in checked }
        val excl = fileNames.filter { it !in checked }
        return when {
            excl.isEmpty() -> AlbumSelection(0, emptyList(), effMask, false)   // exclude нечего = все файлы
            // Длинный отбор (FEAT_ALBUM_LONG) — всегда include: любой длины, в
            // порядке библиотеки.
            longList -> AlbumSelection(1, incl, effMask, false)
            incl.size <= excl.size && incl.size <= 20 -> AlbumSelection(1, incl, effMask, false)
            excl.size <= 20 -> AlbumSelection(0, excl, effMask, false)
            else -> AlbumSelection(0, emptyList(), effMask, true)
        }
    }

    /**
     * Обрезает [names] по фактическому байтовому бюджету ОДНОЙ ATT-записи
     * OP_ALBUM у соединения с бюджетом [payloadSize]. Запись не режется на
     * куски, а MTU согласовывается на каждое соединение отдельно — у двух
     * колёс он не обязан совпасть, поэтому для группы берётся самый тесный.
     * 19 байт — несократимая часть кадра: 2 байта заголовка самого запроса
     * (опкод+seq, см. `BleClient.requestUnlocked`) плюс 9 байт полей `album()`
     * (start, delay, listMode, count, хвостовая маска эффектов) и 8 байт t0.
     * На каждое имя — его длина в байтах плюс один байт этой длины.
     */
    private fun fitNamesToOneWrite(names: List<String>, payloadSize: Int): List<String> {
        var budget = payloadSize - 19
        return names.takeWhile { n ->
            val cost = 1 + n.toByteArray(Charsets.US_ASCII).size
            if (cost > budget) return@takeWhile false
            budget -= cost
            true
        }
    }

    /** Запустить слайдшоу с отмеченным [checked] (имена файлов + токены эффектов). */
    fun startSlideshow(delaySecs: Int, checked: Set<String>, fileNames: List<String>) {
        if (checked.isEmpty()) { say("Tick at least one item"); return }
        endSync(current.value)   // это НЕ синхронное слайдшоу — снимаем прежнее, если было
        current.value?.let {
            prefs.edit().putString(slideSelKey(it), checked.joinToString(",")).apply()
        }
        val sel = albumSelectionFor(checked, fileNames, currentClient()?.hello?.hasAlbumLong == true)
        val ms = (delaySecs * 1000).coerceIn(1000, 300000)
        onTargets { it.album(true, ms, sel.mode, sel.names, sel.effectMask) }
        say(if (sel.overflowed) "Too many files to pick one by one — all files shown" else "Slideshow started")
    }

    fun stopSlideshow() = onTargets { it.album(false, 0) }.also { say("Slideshow stopped") }

    /**
     * Интервал слайдшоу. [slideIntervalMs] запоминает его для этого адреса
     * ВСЕГДА, синхронный показ идёт или нет, — это и есть то единственное
     * значение, которое экран показывает и предлагает дальше (см. комментарий
     * у [slideIntervalMs]): смена во время синхронного показа обязана остаться
     * в силе и после его остановки, а не откатиться к тому, что было раньше.
     *
     * Если колесо сейчас в группе — новое расписание с новым t0 всем
     * участникам: со старым t0 и другим интервалом номер слота перескочил бы
     * на случайный пункт. Разносим то же число по всем участникам группы для
     * экрана, с какого бы из них ни поменяли, и обновляем копию в
     * [SyncSlideshowService] на случай будущей передачи группы ей. Если
     * обычный (не синхронный) показ уже идёт на устройстве — оно подхватит
     * новый интервал на лету через `OP_ALBUM`, как и раньше.
     */
    fun setSlideInterval(secs: Int) {
        val addr = current.value ?: return
        val ms = (secs * 1000).coerceIn(1000, 300000)
        val group = syncGroups[addr]
        if (group != null) {
            group.intervalMs = ms
            group.t0Ms = newSyncT0()
            slideIntervalMs.value = slideIntervalMs.value + group.members.associateWith { ms }
            SyncSlideshowService.updateConfig(group.files, ms, group.t0Ms)
            persistSyncGroup(group)
            armSyncGroup(group)
        } else {
            slideIntervalMs.value = slideIntervalMs.value + (addr to ms)
            onTargets { c -> if (c.tele.value.slideshow) c.album(true, ms) }
        }
    }
    /** Переименовать колесо [addr]. Требует связи — имя пишется на устройство. */
    fun renameWheel(addr: String, name: String, onDone: (String) -> Unit) {
        val c = clients[addr]
        if (c == null || c.link.value != Link.Ready) {
            onDone("Connect to this wheel first")
            return
        }
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

    /**
     * Выключение в транспортный режим: колесо гаснет и до удержания кнопки уже
     * не проснётся — ни по тряске, ни по BLE.
     *
     * Сначала — хвост лога Холла: фоновый опрос забирает кольцо раз в 3–10 с, а
     * колесо уходит в сон сразу. Во флеш оно его допишет, но телефон прочтёт
     * оттуда лишь после следующего включения кнопкой — а ролик, снятый только
     * что, хотят склеить сейчас.
     */
    fun powerOff() = onTargets {
        if (it.hello?.hasHallLog == true) withTimeoutOrNull(5_000) { it.pullHallLogNow() }
        it.powerOff()
    }

    /**
     * Прошивка по BLE. Транспорт тот же, что у анимаций, но сжатия нет:
     * образ и так почти несжимаем, а устройство пишет его в раздел OTA
     * напрямую, минуя распаковщик (см. OP_OTA_BEGIN в povble.cpp).
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

    // --- Единая сериализованная запись настроек на колесо ---
    // И «живьём» при перетаскивании ползунка/градусов, и по кнопкам/отпусканию —
    // ВСЁ через одну очередь. Раньше pushSettings отменял «живую» задачу
    // (`livePushJob.cancel()`), и если та висела внутри BLE-записи с
    // подтверждением, GATT-слой оставался занят — следующая запись падала с
    // «the radio refused the write» (а изменение при этом успевало примениться
    // с другой попытки). Здесь ничего не отменяется: цикл сам вычерпывает
    // накопленное, не чаще ~10 раз/с, и по флагу дописывает NVS.
    private var settingsJob: kotlinx.coroutines.Job? = null
    @Volatile private var settingsPending: Settings? = null
    @Volatile private var savePending = false
    private val SETTINGS_WRITE_GAP_MS = 100L

    private fun queueSettingsWrite(s: Settings, save: Boolean) {
        settings.value = s
        settingsPending = s
        if (save) savePending = true
        if (settingsJob?.isActive == true) return
        settingsJob = viewModelScope.launch {
            while (true) {
                val snap = settingsPending ?: break
                settingsPending = null
                val doSave = savePending
                savePending = false
                val addr = current.value
                val c = clients[addr]
                if (c != null && c.link.value == Link.Ready) {
                    try {
                        c.setSettings(snap)
                        if (doSave) c.save()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) {}
                }
                delay(SETTINGS_WRITE_GAP_MS)
            }
        }
    }

    /** Промежуточное значение во время перетаскивания — применяется сразу, без NVS. */
    fun pushSettingsLive(s: Settings) {
        if (settingsLoaded.value) queueSettingsWrite(s, save = false)
    }

    /** Авторитетная отправка (отпускание пальца, кнопка, поле ввода) + запись в NVS. */
    fun pushSettings(s: Settings) {
        if (!settingsLoaded.value) {
            // Отправить сейчас значило бы записать на устройство наши значения
            // по умолчанию вместо его собственных.
            say("Settings are still loading")
            refreshAll()
            return
        }
        queueSettingsWrite(s, save = true)
        // Идёт синхронный показ — rpm-пороги, диапазон авто-яркости и Color
        // Correction общие для всей группы (см. applyGroupSyncedFields): смену
        // любого из них на ЭТОМ колесе — не важно, с какого из группы — сразу
        // подхватывают и остальные участники.
        current.value?.let { syncGroups[it] }?.let { applyGroupSyncedFields(it, s) }
    }

    fun saveSettings() {
        if (settingsLoaded.value) queueSettingsWrite(settings.value, save = true)
    }

    fun delete(name: String) = deleteMany(listOf(name))

    /** Удаляет несколько файлов с ТЕКУЩЕГО колеса, заодно чистит их локальные
     *  превью. Обычно НЕ зеркалит на другие колёса — у них разные библиотеки —
     *  но если текущее колесо синхронизировано ([syncGroups]) и среди
     *  удалённых есть файлы из общего списка, спрашивает через
     *  [pendingSyncDelete], удалить ли их и у остальных участников группы:
     *  иначе синхронный показ наткнётся на файл, которого больше нет у кого-то
     *  из них. */
    fun deleteMany(names: List<String>) {
        if (names.isEmpty()) return
        val addr = current.value
        val c = currentClient() ?: return
        val group = addr?.let { syncGroups[it] }
        viewModelScope.launch {
            var ok = 0
            for (n in names) {
                try { c.delete(n); ok++ } catch (e: Exception) { say("Delete failed: " + n) }
                synchronized(clipMem) { clipMem.remove(n) }
                withContext(Dispatchers.IO) {
                    runCatching { PreviewClips.fileFor(previewDir, n).delete() }
                    dropThumb(n)
                }
            }
            runCatching { files.value = c.list() }
            runCatching { fsInfo.value = c.fsInfo() }
            if (ok == 1) say("Deleted " + names.first())
            else if (ok > 1) say("Deleted " + ok + " files")

            if (group != null) {
                val shared = names.filter { it in group.files }
                group.files = group.files - names.toSet()
                if (group.files.isEmpty()) {
                    endSync(addr)
                    say("Synced slideshow stopped — no shared files left")
                } else {
                    // Группа продолжает идти без удалённых файлов — с новым
                    // расписанием (t0): со старым у колёс посреди круга
                    // сдвинулась бы нумерация пунктов. Служебной копии
                    // конфигурации (на случай передачи группы в
                    // SyncSlideshowService) тоже сообщаем.
                    group.t0Ms = newSyncT0()
                    SyncSlideshowService.updateConfig(group.files, group.intervalMs, group.t0Ms)
                    persistSyncGroup(group)
                    armSyncGroup(group)
                }
                if (shared.isNotEmpty()) {
                    val partners = group.members.filter { it != addr }.map { p ->
                        p to (wheels.value.firstOrNull { w -> w.address == p }?.name ?: "the other wheel")
                    }
                    if (partners.isNotEmpty()) pendingSyncDelete.value = PendingSyncDelete(shared, partners)
                }
            }
        }
    }

    /** Ответ на диалог [pendingSyncDelete]: удалить те же файлы у остальных
     *  участников группы тоже. */
    fun confirmSyncDelete(doIt: Boolean) {
        val pend = pendingSyncDelete.value ?: return
        pendingSyncDelete.value = null
        if (!doIt) return
        viewModelScope.launch {
            for ((addr, _) in pend.partners) {
                val c = clients[addr] ?: continue
                for (n in pend.names) runCatching { c.delete(n) }
                val list = runCatching { c.list() }.getOrNull()
                if (list != null) {
                    filesByAddr[addr] = list
                    if (current.value == addr) files.value = list
                }
            }
            say("Deleted on " + pend.partners.joinToString(", ") { it.second } + " too")
        }
    }

    // --------------------------------------------------------------- превью

    /**
     * Первый кадр файла — плитке, у которой нет локального клипа (файл залит с
     * другого телефона или до переустановки приложения). Память → диск → колесо
     * (OP_PREVIEW). Полученный по BLE кадр ложится на диск рядом с клипами, так что
     * тянется один раз на файл, а не на каждый запуск, и виден и без связи.
     * null — колеса нет на связи или оно не ответило: плитка попросит снова
     * ([canFetchThumb]).
     */
    suspend fun thumb(f: DevFile): PreviewFrame? {
        val key = f.name + "|" + f.size
        thumbs[key]?.let { return it }
        withContext(Dispatchers.IO) { loadThumb(f) }?.let { thumbs[key] = it; return it }
        val c = readyClient() ?: return null
        if (c.hello?.hasPreview == false) return null
        return withContext(Dispatchers.IO) {
            try {
                val p = c.preview(f.name)
                if (p != null) { thumbs[key] = p; saveThumb(f, p) }
                p
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { null }
        }
    }

    /** Есть смысл просить кадр у колеса снова: оно на связи и превью умеет. */
    fun canFetchThumb(): Boolean = readyClient()?.let { it.hello?.hasPreview != false } ?: false

    // Кадр на диске: "PVF1", размер файла на колесе (по нему видно, что файл
    // перезалили), секторы, радиусы, RGB565.
    private fun thumbFile(name: String) = File(previewDir, name + ".pvf")

    private fun loadThumb(f: DevFile): PreviewFrame? = runCatching {
        val file = thumbFile(f.name)
        if (!file.exists()) return null
        val b = java.nio.ByteBuffer.wrap(file.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 10 || b.int != THUMB_MAGIC || b.int.toLong() and 0xFFFFFFFFL != f.size) return null
        val sec = b.get().toInt() and 0xFF
        val rad = b.get().toInt() and 0xFF
        if (sec == 0 || rad == 0 || b.remaining() < sec * rad * 2) return null
        val px = ByteArray(sec * rad * 2); b.get(px)
        PreviewFrame(sec, rad, px)
    }.getOrNull()

    private fun saveThumb(f: DevFile, p: PreviewFrame) {
        runCatching {
            val b = java.nio.ByteBuffer.allocate(10 + p.rgb565.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.putInt(THUMB_MAGIC).putInt(f.size.toInt()).put(p.sectors.toByte()).put(p.radii.toByte()).put(p.rgb565)
            val tmp = File(previewDir, f.name + ".pvf.tmp")
            tmp.writeBytes(b.array())
            if (!tmp.renameTo(thumbFile(f.name))) tmp.delete()
        }
    }

    /** Файл [name] на колесе заменён или удалён — его кадр больше не годится. */
    private fun dropThumb(name: String) {
        thumbs.keys.removeIf { it.startsWith("$name|") }
        runCatching { thumbFile(name).delete() }
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
        povVideo.shutdown()
        btStateReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        btStateReceiver = null
        stopScan()
        handOffActiveSyncGroups()
        handOffConnectivity()
        disconnectAll()
        synchronized(clipMem) { clipMem.clear() }
        super.onCleared()
    }

    /**
     * Приложение закрывается — соединений у этой ViewModel сейчас не станет.
     * Каждую ещё активную группу синхронного показа передаём
     * [SyncSlideshowService]: она подключится к участникам с нуля и будет
     * держать их часы и расписание сама (см. комментарий в начале секции про
     * синхронизацию слайдшоу). Вызывается ДО [disconnectAll] — а сама служба
     * всё равно выжидает короткую паузу перед тем как подключаться, так что
     * порядок здесь не критичен, лишь бы оба шага произошли.
     */
    private fun handOffActiveSyncGroups() {
        val seen = HashSet<SyncGroup>()
        for (group in syncGroups.values) {
            if (!seen.add(group)) continue
            val names = group.members.map { m -> wheels.value.firstOrNull { it.address == m }?.name ?: m }
            SyncSlideshowService.track(group.members, names, group.files, group.intervalMs, group.t0Ms)
            SyncSlideshowService.takeOver(ctx)
        }
    }

    /**
     * То же самое, но для ВСЕХ остальных, не синхронных, колёс — иначе
     * закрытие приложения бросало их без единого способа переподключиться
     * (см. класс [WheelConnectivityService]: скан + автоподключение по тем же
     * правилам, что и здесь, только без экрана). Участников синхронной группы
     * не передаём сюда — их уже забрал [handOffActiveSyncGroups], и служба
     * связи сама пропускает любой адрес, у которого есть трекнутая группа
     * ([SyncSlideshowService.trackedGroupFor]), чтобы не подключаться к нему
     * второй раз параллельно.
     *
     * [hint] — адреса, что были на связи прямо перед закрытием: их можно
     * пробовать подключить сразу по MAC, не дожидаясь свежей рекламы (колесо
     * рекламу не шлёт, пока подключено, — см. комментарий в rebuildWheels).
     * Остальные известные/увиденные колёса подхватит собственное сканирование
     * службы, как только они дадут о себе знать.
     *
     * Если колёс никогда не было (пустой install), службу вообще не поднимаем —
     * нечего сторожить.
     */
    private fun handOffConnectivity() {
        if (knownOrder().isEmpty() && wantConnected.isEmpty()) return
        val syncMembers = syncGroups.keys
        val hint = clients.filterKeys { it !in syncMembers }
            .filterValues { it.link.value == Link.Ready }
            .keys.toList()
        WheelConnectivityService.takeOver(ctx, hint)
    }

    /**
     * Экран снова на переднем плане. Если [WheelConnectivityService] всё это
     * время сторожила колёса сама (приложение было закрыто, не просто
     * свёрнуто) — забираем управление обратно: просим её отпустить свои
     * соединения, недолго ждём подтверждения (та же причина, что и у
     * [SyncSlideshowService] — два параллельных GATT-соединения к одному
     * адресу с одного телефона ненадёжны) и явно подключаемся сами к тому, что
     * она держала, не дожидаясь свежей рекламы (подключённое колесо не
     * рекламирует себя). Своё собственное сканирование эта ViewModel не
     * останавливала на время простого сворачивания (см. [enterBackground]) —
     * `startScan()` здесь безвредна и на этот случай, и как страховка, если
     * что-то всё же остановило её раньше.
     */
    fun enterForeground() {
        if (!WheelConnectivityService.isBusy()) return
        viewModelScope.launch {
            val held = WheelConnectivityService.snapshotDriving()
            WheelConnectivityService.release(ctx)
            withTimeoutOrNull(1500) { while (WheelConnectivityService.isBusy()) delay(50) }
            startScan()
            for (addr in held) {
                if (clients[addr]?.link?.value != Link.Ready) {
                    connect(addr, prefs.getString("name_" + addr, "POV wheel") ?: "POV wheel")
                }
            }
        }
    }

    /**
     * Экран свернули (кнопка Home, переключение на другое приложение) — САМА
     * ViewModel продолжает жить и держать свои соединения ровно как и раньше
     * (Compose не разбирает дерево при простой остановке Activity, только при
     * уничтожении), так что здесь ничего не отключаем и не передаём. Единственная
     * задача — поднять [WheelConnectivityService] в режиме простого ожидания
     * (без своего сканирования и подключений, только уведомление): Android
     * глушит фоновое BLE-сканирование и может убить процесс целиком, если у
     * приложения нет ни одной foreground-службы, — а без этого исчезал бы сам
     * смысл автоподключения «даже когда телефон свёрнут». Если процесс всё же
     * будет уничтожен по-настоящему, дойдёт до [onCleared], и служба сама
     * перейдёт из ожидания в полноценный поиск (см. [handOffConnectivity]).
     */
    fun enterBackground() {
        if (knownOrder().isEmpty() && wantConnected.isEmpty()) return
        WheelConnectivityService.standby(ctx)
    }
}
