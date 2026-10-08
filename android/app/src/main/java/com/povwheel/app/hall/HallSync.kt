package com.povwheel.app.hall

import android.content.Context
import android.os.SystemClock
import com.povwheel.app.ble.BleClient
import com.povwheel.app.ble.BleException
import com.povwheel.app.ble.Link
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max

/**
 * Фоновый сбор лога Холла с одного подключённого колеса в [HallArchive].
 *
 * Запускается самим [BleClient] после рукопожатия — неважно, кто держит соединение
 * (экран, служба фонового подключения, синхронный показ): архив должен быть полным
 * всегда, когда колесо крутится рядом с телефоном. Всё это — фоновый обмен: колесо
 * не считает его активностью и засыпает по простою как обычно.
 *
 * Что делает:
 *  - пингует часы (OP_TIME) — сразу серией, дальше раз в полминуты; по лучшим пингам
 *    архив переводит время событий в часы телефона;
 *  - при первом контакте измеряет ошибку часов колеса (для сессий, проезженных без
 *    телефона, см. [HallArchive.noteGen]) и выставляет их точно (OP_TIME_SET);
 *  - дальше, раз в полминуты, подправляет их, если ушли больше чем на
 *    [RESYNC_TOL_US]: по ним идёт синхронный показ нескольких колёс;
 *  - забирает новые записи кольца (OP_HALL_LOG): раз в 3 с, пока лента светится,
 *    раз в 10 с — пока нет;
 *  - пока лента НЕ светится, дочитывает файлы истории с флеша колеса (OP_HALL_HIST) —
 *    там то, что колесо записало без телефона.
 */
class HallSync(private val ctx: Context, private val c: BleClient) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        HallArchive.init(ctx)
        scope.launch {
            try { loop() } catch (_: Exception) { }
        }
    }

    fun stop() = scope.cancel()

    // Фоновый виток и внеочередной [pullNow] не должны тянуть кольцо одновременно:
    // оба спросили бы с одного и того же места и дважды прогнали одни записи.
    private val pullLock = Mutex()

    /**
     * Забрать новые записи кольца сейчас же — для «Render POV Video»: ролик только
     * что сняли, а фоновый виток доберёт хвост лишь через несколько секунд. Кольцо
     * в PSRAM читается и пока лента светится (в отличие от истории на флеше).
     */
    suspend fun pullNow() {
        if (c.link.value != Link.Ready) return
        // Только что подключились — сначала первая серия пингов: без неё записи
        // этой сессии не к чему привязать по времени телефона.
        withTimeoutOrNull(6_000) { primed.await() }
        runCatching { pullLock.withLock { pullLog(c.address) } }
    }

    /** Первая серия пингов часов прошла (см. [loop]). */
    private val primed = CompletableDeferred<Unit>()

    /** Часы колеса выставлены после подключения (или оказались верными и так). */
    private val clockReady = CompletableDeferred<Unit>()

    suspend fun awaitClock() = clockReady.await()

    private suspend fun pings(n: Int): List<BleClient.TimeSample> {
        val out = ArrayList<BleClient.TimeSample>(n)
        for (i in 0 until n) {
            if (c.link.value != Link.Ready) break
            runCatching { c.timePing() }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private suspend fun loop() {
        val addr = c.address
        HallArchive.noteWheel(addr, c.hello?.name ?: "")
        var first = true
        var lastPing = 0L
        var lastHist = 0L
        var pwr = 0
        while (c.link.value == Link.Ready) {
            val now = SystemClock.elapsedRealtime()
            if (first || now - lastPing > 30_000) {
                val ss = pings(if (first) 16 else 6)
                if (ss.isNotEmpty()) {
                    lastPing = now
                    val best = ss.minBy { it.rttUs }
                    val ti = best.info
                    pwr = ti.pwr
                    HallArchive.addPings(addr, ti.bootId,
                        ss.map { HallArchive.Ping(it.info.espUs, it.wallMidUs, it.rttUs) },
                        HallArchive.Ref(ti.espUs, ti.wallUs, ti.sleepUs, ti.clockGen, ti.precise))
                    // Ошибка часов колеса сейчас: метки часов и esp_timer колесо снимает
                    // вместе, телефон относит их к середине круга.
                    val err = if (ti.wallUs != 0L) ti.wallUs - best.wallMidUs else Long.MAX_VALUE
                    if (ti.wallUs != 0L) HallArchive.noteGen(addr, ti.clockGen, err, ti.sleepUs, ti.precise)
                    if (first) {
                        first = false
                        primed.complete(Unit)
                        val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
                        val tol = max(2_000L, best.rttUs / 2)
                        if (ti.wallUs == 0L || !ti.precise || abs(err) > tol) {
                            // Новое поколение часов начинается с ошибкой ≈ 0 — с этого
                            // момента дрейф во сне раскладывается по проспанному.
                            runCatching { c.timeSet(best.wallMidUs, ti.espUs, tz) }
                        } else {
                            // Часы точные — только пояс (колесо не сдвинет их ради
                            // секундной точности старой команды).
                            runCatching { c.setTime(System.currentTimeMillis() / 1000, tz) }
                        }
                        clockReady.complete(Unit)
                    } else if (ti.wallUs == 0L || !ti.precise || abs(err) > RESYNC_TOL_US) {
                        // Кварц колеса и телефона расходятся на десятки ppm — за час
                        // это до сотни мс, а синхронный показ по часам ждёт от колёс
                        // группы единиц мс. Подправляем, когда ушло заметно больше
                        // шума измерения (асимметрия круга BLE — единицы мс).
                        val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
                        runCatching { c.timeSet(best.wallMidUs, ti.espUs, tz) }
                    }
                }
            }

            runCatching { pullLock.withLock { pullLog(addr) } }

            if (pwr != 2 && now - lastHist > 60_000) {
                // Сбой разбора истории не должен останавливать весь сбор: раньше такое
                // исключение выходило из loop(), и до переподключения лог не шёл вовсе.
                // Повтор — через минуту, как после удачного прохода.
                if (runCatching { pullHist(addr) }.getOrDefault(true)) lastHist = now
            }
            // pwr обновляется и по странице: лента могла зажечься между пингами.
            delay(if (pwr == 2) 3_000 else 10_000)
            if (c.link.value == Link.Ready) runCatching { pwr = c.tele.value.pwr }
        }
    }

    private companion object {
        /** Порог подстройки часов колеса на ходу (см. [loop]), мкс. */
        const val RESYNC_TOL_US = 10_000L
    }

    private suspend fun pullLog(addr: String) {
        var boot = -1L
        for (guard in 0 until 256) {
            if (c.link.value != Link.Ready) return
            val from = if (boot >= 0) HallArchive.nextSeq(addr, boot) else -1L
            // Первый запрос — без номера: какой сессии он принадлежит, мы узнаем только
            // из ответа. Спросим с конца известного, если сессия та же.
            val pg = c.hallLog(if (from >= 0) from else 0x7FFFFFFFL, 4096)
            if (boot < 0) {
                boot = pg.bootId
                val want = HallArchive.nextSeq(addr, boot)
                if (want < pg.head) continue          // переспросим с нужного места
                return
            }
            if (pg.entries.isEmpty()) return
            HallArchive.addEntries(addr, pg.bootId, pg.seq0, pg.t0, pg.entries, pg.entries.size,
                pg.calX100, pg.armReverse)
            if (pg.seq0 + pg.entries.size >= pg.head) return
        }
    }

    /** false — колесо занято (лента светится), попробуем позже. */
    private suspend fun pullHist(addr: String): Boolean {
        for (which in 0..1) {
            for (guard in 0 until 64) {
                if (c.link.value != Link.Ready) return false
                val pos = HallArchive.histPos(addr, which)
                val h = try {
                    c.hallHist(which, pos.read, 32 * 1024)
                } catch (e: BleException) {
                    return false
                }
                if (h.size == 0L) break                               // файла нет
                val sameFile = h.keyBoot == pos.keyBoot && h.keySeq == pos.keySeq
                HallArchive.addHist(addr, which, h.keyBoot, h.keySeq, h.off, h.bytes)
                if (!sameFile) continue                              // файл сменился — с начала
                if (h.bytes.isEmpty() || h.off + h.bytes.size >= h.size) break
            }
        }
        return true
    }
}
