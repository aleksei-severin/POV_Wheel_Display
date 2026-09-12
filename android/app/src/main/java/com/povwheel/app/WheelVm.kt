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
import androidx.core.content.ContextCompat
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

    /** На связи или хотя бы в эфире — колесо, на которое имеет смысл переключиться
     *  (тап по имени, свайп). Отсеивает известные, но давно не слышные. */
    val reachable: Boolean get() =
        link == Link.Ready || link == Link.Connecting || (!stale && rssi != Int.MIN_VALUE)

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

    // ------------------------------------------------------- синхронизация слайдшоу
    //
    // Два слоя, один поверх другого.
    //
    // ЖЁСТКИЙ (основной, пока есть кому его вести). Колёса не умеют говорить
    // друг с другом напрямую, поэтому единственные часы — телефон: корутина
    // (`group.job`, см. startGroupTicker) раз в интервал шлёт OP_SYNC_TICK
    // (см. BleClient.syncTick — «называет» файл/эффект по ИМЕНИ, а не по
    // индексу в чьём-то списке) на ВСЕ адреса группы сразу. Живёт ровно пока
    // жив ведущий: пока открыт экран — это ViewModel, на её собственных
    // соединениях (участники обязаны быть Ready, иначе их и в партнёры не
    // выбрать — см. otherReady на экране, — так что соединение есть железно);
    // как только Activity закрывают (WheelVm.onCleared()), тикер передаётся в
    // SyncSlideshowService — она подключается к участникам С НУЛЯ и продолжает
    // слать те же команды своими соединениями, специально ПОСЛЕ того, как
    // ViewModel уже отпустила свои (см. её комментарий): второе, параллельное
    // соединение к ещё занятому адресу — вещь ненадёжная (Bluetooth не держит
    // два по-настоящему разных ACL-канала к одному устройству с одного
    // телефона), и именно так выглядела реальная поломка «показ не
    // запускается вовсе». Если экран открывают заново, пока служба уже ведёт
    // показ сама, restoreSyncStateIfNeeded просит её вернуть тикер обратно
    // сюда — по той же причине: держать оба соединения разом нельзя.
    //
    // МЯГКИЙ (запасной, переживает и телефон, и саму службу). При старте
    // armGroupFallback() вооружает на КАЖДОМ колесе его же штатное
    // `OP_ALBUM`-слайдшоу с тем же отбором и интервалом — и колесо готово
    // считать само, автономно, глубоким сном включительно. Раньше это было
    // ЕДИНСТВЕННЫМ механизмом (ради того, чтобы показ переживал смерть
    // сервиса), и это ломало синхронизацию по-другому и хуже: порядок файлов
    // в OP_ALBUM строился из savedFiles — сырого порядка обхода LittleFS на
    // КАЖДОМ колесе (updateFileList() в прошивке), ничем не гарантированно
    // совпадающего между независимо прошитыми/залитыми устройствами; один и
    // тот же ОТБОР превращался в РАЗНЫЙ порядок показа — колёса показывали
    // разное с первого же переключения, а не расходились со временем. Это
    // чинится на стороне прошивки (advanceSlideshow() берёт файлы отбора в
    // порядке САМОГО присланного списка, не savedFiles — см. main.cpp), но
    // сама мягкая синхронизация остаётся мягкой: без телефона, поминутно
    // поправляющего каждое колесо, они могут медленно разойтись по фазе от
    // разницы хода часов — секунды в час, не больше, и только пока телефон
    // (или служба) не вернутся. OP_SYNC_TICK специально НЕ трогает
    // slideshowActive (в отличие от OP_PLAY/OP_EFFECT) — жёсткий слой лишь
    // ПОДПРАВЛЯЕТ мягкий на ходу (позицию и время последней смены, см.
    // syncTick() в main.cpp), а не подменяет его, так что рассылка каждого
    // тика не разоружает страховку, ради которой она и существует.
    //
    // Группа — два и больше колёс: с двумя устройствами и не разгонишься
    // дальше пары, но у велосипеда бывает и третье (запасное, прицеп) —
    // список, а не пара, ничего не усложняет.

    private class SyncGroup(val members: List<String>, var files: List<String>, var intervalMs: Int) {
        var index = -1
        var job: kotlinx.coroutines.Job? = null
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
     *  снимает всю группу, не только этот адрес, останавливает тикер (свой,
     *  если он ещё здесь, либо служебный — см. SyncSlideshowService.stop) и
     *  гасит уведомление. */
    private fun endSync(addr: String?) {
        val group = addr?.let { syncGroups[it] } ?: return
        group.job?.cancel()
        for (m in group.members) syncGroups.remove(m)
        syncPartners.value = syncPartners.value - group.members.toSet()
        // slideIntervalMs НЕ трогаем — см. комментарий у него: это последнее
        // использованное значение, оно должно пережить остановку показа.
        SyncSlideshowService.stop(ctx)
    }

    /** Токен эффекта («@e3») → его номер, иначе null (значит это имя файла). */
    private fun slideEffectId(token: String): Int? =
        if (isSlideEffect(token)) token.removePrefix("@e").toIntOrNull() else null

    /** Тикер группы: раз в интервал шлёт OP_PLAY/OP_EFFECT на все адреса
     *  группы разом через ИХ СОБСТВЕННЫЕ, уже открытые соединения этой
     *  ViewModel — см. комментарий в начале секции про то, почему именно так,
     *  а не через OP_ALBUM на каждом колесе по отдельности. Молча пропускает
     *  временно не-Ready участников — они подхватят показ, как только
     *  переподключатся, следующим же тиком. */
    private fun startGroupTicker(group: SyncGroup) {
        group.job?.cancel()
        group.job = viewModelScope.launch {
            while (isActive) {
                if (group.files.isEmpty()) break
                group.index = (group.index + 1).let { if (it >= group.files.size) 0 else it }
                val name = group.files[group.index]
                val effId = slideEffectId(name)
                for (a in group.members) {
                    clients[a]?.takeIf { it.link.value == Link.Ready }?.let { c ->
                        // syncTick — не play()/effect(): те гасят автономный ход
                        // слайдшоу на колесе (см. комментарий в начале секции и
                        // syncTick() в прошивке), а он должен остаться вооружён
                        // на случай, если телефон пропадёт без предупреждения.
                        runCatching { if (effId != null) c.syncTick(null, effId) else c.syncTick(name) }
                    }
                }
                delay(group.intervalMs.toLong())
            }
        }
    }

    /**
     * Отражение группы синхронного показа для [addr] могло не пережить
     * закрытие приложения — сама ViewModel новая, `syncGroups` пуст. Источник
     * правды теперь не SharedPreferences, а сама [SyncSlideshowService]: пока
     * жив процесс (а именно это и означает «служба пережила закрытие
     * приложения»), она хранит состав/отбор/интервал последней активной
     * группы в памяти — надёжнее файла настроек, который остался бы верным и
     * тогда, когда службу давно убили вместе с процессом, и мы бы ожили показ
     * без единого способа проверить, что он ещё правда идёт (`OP_PLAY`, в
     * отличие от `OP_ALBUM`, не оставляет на устройстве никакого флага,
     * который телефон мог бы потом перепросить).
     *
     * Если служба что-то помнит для этого адреса — забираем тикер обратно
     * сюда: она отпускает СВОИ соединения (см. releaseTicking и комментарий в
     * начале секции про то, почему второе соединение к тому же адресу
     * ненадёжно), и как только это подтверждено, здесь заводится обычный
     * тикер на соединениях этой ViewModel; заодно подключаемся к остальным
     * участникам группы, которых сама эта ViewModel ещё не открывала.
     * Однократно на адрес за время жизни ViewModel — дальше состоянием
     * заведуют сами [startSyncedSlideshow]/[endSync].
     */
    private fun restoreSyncStateIfNeeded(addr: String) {
        if (!restoredSyncFor.add(addr)) return
        if (syncGroups.containsKey(addr)) return
        val tracked = SyncSlideshowService.trackedGroupFor(addr) ?: return
        val group = SyncGroup(tracked.members, tracked.files, tracked.intervalMs)
        for (m in tracked.members) syncGroups[m] = group
        syncPartners.value = syncPartners.value + tracked.members.associateWith { m -> tracked.members - m }
        slideIntervalMs.value = slideIntervalMs.value + tracked.members.associateWith { tracked.intervalMs }
        viewModelScope.launch {
            SyncSlideshowService.releaseTicking(ctx)
            withTimeoutOrNull(1500) { while (SyncSlideshowService.isDrivingAddress(addr)) delay(50) }
            startGroupTicker(group)
            for (m in tracked.members) {
                if (m != addr && clients[m] == null) {
                    connect(m, prefs.getString("name_" + m, "POV wheel")!!)
                }
            }
        }
    }

    /**
     * [addr] только что снова стал Ready посреди группы, у которой тикер уже
     * есть и продолжал идти по своим (телефонным) часам всё время, пока это
     * колесо было недостижимо — group.index уже указывает на правильную,
     * актуальную позицию, тикеру осталось только дождаться своего follow-up
     * `delay()`, а это до целого интервала показа. Досылаем ту же поправку
     * немедленно, не дожидаясь расписания — иначе накопленный за время
     * реального обрыва рассинхрон был бы виден ещё один полный интервал
     * после того, как связь уже восстановлена. У свежесозданной группы
     * (см. [restoreSyncStateIfNeeded]) index ещё −1 — там первый тик уже и
     * так уходит немедленно из [startGroupTicker], лишний здесь не нужен.
     */
    private fun nudgeGroupSync(addr: String) {
        val group = syncGroups[addr] ?: return
        if (group.index !in group.files.indices) return
        val c = clients[addr] ?: return
        val name = group.files[group.index]
        val effId = slideEffectId(name)
        viewModelScope.launch {
            runCatching { if (effId != null) c.syncTick(null, effId) else c.syncTick(name) }
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
     * токены эффектов `@eN`; телефон сам гонит их по кругу через явные
     * `play(name)`/`effect(id)` на все адреса разом (см. [startGroupTicker] и
     * комментарий в начале секции про то, почему не `OP_ALBUM` на каждом
     * колесе по отдельности).
     */
    fun startSyncedSlideshow(partners: Set<String>, delaySecs: Int, checked: Set<String>) {
        val addr = current.value ?: return
        val others = (partners - addr).toList()
        if (others.isEmpty()) return
        val list = checked.toList()
        if (list.isEmpty()) { say("Tick at least one shared animation"); return }
        val members = listOf(addr) + others
        endSync(addr); others.forEach { endSync(it) }
        val ms = (delaySecs * 1000).coerceIn(1000, 300000)
        val group = SyncGroup(members, list, ms)
        for (m in members) syncGroups[m] = group
        syncPartners.value = syncPartners.value + members.associateWith { m -> members - m }
        slideIntervalMs.value = slideIntervalMs.value + members.associateWith { ms }
        if (settingsLoaded.value) applyGroupSyncedFields(group, settings.value)
        armGroupFallback(members, list, ms)
        startGroupTicker(group)
        val names = members.map { m -> wheels.value.firstOrNull { it.address == m }?.name ?: m }
        SyncSlideshowService.track(ctx, members, names, list, ms)
        say("Synced slideshow started")
    }

    /** Остановить синхронное слайдшоу И погасить ленту на ВСЕХ колёсах группы —
     *  нажатие Stop не должно требовать повторного нажатия на каждом.
     *  [endSync] уже отменяет тикер (свой или служебный) и просит
     *  [SyncSlideshowService] разослать стоп; следующий цикл дублирует
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
    val logLines = MutableStateFlow<List<String>>(emptyList())
    val toast = MutableStateFlow<String?>(null)

    /** Замок «Magnet Position» — защита от случайной правды на ходу. Своё
     *  значение на колесо, в prefs (`maglock_<addr>`). */
    val magnetLocked = MutableStateFlow(false)
    private fun magnetLockKey(addr: String) = "maglock_" + addr
    fun setMagnetLocked(v: Boolean) {
        magnetLocked.value = v
        current.value?.let { prefs.edit().putBoolean(magnetLockKey(it), v).apply() }
    }

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
    private var btStateReceiver: BroadcastReceiver? = null

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
        registerBtStateReceiver()
    }

    /**
     * Выключение Bluetooth на телефоне роняет весь адаптер разом — это не то
     * же самое, что обрыв одного GATT-соединения, и `onConnectionStateChange`
     * в этом случае у многих версий Android/производителей вообще не
     * приходит: стеку, у которого только что выключили радио, уже некому
     * сказать «отключено». Без этой подписки клиент так и оставался бы
     * Ready навсегда — свой собственный тикер группы (см. startGroupTicker)
     * продолжал бы слать syncTick в мёртвую пустоту, и ни он, ни onLinkLost
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

    /** Открытый клиент, но только если он на связи — получатель всех команд. */
    private fun readyClient(): BleClient? =
        currentClient()?.takeIf { it.link.value == Link.Ready }

    /** Открытое сейчас колесо и правда на связи — экран у него есть смысл не отбирать. */
    private fun currentIsActive(): Boolean =
        current.value?.let { clients[it]?.link?.value == Link.Ready } == true

    /**
     * Открыть колесо по тапу в строке: если оно уже на связи — просто показать,
     * иначе показать «подключаемся» и запустить соединение.
     */
    fun openWheel(addr: String, name: String) {
        val c = clients[addr]
        if (c != null && c.link.value == Link.Ready) selectWheel(addr)
        else {
            current.value = addr
            prefs.edit().putString("last_wheel", addr).apply()
            connect(addr, name)
        }
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
        openWheel(addr, prefs.getString("name_" + addr, "POV wheel")!!)
        viewModelScope.launch {
            delay(3000)
            while (current.value == addr && clients[addr]?.link?.value != Link.Ready) {
                val alt = wheels.value.firstOrNull { it.reachable && it.address != addr }
                if (alt != null) { openWheel(alt.address, alt.name); return@launch }
                delay(1000)
            }
        }
    }

    /**
     * Свайп между колёсами: +1 — следующее, −1 — предыдущее, по кругу. Список —
     * достижимые колёса ([WheelEntry.reachable]) в том же порядке, что в строке.
     * Меньше двух — свайп ничего не делает.
     */
    fun cycleWheel(dir: Int) {
        val list = wheels.value.filter { it.reachable }
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

    /** Пользователь сам выбрал это колесо — вот теперь оно «известное». */
    private fun markKnown(addr: String) {
        val known = prefs.getStringSet("known", emptySet())!!.toMutableSet()
        if (known.add(addr)) prefs.edit().putStringSet("known", known).apply()
    }

    fun forget(addr: String) {
        disconnect(addr)
        val known = prefs.getStringSet("known", emptySet())!!.toMutableSet()
        known.remove(addr)
        val edit = prefs.edit().putStringSet("known", known).remove("name_" + addr)
        if (prefs.getString("last_wheel", null) == addr) edit.remove("last_wheel")
        edit.apply()
        nameCache.remove(addr)
        filesByAddr.remove(addr); fsInfoByAddr.remove(addr); settingsByAddr.remove(addr)
        prefs.edit().remove(magnetLockKey(addr)).apply()
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
            launch { c.link.collect { lk ->
                rebuildWheels()
                // Соседнее колесо вышло на связь — тихо прогреваем кэш, чтобы
                // свайп на него открывал библиотеку сразу, а не с задержкой.
                if (lk == Link.Ready) { prefetchWheel(addr); restoreSyncStateIfNeeded(addr); nudgeGroupSync(addr) }
                else prefetched.remove(addr)
            } }
            launch { c.tele.collect { rebuildWheels() } }
        }
        c.onLinkLost = { viewModelScope.launch { onLinkLost(addr) } }
        reconnectJobs.remove(addr)?.cancel()
        rebuildWheels()
        viewModelScope.launch {
            // SyncSlideshowService могла подключиться к этому же адресу сама,
            // пока экран был закрыт (см. комментарий в начале секции про
            // синхронизацию слайдшоу) — второе, параллельное соединение с
            // этого же телефона ненадёжно, поэтому просим её отпустить и
            // недолго ждём подтверждения, прежде чем пробовать сами.
            if (SyncSlideshowService.isDrivingAddress(addr)) {
                SyncSlideshowService.releaseTicking(ctx)
                withTimeoutOrNull(1500) { while (SyncSlideshowService.isDrivingAddress(addr)) delay(50) }
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
            if (addr == current.value || !currentIsActive()) {
                selectWheel(addr)
                refreshAll()
                startPolling()
            }
            // Часы переживают глубокий сон, но после полного обесточивания
            // взяться им неоткуда — любое соединение это повод их выставить,
            // даже фоновому колесу, которое сейчас не на экране.
            try {
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

        // Кэш — ТОЛЬКО для мгновенного показа; settingsLoaded держим false, пока
        // не придёт настоящее чтение с устройства (adoptSettings). Иначе первое
        // же касание ползунка/спиннера отправило бы на колесо кэш — а он мог
        // оказаться значениями по умолчанию (колесо в дефолтном состоянии, или
        // старый снимок), и настройки на устройстве затёрлись бы дефолтами.
        settings.value       = settingsByAddr[addr] ?: Settings()
        settingsLoaded.value = false
        files.value  = filesByAddr[addr] ?: emptyList()
        fsInfo.value = fsInfoByAddr[addr] ?: FsInfo()
        magnetLocked.value = prefs.getBoolean(magnetLockKey(addr), false)
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
            settingsLoaded.value = false
            // Именно текущее колесо и отвалилось — это тот случай, когда экран
            // ДОЛЖЕН уйти с него: правило «не отбирать экран у активного
            // колеса» (см. connect()) относится только к ещё живому текущему.
            // Переходим на любое другое доступное и остаёмся там — назад сюда
            // не утащит, пока это, отвалившееся, само не переподключится, пока
            // оно ещё текущее (см. проверку в connect()).
            val alt = wheels.value.firstOrNull { it.address != addr && it.link == Link.Ready }
                ?: wheels.value.firstOrNull { it.address != addr && it.reachable }
            if (alt != null) openWheel(alt.address, alt.name)
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

        // Адрес, а не сам объект: авто-переподключение создаёт НОВЫЙ BleClient,
        // и захваченная на всю пачку ссылка указывала бы на закрытый.
        val targetAddrs = listOfNotNull(readyClient()?.address)
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
                        // Второе подключённое колесо (если есть) — на «мягкий»
                        // линк: два активных BLE-соединения делят радио телефона,
                        // без этого заливка шла ~20 вместо ~110 кБ/с.
                        focusUploadLink(addr)
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
    fun album(start: Boolean, ms: Int) {
        if (start) endSync(current.value)
        onTargets { it.album(start, ms) }
    }

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

    /** Отбор, готовый уйти в `BleClient.album()`. [overflowed] — оба списка,
     *  include и exclude, длиннее, чем одна ATT-посылка выдержит (~20 имён);
     *  тогда шлём "все файлы" и вызывающий решает, стоит ли об этом сказать. */
    private data class AlbumSelection(
        val mode: Int, val names: List<String>, val effectMask: Int, val overflowed: Boolean
    )

    /** [checked] (имена файлов + токены эффектов `@eN`) → [AlbumSelection]:
     *  маска эффектов плюс include/exclude-список файлов, тот что короче. */
    private fun albumSelectionFor(checked: Set<String>, fileNames: List<String>): AlbumSelection {
        val effMask = slideEffectTokens.foldIndexed(0) { i, m, t -> if (t in checked) m or (1 shl i) else m }
        val incl = fileNames.filter { it in checked }
        val excl = fileNames.filter { it !in checked }
        return when {
            excl.isEmpty() -> AlbumSelection(0, emptyList(), effMask, false)   // exclude нечего = все файлы
            incl.size <= excl.size && incl.size <= 20 -> AlbumSelection(1, incl, effMask, false)
            excl.size <= 20 -> AlbumSelection(0, excl, effMask, false)
            else -> AlbumSelection(0, emptyList(), effMask, true)
        }
    }

    /**
     * Как [albumSelectionFor], но ВСЕГДА include, а не то из include/exclude,
     * что короче: синхронному показу порядок важнее размера ATT-посылки —
     * прошивка теперь берёт файлы отбора-include в порядке САМОГО присланного
     * списка (см. advanceSlideshow() в main.cpp), и это единственный способ
     * гарантировать, что автономный запасной ход слайдшоу (см. комментарий в
     * начале секции синхронизации) идёт в ОДНОМ порядке на всех колёсах
     * группы, а не в порядке, в котором каждое из них когда-то получило файлы.
     */
    private fun syncAlbumSelection(checked: Set<String>): AlbumSelection {
        val effMask = slideEffectTokens.foldIndexed(0) { i, m, t -> if (t in checked) m or (1 shl i) else m }
        val names = checked.filterNot { isSlideEffect(it) }
        return AlbumSelection(1, names, effMask, names.size > 20)
    }

    /**
     * Вооружить автономный запасной ход слайдшоу на каждом из [members] — тот
     * же `album(true, …)`, что и у обычного показа, но всегда через
     * [syncAlbumSelection]. Это НЕ основной механизм показа (им остаётся
     * тикер, см. [startGroupTicker]/`syncTick`), а страховка: если телефон
     * (или сама [SyncSlideshowService]) пропадёт совсем — без Bluetooth, без
     * предупреждения, — колесо не застынет на последнем кадре, а продолжит
     * крутить ЭТУ ЖЕ последовательность по собственным часам, слегка теряя
     * точную фазу со временем, но не содержимое.
     */
    private fun armGroupFallback(members: List<String>, files: List<String>, ms: Int) {
        val sel = syncAlbumSelection(files.toSet())
        for (m in members) {
            clients[m]?.takeIf { it.link.value == Link.Ready }?.let { c ->
                viewModelScope.launch { runCatching { c.album(true, ms, sel.mode, sel.names, sel.effectMask) } }
            }
        }
    }

    /** Запустить слайдшоу с отмеченным [checked] (имена файлов + токены эффектов). */
    fun startSlideshow(delaySecs: Int, checked: Set<String>, fileNames: List<String>) {
        if (checked.isEmpty()) { say("Tick at least one item"); return }
        endSync(current.value)   // это НЕ синхронное слайдшоу — снимаем прежнее, если было
        current.value?.let {
            prefs.edit().putString(slideSelKey(it), checked.joinToString(",")).apply()
        }
        val sel = albumSelectionFor(checked, fileNames)
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
     * Если колесо сейчас в группе — просто меняем `group.intervalMs`: тикер
     * (см. [startGroupTicker]) читает его заново на каждом обороте, слать
     * никому ничего отдельно не нужно. Разносим то же число по всем
     * участникам группы для экрана, с какого бы из них ни поменяли, и
     * обновляем копию в [SyncSlideshowService] на случай будущей передачи
     * тикера ей (см. комментарий в начале секции). Если обычный (не
     * синхронный) показ уже идёт на устройстве — оно подхватит новый
     * интервал на лету через `OP_ALBUM`, как и раньше.
     */
    fun setSlideInterval(secs: Int) {
        val addr = current.value ?: return
        val ms = (secs * 1000).coerceIn(1000, 300000)
        val group = syncGroups[addr]
        if (group != null) {
            // Тикер (см. startGroupTicker) читает group.intervalMs заново на
            // каждом обороте — рассылать для него отдельно нечего. А вот
            // вооружённый на каждом колесе запасной ход (см. armGroupFallback)
            // хранит интервал у СЕБЯ, в NVS устройства, — его нужно поправить
            // отдельно, иначе после исчезновения телефона колесо продолжит
            // считать по старому, забытому значению.
            group.intervalMs = ms
            slideIntervalMs.value = slideIntervalMs.value + group.members.associateWith { ms }
            SyncSlideshowService.updateConfig(group.files, ms)
            for (m in group.members) {
                clients[m]?.takeIf { it.link.value == Link.Ready }?.let { c ->
                    viewModelScope.launch { runCatching { c.album(true, ms) } }
                }
            }
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
    fun wifi(on: Boolean) = onTargets { it.wifi(on) }

    /**
     * Выключение в транспортный режим: колесо гаснет и до удержания кнопки уже
     * не проснётся — ни по тряске, ни по BLE.
     */
    fun powerOff() = onTargets { it.powerOff() }

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
                    // Группа продолжает идти без удалённых файлов — тикеру
                    // (см. startGroupTicker) отдельно рассылать нечего, он
                    // читает group.files заново на каждом обороте. А вот
                    // вооружённый на каждом колесе запасной ход (см.
                    // armGroupFallback) держит свой отбор в NVS устройства —
                    // без обновления он бы ещё пытался сыграть то, чего
                    // больше нет, если телефон вдруг пропадёт. Служебной
                    // копии конфигурации (на случай передачи тикера в
                    // SyncSlideshowService) тоже сообщаем.
                    if (group.index >= group.files.size) group.index = -1
                    SyncSlideshowService.updateConfig(group.files, group.intervalMs)
                    armGroupFallback(group.members, group.files, group.intervalMs)
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
        btStateReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        btStateReceiver = null
        stopScan()
        handOffActiveSyncGroups()
        disconnectAll()
        synchronized(clipMem) { clipMem.clear() }
        super.onCleared()
    }

    /**
     * Приложение закрывается — соединений (и тикеров) у этой ViewModel сейчас
     * не станет. Каждую ещё активную группу синхронного показа передаём
     * [SyncSlideshowService]: она подключится к участникам с нуля и продолжит
     * слать те же команды сама (см. комментарий в начале секции про
     * синхронизацию слайдшоу). Вызывается ДО [disconnectAll] — а сама служба
     * всё равно выжидает короткую паузу перед тем как подключаться, так что
     * порядок здесь не критичен, лишь бы оба шага произошли.
     */
    private fun handOffActiveSyncGroups() {
        val seen = HashSet<SyncGroup>()
        for (group in syncGroups.values) {
            if (!seen.add(group)) continue
            group.job?.cancel()
            val names = group.members.map { m -> wheels.value.firstOrNull { it.address == m }?.name ?: m }
            SyncSlideshowService.track(ctx, group.members, names, group.files, group.intervalMs)
            SyncSlideshowService.takeOverTicking(ctx)
        }
    }
}
