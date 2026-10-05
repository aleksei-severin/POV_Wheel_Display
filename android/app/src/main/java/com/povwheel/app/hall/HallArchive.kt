package com.povwheel.app.hall

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Архив лога событий Холла на телефоне — источник синхронизации для «Render POV Video».
 *
 * Колесо помнит события ограниченно (полмегабайта PSRAM и пара файлов на флеше, см.
 * include/hall_log.h), а ролик могут склеивать через неделю. Поэтому всё, что приходит
 * с колеса, складывается сюда и не удаляется: минута езды при 300 об/мин — это около
 * 30 кБ, час — меньше двух мегабайт.
 *
 * Раскладка: `files/hall/<адрес колеса>/`
 *  - `wheel.json` — имя, ошибки часов колеса по «поколениям» установки и позиции
 *    чтения файлов истории;
 *  - `s_<сессия>.ev` — события сессии (одна загрузка колеса), записи по 16 байт
 *    [i64 esp_us][i32 seq][u8 тип][u8 аргумент][u16 0], в порядке поступления;
 *  - `s_<сессия>.json` — калибровка датчиков, пинги часов, опорные пары часов колеса и
 *    уже покрытые диапазоны номеров записей (по ним отсекаются повторы).
 *
 * ВРЕМЯ. События помечены esp_timer колеса — кварц, но с нулём в момент загрузки. На
 * часы телефона (мкс UTC) их переводит [ClockMap]:
 *  - если в сессии были пинги OP_TIME — прямая по лучшим из них (минимальный круг
 *    запрос-ответ), точность — половина этого круга, единицы миллисекунд;
 *  - если колесо ездило без телефона — по опорной паре «esp_timer ↔ часы колеса» из
 *    блока на флеше. Часы колеса во сне идут от RC-генератора и уплывают; ошибку,
 *    накопленную к следующему подключению, телефон измеряет и раскладывает
 *    пропорционально проспанному (см. [noteGen]).
 */
object HallArchive {

    /** Событие лога: тип как в прошивке (HLOG_*). */
    const val T_HALL = HallDecode.T_HALL
    const val T_LIT = HallDecode.T_LIT
    const val T_DARK = HallDecode.T_DARK

    /** Размер заголовка блока на флеше (HallBlockHdr). */
    const val BLOCK_HDR = 68
    private const val BLOCK_MAGIC = 0x31424C48

    private lateinit var root: File

    fun init(ctx: Context) {
        if (!::root.isInitialized) root = File(ctx.filesDir, "hall").apply { mkdirs() }
    }

    private fun wheelDir(addr: String): File =
        File(root, addr.replace(":", "")).apply { mkdirs() }

    private fun hex(boot: Long) = String.format("%08x", boot and 0xFFFFFFFFL)

    // ------------------------------------------------------------------ запись

    /** Пинг часов: esp_timer колеса против часов телефона. */
    class Ping(val espUs: Long, val wallUs: Long, val rttUs: Long)

    /** Опорная пара часов колеса (из блока на флеше или из пинга). */
    class Ref(val espUs: Long, val wheelWallUs: Long, val sleepUs: Long, val gen: Long, val precise: Boolean)

    @Synchronized
    fun noteWheel(addr: String, name: String) {
        val w = loadWheel(addr)
        if (w.optString("name") != name && name.isNotEmpty()) {
            w.put("name", name)
            saveWheel(addr, w)
        }
    }

    /**
     * Ошибка часов колеса в поколении установки [gen]: [errUs] = часы колеса − истинное
     * время, измерено при [sleepUs] проспанного с установки. Позднее измерение того же
     * поколения точнее раннего (сна больше) — его и храним.
     */
    @Synchronized
    fun noteGen(addr: String, gen: Long, errUs: Long, sleepUs: Long, precise: Boolean) {
        val w = loadWheel(addr)
        val gens = w.optJSONObject("gens") ?: JSONObject().also { w.put("gens", it) }
        val k = gen.toString()
        val old = gens.optJSONObject(k)
        if (old != null && old.optLong("sleep") > sleepUs) return
        gens.put(k, JSONObject().put("err", errUs).put("sleep", sleepUs).put("prec", precise))
        saveWheel(addr, w)
    }

    @Synchronized
    fun addPings(addr: String, boot: Long, pings: List<Ping>, ref: Ref?) {
        if (pings.isEmpty() && ref == null) return
        val s = loadSession(addr, boot)
        val arr = s.optJSONArray("pings") ?: JSONArray().also { s.put("pings", it) }
        for (p in pings) arr.put(JSONArray().put(p.espUs).put(p.wallUs).put(p.rttUs))
        // Не даём списку расти без конца: оставляем по лучшему пингу на каждую минуту
        // часов колеса — прямой этого хватает с запасом.
        if (arr.length() > 4000) s.put("pings", thinPings(arr))
        if (ref != null) addRef(s, ref)
        saveSession(addr, boot, s)
    }

    private fun thinPings(arr: JSONArray): JSONArray {
        val best = HashMap<Long, JSONArray>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONArray(i)
            val k = p.getLong(0) / 60_000_000L
            val cur = best[k]
            if (cur == null || p.getLong(2) < cur.getLong(2)) best[k] = p
        }
        val out = JSONArray()
        best.keys.sorted().forEach { out.put(best[it]) }
        return out
    }

    private fun addRef(s: JSONObject, r: Ref) {
        if (r.wheelWallUs == 0L) return
        val arr = s.optJSONArray("refs") ?: JSONArray().also { s.put("refs", it) }
        arr.put(JSONArray().put(r.espUs).put(r.wheelWallUs).put(r.sleepUs).put(r.gen).put(if (r.precise) 1 else 0))
        if (arr.length() > 200) {
            val keep = JSONArray()
            for (i in arr.length() - 200 until arr.length()) keep.put(arr.get(i))
            s.put("refs", keep)
        }
    }

    /** Номер, с которого телефону нужна следующая страница кольца этой сессии. */
    @Synchronized
    fun nextSeq(addr: String, boot: Long): Long {
        val s = loadSession(addr, boot)
        val r = ranges(s)
        // Конец непрерывного куска, начатого с самого свежего диапазона: пропуски
        // до него (кольцо успело перезаписаться) дотягивать с колеса уже нечем.
        return if (r.isEmpty()) 0L else r.last()[1]
    }

    /**
     * Записи кольца, начиная с номера [seq0]; [t0] — время esp_timer до первой из них.
     * Повторы (уже покрытые номера) отбрасываются.
     */
    @Synchronized
    fun addEntries(
        addr: String, boot: Long, seq0: Long, t0: Long, entries: IntArray, count: Int,
        cal: IntArray?, armReverse: Boolean?
    ): Int {
        if (count <= 0) return 0
        val s = loadSession(addr, boot)
        if (cal != null) s.put("cal", JSONArray().apply { cal.forEach { put(it) } })
        if (armReverse != null) s.put("rev", armReverse)
        val have = ranges(s)
        val buf = ByteBuffer.allocate(count * 16).order(ByteOrder.LITTLE_ENDIAN)
        var added = 0
        var minEsp = Long.MAX_VALUE
        var maxEsp = Long.MIN_VALUE
        HallDecode.decode(seq0, t0, entries, count) { seq, t, type, arg ->
            if (!covered(have, seq)) {
                buf.putLong(t).putInt(seq.toInt()).put(type.toByte()).put(arg.toByte()).putShort(0)
                added++
                minEsp = min(minEsp, t); maxEsp = max(maxEsp, t)
            }
        }
        if (added > 0) {
            FileOutputStream(File(wheelDir(addr), "s_" + hex(boot) + ".ev"), true).use {
                it.write(buf.array(), 0, added * 16)
            }
            s.put("espLo", min(s.optLong("espLo", Long.MAX_VALUE), minEsp))
            s.put("espHi", max(s.optLong("espHi", Long.MIN_VALUE), maxEsp))
        }
        addRange(s, seq0, seq0 + count)
        saveSession(addr, boot, s)
        return added
    }

    /** Позиция чтения файла истории [which] (0 — .old, 1 — .log) на колесе. */
    class HistPos(val keyBoot: Long, val keySeq: Long, val read: Long)

    @Synchronized
    fun histPos(addr: String, which: Int): HistPos {
        val h = loadWheel(addr).optJSONObject("hist")?.optJSONObject(which.toString())
            ?: return HistPos(-1, -1, 0)
        return HistPos(h.optLong("kb", -1), h.optLong("ks", -1), h.optLong("read", 0))
    }

    /** Локальная копия файла истории колеса — блоки разбираются, когда дочитаны целиком. */
    private fun histCopy(addr: String, which: Int) = File(wheelDir(addr), "hist$which.bin")

    /**
     * Очередной кусок файла истории [which] с позиции [off]. Если файл на колесе сменился
     * (ротация — другой первый блок), копия начинается заново. Разбирает все целые блоки.
     */
    @Synchronized
    fun addHist(addr: String, which: Int, keyBoot: Long, keySeq: Long, off: Long, bytes: ByteArray) {
        val w = loadWheel(addr)
        val hist = w.optJSONObject("hist") ?: JSONObject().also { w.put("hist", it) }
        var h = hist.optJSONObject(which.toString())
        val f = histCopy(addr, which)
        if (h == null || h.optLong("kb") != keyBoot || h.optLong("ks") != keySeq) {
            h = JSONObject().put("kb", keyBoot).put("ks", keySeq).put("read", 0L).put("parsed", 0L)
            f.delete()
            hist.put(which.toString(), h)
            saveWheel(addr, w)
        }
        if (off != h.optLong("read")) return     // не то место — переспросим с нужного
        RandomAccessFile(f, "rw").use { raf -> raf.seek(off); raf.write(bytes) }
        h.put("read", off + bytes.size)
        // Разбор целых блоков с последней разобранной позиции.
        var parsed = h.optLong("parsed")
        val all = f.readBytes()
        while (parsed + BLOCK_HDR <= all.size) {
            val p = ByteBuffer.wrap(all, parsed.toInt(), all.size - parsed.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            if (p.int != BLOCK_MAGIC) { parsed = all.size.toLong(); break }   // испорчено — дальше не разобрать
            val boot = p.int.toLong() and 0xFFFFFFFFL
            val seq0 = p.int.toLong() and 0xFFFFFFFFL
            val n = p.int
            val t0 = p.long
            val refEsp = p.long
            val refWall = p.long
            val sleep = p.long
            val gen = p.int.toLong() and 0xFFFFFFFFL
            val cal = IntArray(6) { p.short.toInt() }
            val rev = (p.get().toInt() and 0xFF) != 0
            val fl = p.get().toInt() and 0xFF
            p.short
            if (parsed + BLOCK_HDR + n.toLong() * 4 > all.size) break           // блок ещё не дочитан
            val ent = IntArray(n) { p.int }
            // Опорная пара — до записей: addEntries сохраняет сессию, и пара должна в неё попасть.
            val s = loadSession(addr, boot)
            addRef(s, Ref(refEsp, refWall, sleep, gen, fl and 1 != 0))
            saveSession(addr, boot, s)
            addEntries(addr, boot, seq0, t0, ent, n, cal, rev)
            parsed += BLOCK_HDR + n.toLong() * 4
        }
        h.put("parsed", parsed)
        hist.put(which.toString(), h)
        saveWheel(addr, w)
    }

    // ------------------------------------------------------------------ чтение

    /**
     * Перевод esp_timer сессии в часы телефона: wall = [wall0] + [slope]·(esp − [esp0]).
     * [sigmaUs] — оценка погрешности (половина лучшего круга пинга либо неопределённость
     * часов колеса), по ней анализатор выбирает ширину поиска сдвига по видео.
     */
    class ClockMap(val esp0: Long, val wall0: Double, val slope: Double, val sigmaUs: Double, val how: String) {
        fun wall(esp: Long): Double = wall0 + slope * (esp - esp0)
        fun esp(wall: Double): Double = esp0 + (wall - wall0) / slope
    }

    class Session(
        val addr: String,
        val wheelName: String,
        val bootId: Long,
        val calX100: IntArray,
        val armReverse: Boolean,
        /** События по времени: esp_timer, тип, аргумент. */
        val esp: LongArray,
        val type: ByteArray,
        val arg: ByteArray,
        val map: ClockMap
    ) {
        val wallLo: Double get() = if (esp.isEmpty()) 0.0 else map.wall(esp.first())
        val wallHi: Double get() = if (esp.isEmpty()) 0.0 else map.wall(esp.last())
    }

    /** Сессии всех колёс, события которых попадают в [wallLoUs]…[wallHiUs] (с запасом [padUs]). */
    @Synchronized
    fun sessionsAround(wallLoUs: Double, wallHiUs: Double, padUs: Double): List<Session> {
        if (!::root.isInitialized) return emptyList()
        val out = ArrayList<Session>()
        for (wd in root.listFiles() ?: emptyArray()) {
            if (!wd.isDirectory) continue
            val addr = wd.name
            val w = loadWheelFile(wd)
            val name = w.optString("name", addr)
            for (sf in wd.listFiles() ?: emptyArray()) {
                val nm = sf.name
                if (!nm.startsWith("s_") || !nm.endsWith(".json")) continue
                val boot = nm.substring(2, nm.length - 5).toLongOrNull(16) ?: continue
                val s = runCatching { JSONObject(sf.readText()) }.getOrNull() ?: continue
                val lo = s.optLong("espLo", Long.MAX_VALUE)
                val hi = s.optLong("espHi", Long.MIN_VALUE)
                if (lo > hi) continue
                val map = clockMap(s, w) ?: continue
                val pad = padUs + map.sigmaUs * 3
                if (map.wall(hi) < wallLoUs - pad || map.wall(lo) > wallHiUs + pad) continue
                val ev = readEvents(File(wd, "s_" + hex(boot) + ".ev"))
                val cal = IntArray(6)
                s.optJSONArray("cal")?.let { a -> for (i in 0 until min(6, a.length())) cal[i] = a.getInt(i) }
                out.add(Session(addr, name, boot, cal, s.optBoolean("rev"), ev.first, ev.second, ev.third, map))
            }
        }
        return out
    }

    /** Все записи файла событий, по времени, без повторов номеров. */
    private fun readEvents(f: File): Triple<LongArray, ByteArray, ByteArray> {
        if (!f.exists()) return Triple(LongArray(0), ByteArray(0), ByteArray(0))
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.remaining() / 16
        val esp = LongArray(n); val seq = LongArray(n); val ty = ByteArray(n); val ar = ByteArray(n)
        for (i in 0 until n) {
            esp[i] = b.long
            seq[i] = b.int.toLong() and 0xFFFFFFFFL
            ty[i] = b.get(); ar[i] = b.get(); b.short
        }
        val idx = (0 until n).sortedWith(compareBy({ seq[it] }, { esp[it] }))
        val keep = ArrayList<Int>(n)
        var last = -1L
        for (i in idx) { if (seq[i] != last) keep.add(i); last = seq[i] }
        // Номера идут по времени, но после сортировки по номеру ещё раз — по времени
        // (страховка на случай перезапуска счётчика в одной сессии).
        keep.sortBy { esp[it] }
        return Triple(LongArray(keep.size) { esp[keep[it]] }, ByteArray(keep.size) { ty[keep[it]] },
            ByteArray(keep.size) { ar[keep[it]] })
    }

    /** Модель часов сессии. null — привязать к часам телефона нечем. */
    private fun clockMap(s: JSONObject, w: JSONObject): ClockMap? {
        val pings = s.optJSONArray("pings")
        if (pings != null && pings.length() > 0) {
            var minRtt = Long.MAX_VALUE
            for (i in 0 until pings.length()) minRtt = min(minRtt, pings.getJSONArray(i).getLong(2))
            // Лучшие пинги: круг не длиннее полутора лучших. По ним — прямая; наклон
            // — расхождение кварцев колеса и телефона, единицы-десятки ppm.
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (i in 0 until pings.length()) {
                val p = pings.getJSONArray(i)
                if (p.getLong(2) <= minRtt * 3 / 2 + 1500) { xs.add(p.getLong(0).toDouble()); ys.add(p.getLong(1).toDouble()) }
            }
            val e0 = xs.average()
            val w0 = ys.indices.sumOf { ys[it] - xs[it] } / ys.size + e0
            var slope = 1.0
            if (xs.size >= 2) {
                var sxx = 0.0; var sxy = 0.0
                for (i in xs.indices) { val dx = xs[i] - e0; sxx += dx * dx; sxy += dx * (ys[i] - w0) }
                // Наклон берём, только если пинги разнесены хотя бы на минуту и он похож
                // на расхождение кварцев (< 200 ppm); иначе это шум задержек BLE.
                if (sxx > 0 && (xs.max() - xs.min()) > 60e6) {
                    val k = sxy / sxx
                    if (abs(k - 1.0) < 200e-6) slope = k
                }
            }
            return ClockMap(e0.toLong(), w0, slope, max(1000.0, minRtt / 2.0), "BLE clock pings")
        }
        val refs = s.optJSONArray("refs") ?: return null
        if (refs.length() == 0) return null
        val r = refs.getJSONArray(refs.length() - 1)
        val esp = r.getLong(0); val wall = r.getLong(1); val sleep = r.getLong(2)
        val gen = r.getLong(3); val prec = r.getInt(4) != 0
        // Ошибка часов колеса в этом поколении — измерена при одном из следующих
        // подключений. Точная установка (ошибка в начале ≈ 0) — растёт со сном,
        // грубая — неизвестна с самого начала, считаем постоянной.
        val g = w.optJSONObject("gens")?.optJSONObject(gen.toString())
        var corr = 0.0
        var sigma = 30e6                          // ничего не знаем: ± полминуты
        if (g != null) {
            val err = g.getLong("err").toDouble()
            val gs = g.getLong("sleep").toDouble()
            if (prec && gs > 0) {
                val k = min(1.0, sleep / gs)
                corr = err * k
                sigma = max(50e3, abs(err) * 0.15 * k + 2e3)
            } else {
                corr = err
                sigma = max(50e3, abs(err) * 0.15)
            }
        } else if (prec && sleep == 0L) {
            sigma = 20e3                          // поставлены точно и с тех пор не спали
        }
        return ClockMap(esp, wall - corr, 1.0, sigma, if (g != null) "wheel clock, drift corrected" else "wheel clock")
    }

    // ------------------------------------------------------------------ служебное

    private fun ranges(s: JSONObject): MutableList<LongArray> {
        val a = s.optJSONArray("ranges") ?: return ArrayList()
        val out = ArrayList<LongArray>(a.length())
        for (i in 0 until a.length()) { val r = a.getJSONArray(i); out.add(longArrayOf(r.getLong(0), r.getLong(1))) }
        return out
    }

    private fun covered(r: List<LongArray>, seq: Long): Boolean {
        for (x in r) if (seq >= x[0] && seq < x[1]) return true
        return false
    }

    private fun addRange(s: JSONObject, lo: Long, hi: Long) {
        val r = ranges(s)
        r.add(longArrayOf(lo, hi))
        r.sortBy { it[0] }
        val m = ArrayList<LongArray>()
        for (x in r) {
            val last = m.lastOrNull()
            if (last != null && x[0] <= last[1]) last[1] = max(last[1], x[1]) else m.add(longArrayOf(x[0], x[1]))
        }
        s.put("ranges", JSONArray().apply { m.forEach { put(JSONArray().put(it[0]).put(it[1])) } })
    }

    private fun loadWheel(addr: String): JSONObject = loadWheelFile(wheelDir(addr))
    private fun loadWheelFile(dir: File): JSONObject {
        val f = File(dir, "wheel.json")
        return if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() } else JSONObject()
    }
    private fun saveWheel(addr: String, w: JSONObject) = writeAtomic(File(wheelDir(addr), "wheel.json"), w.toString())

    private fun loadSession(addr: String, boot: Long): JSONObject {
        val f = File(wheelDir(addr), "s_" + hex(boot) + ".json")
        return if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() } else JSONObject()
    }
    private fun saveSession(addr: String, boot: Long, s: JSONObject) =
        writeAtomic(File(wheelDir(addr), "s_" + hex(boot) + ".json"), s.toString())

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
