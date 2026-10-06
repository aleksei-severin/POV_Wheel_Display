package com.povwheel.app.hall

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
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
        val e = readEventsFull(f)
        return Triple(e.esp, e.type, e.arg)
    }

    /** События сессии вместе с номерами записей кольца. */
    private class Events(val esp: LongArray, val seq: LongArray, val type: ByteArray, val arg: ByteArray)

    private fun readEventsFull(f: File): Events {
        if (!f.exists()) return Events(LongArray(0), LongArray(0), ByteArray(0), ByteArray(0))
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
        return Events(LongArray(keep.size) { esp[keep[it]] }, LongArray(keep.size) { seq[keep[it]] },
            ByteArray(keep.size) { ty[keep[it]] }, ByteArray(keep.size) { ar[keep[it]] })
    }

    // ------------------------------------------------------------ выгрузка и загрузка
    //
    // Архив можно унести с телефона и принести обратно (или на другой телефон):
    // ZIP, в котором
    //  - README.txt — сводка для человека: по каждому дисплею его сессии с датами и
    //    временем, когда он светился (с оборотами), и пояснение к файлам;
    //  - <дисплей>/wheel.json — имя и измеренные ошибки часов колеса;
    //  - <дисплей>/<дата>_<время>_s<сессия>.csv — каждое событие строкой: местное
    //    время, esp_timer, номер записи, событие, аргумент — читается любой таблицей;
    //  - <дисплей>/<дата>_<время>_s<сессия>.json — пинги часов и прочее, без чего
    //    сессию не привязать ко времени телефона (нужно для обратной загрузки).
    // Загрузка СЛИВАЕТ такой архив с текущим: события добавляются по номерам записей
    // (уже имеющиеся не дублируются), пинги и опорные пары — объединяются; ничего из
    // того, что уже лежит на телефоне, не заменяется. Повторная загрузка того же
    // файла ничего не добавляет.

    class Stats(val wheels: Int, val sessions: Int, val bytes: Long, val firstUs: Double?, val lastUs: Double?)

    /** Что лежит в архиве — для окна выгрузки. */
    @Synchronized
    fun stats(): Stats {
        if (!::root.isInitialized) return Stats(0, 0, 0, null, null)
        var wheels = 0; var sessions = 0; var bytes = 0L
        var lo: Double? = null; var hi: Double? = null
        for (wd in root.listFiles() ?: emptyArray()) {
            if (!wd.isDirectory) continue
            val w = loadWheelFile(wd)
            var any = false
            for (sf in wd.listFiles() ?: emptyArray()) {
                val nm = sf.name
                if (!nm.startsWith("s_")) continue
                if (nm.endsWith(".ev")) { bytes += sf.length(); continue }
                if (!nm.endsWith(".json")) continue
                bytes += sf.length()
                val s = runCatching { JSONObject(sf.readText()) }.getOrNull() ?: continue
                val elo = s.optLong("espLo", Long.MAX_VALUE)
                val ehi = s.optLong("espHi", Long.MIN_VALUE)
                if (elo > ehi) continue
                any = true; sessions++
                clockMap(s, w)?.let { m ->
                    lo = min(lo ?: m.wall(elo), m.wall(elo))
                    hi = max(hi ?: m.wall(ehi), m.wall(ehi))
                }
            }
            if (any) wheels++
        }
        return Stats(wheels, sessions, bytes, lo, hi)
    }

    /** Снимок одной сессии для выгрузки — под замком, писать ZIP можно уже без него. */
    private class Snap(val boot: Long, val json: JSONObject, val map: ClockMap?, val ev: Events)

    @Synchronized
    private fun snap(wd: File, boot: Long): Snap? {
        val w = loadWheelFile(wd)
        val s = runCatching { JSONObject(File(wd, "s_" + hex(boot) + ".json").readText()) }.getOrNull() ?: return null
        val ev = readEventsFull(File(wd, "s_" + hex(boot) + ".ev"))
        if (ev.esp.isEmpty()) return null
        return Snap(boot, s, clockMap(s, w), ev)
    }

    class ExportResult(val wheels: Int, val sessions: Int, val events: Long)

    /** Весь архив — ZIP в [out] (закрывает его вызывающий). */
    fun export(out: java.io.OutputStream, progress: (String) -> Unit): ExportResult {
        if (!::root.isInitialized) throw IllegalStateException("archive is not ready")
        val zone = java.time.ZoneId.systemDefault()
        val zos = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out))
        val report = StringBuilder()
        var nWheels = 0; var nSessions = 0; var nEvents = 0L
        val wheelDirs = synchronized(this) { root.listFiles()?.filter { it.isDirectory } ?: emptyList() }
        for (wd in wheelDirs.sortedBy { loadWheelFile(it).optString("name", it.name).lowercase() }) {
            val w = synchronized(this) { loadWheelFile(wd) }
            val name = w.optString("name", "")
            val boots = (wd.listFiles() ?: emptyArray()).mapNotNull { f ->
                val nm = f.name
                if (nm.startsWith("s_") && nm.endsWith(".json")) nm.substring(2, nm.length - 5).toLongOrNull(16) else null
            }
            val snaps = boots.mapNotNull { snap(wd, it) }
                .sortedBy { it.map?.wall(it.ev.esp.first()) ?: Double.MAX_VALUE }
            if (snaps.isEmpty()) continue
            nWheels++
            val addr = prettyAddr(wd.name)
            val folder = safeName(if (name.isNotEmpty()) "$name ($addr)" else addr)
            putText(zos, "$folder/wheel.json", JSONObject()
                .put("dir", wd.name).put("addr", addr).put("name", name)
                .put("gens", w.optJSONObject("gens") ?: JSONObject()).toString(2))
            report.append('\n').append("=".repeat(64)).append('\n')
            report.append(if (name.isNotEmpty()) "$name   ($addr)" else addr).append('\n')
            report.append("=".repeat(64)).append('\n')
            report.append(snaps.size).append(if (snaps.size == 1) " session" else " sessions")
                .append(", folder \"").append(folder).append("\"\n")
            for (sn in snaps) {
                nSessions++
                nEvents += sn.ev.esp.size
                val base = "$folder/" + sessionBase(sn, zone)
                progress((if (name.isNotEmpty()) name else addr) + " — session " + hex(sn.boot))
                writeCsv(zos, "$base.csv", sn, name.ifEmpty { addr }, zone)
                putText(zos, "$base.json", JSONObject(sn.json.toString())
                    .put("boot", hex(sn.boot)).put("dir", wd.name).toString())
                report.append(sessionReport(sn, base.substringAfterLast('/'), zone))
            }
        }
        val head = StringBuilder()
        head.append("POV Wheel — Hall sensor log archive\n")
        head.append("Exported ").append(fmtFull(System.currentTimeMillis() * 1000.0, zone))
            .append(" (").append(zoneLabel(zone, System.currentTimeMillis() * 1000.0)).append(")\n")
        head.append(nWheels).append(if (nWheels == 1) " display, " else " displays, ")
            .append(nSessions).append(if (nSessions == 1) " session, " else " sessions, ")
            .append(String.format(Locale.US, "%,d", nEvents)).append(" events\n\n")
        head.append(README_HELP)
        putText(zos, "README.txt", head.toString() + report.toString())
        zos.finish()
        zos.flush()
        return ExportResult(nWheels, nSessions, nEvents)
    }

    private val README_HELP = """
        |What this is
        |  Each time a magnet passes one of a display's six Hall sensors (six times a
        |  revolution) the display logs the moment. The app keeps every such log it has
        |  ever downloaded and uses it to stitch "Render POV Video". Below, for each
        |  display: its sessions (one session = from power-up to the next deep sleep)
        |  and the periods when the image was actually on.
        |
        |Files
        |  <display>/<date>_<time>_s<session>.csv — one line per event, opens in any
        |      spreadsheet. Columns: time (local, phone clock), esp_us (the display's own
        |      microsecond timer since power-up), seq (record number in the display's
        |      log), event, arg.
        |      event: hall = a magnet passed Hall sensor <arg> (0…5);
        |             lit  = the image switched on (arg 1 = forward, 0 = reverse spin);
        |             dark = the image switched off.
        |  <display>/<…>.json — clock sync data for the same session; keep it next to
        |      the .csv, the app needs it to import the session back.
        |  <display>/wheel.json — the display's name and measured clock errors.
        |
        |Importing
        |  POV Wheel app → long press "Render POV Video" → Import. The archive is MERGED
        |  into the phone's own: new events are added, nothing already there is replaced,
        |  and importing the same file twice adds nothing.
        |""".trimMargin()

    /** Сводка одной сессии: время, привязка часов, когда светился дисплей. */
    private fun sessionReport(sn: Snap, base: String, zone: java.time.ZoneId): String {
        val ev = sn.ev
        val m = sn.map
        val t0 = ev.esp.first(); val t1 = ev.esp.last()
        val sb = StringBuilder("\n")
        val day0 = m?.let { dayOf(it.wall(t0), zone) }
        fun at(esp: Long): String =
            if (m == null) "+" + hms((esp - t0) / 1e6)
            else {
                val w = m.wall(esp)
                if (dayOf(w, zone) == day0) fmtTime(w, zone) else fmtFull(w, zone)
            }
        sb.append("Session ").append(hex(sn.boot)).append(" — ")
        if (m != null) sb.append(fmtFull(m.wall(t0), zone)).append(" … ").append(at(t1))
        else sb.append("date unknown")
        sb.append("   (").append(dur((t1 - t0) / 1e6)).append(")\n")
        sb.append("  Clock: ").append(
            if (m == null) "no reference — this session never reached a phone, times are counted from its first event"
            else m.how + String.format(Locale.US, ", ±%s", accuracy(m.sigmaUs))
        ).append('\n')
        sb.append("  File:  ").append(base).append(".csv\n")
        // Периоды «изображение горит»: от lit до dark. Обороты — по событиям Холла
        // внутри периода (все шесть датчиков на одну революцию).
        class Iv(val a: Long, var b: Long, var halls: Long, var on: Double, val reverse: Boolean,
                 val fromStart: Boolean, var toEnd: Boolean = false)
        val raw = ArrayList<Iv>()
        var litAt = -1L; var litRev = false; var hallsAtLit = 0L; var halls = 0L
        for (i in ev.esp.indices) {
            when (ev.type[i].toInt()) {
                T_HALL -> halls++
                T_LIT -> if (litAt < 0) { litAt = ev.esp[i]; litRev = ev.arg[i].toInt() == 0; hallsAtLit = halls }
                T_DARK -> {
                    if (litAt >= 0) raw.add(Iv(litAt, ev.esp[i], halls - hallsAtLit, (ev.esp[i] - litAt) / 1e6, litRev, false))
                    // Лог начинается посреди показа (кольцо на колесе успело переписаться).
                    else if (raw.isEmpty()) raw.add(Iv(t0, ev.esp[i], halls, (ev.esp[i] - t0) / 1e6, false, true))
                    litAt = -1
                }
            }
        }
        if (litAt >= 0) raw.add(Iv(litAt, t1, halls - hallsAtLit, (t1 - litAt) / 1e6, litRev, false, true))
        // Короткие погасания (смена файла в слайдшоу, перезапуск эффекта — доли
        // секунды) склеиваем: иначе час езды со слайдшоу дал бы сотни строк.
        val merged = ArrayList<Iv>()
        for (iv in raw) {
            val last = merged.lastOrNull()
            if (last != null && (iv.a - last.b) < 10_000_000L && iv.reverse == last.reverse) {
                last.b = iv.b; last.halls += iv.halls; last.on += iv.on; last.toEnd = iv.toEnd
            } else merged.add(iv)
        }
        if (merged.isEmpty()) {
            sb.append("  Display on: never (spun below the start speed, or only woke up)\n")
        } else {
            sb.append("  Display on:\n")
            for (iv in merged) {
                val rpm = if (iv.on >= 2) iv.halls / 6.0 / iv.on * 60 else 0.0
                sb.append(String.format(Locale.US, "    %s – %s   %-12s%s%s%s%s\n",
                    at(iv.a), at(iv.b), dur((iv.b - iv.a) / 1e6),
                    if (rpm > 0) String.format(Locale.US, "   ~%.0f rpm", rpm) else "",
                    if (iv.reverse) "   reverse spin" else "",
                    if (iv.fromStart) "   (log starts while on)" else "",
                    if (iv.toEnd) "   (log ends while on)" else ""))
            }
            sb.append("  On in total: ").append(dur(merged.sumOf { it.on }))
                .append(String.format(Locale.US, "   (%,d Hall events in the session)\n", halls))
        }
        return sb.toString()
    }

    private fun writeCsv(zos: java.util.zip.ZipOutputStream, path: String, sn: Snap, wheel: String, zone: java.time.ZoneId) {
        zos.putNextEntry(java.util.zip.ZipEntry(path))
        val ev = sn.ev
        val m = sn.map
        val sb = StringBuilder(1 shl 17)
        sb.append("# POV Wheel Hall log — ").append(wheel).append(", session ").append(hex(sn.boot)).append('\n')
        if (m != null) sb.append("# time: local time, ").append(zoneLabel(zone, m.wall(ev.esp.first())))
            .append(", from ").append(m.how).append(String.format(Locale.US, " (±%s)\n", accuracy(m.sigmaUs)))
        else sb.append("# time: unknown — this session never reached a phone (no clock reference)\n")
        sb.append("# event: hall = magnet passed Hall sensor <arg> (0..5); lit = image on (arg 1 forward, 0 reverse); dark = image off\n")
        sb.append("time,esp_us,seq,event,arg\n")
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        for (i in ev.esp.indices) {
            if (m != null) {
                val us = m.wall(ev.esp[i])
                sb.append(java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(Math.floor(us / 1000.0).toLong()), zone).format(fmt))
            }
            sb.append(',').append(ev.esp[i]).append(',').append(ev.seq[i]).append(',')
                .append(evName(ev.type[i].toInt())).append(',').append(ev.arg[i].toInt()).append('\n')
            if (sb.length > 60_000) { zos.write(sb.toString().toByteArray(Charsets.UTF_8)); sb.setLength(0) }
        }
        zos.write(sb.toString().toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun putText(zos: java.util.zip.ZipOutputStream, path: String, text: String) {
        zos.putNextEntry(java.util.zip.ZipEntry(path))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun evName(t: Int) = when (t) { T_HALL -> "hall"; T_LIT -> "lit"; T_DARK -> "dark"; else -> "t$t" }
    private fun evType(n: String) = when (n) { "hall" -> T_HALL; "lit" -> T_LIT; "dark" -> T_DARK; else -> -1 }

    /** «2026-10-05_14-02_s1a2b3c4d» — дата и время начала сессии, если известны. */
    private fun sessionBase(sn: Snap, zone: java.time.ZoneId): String {
        val m = sn.map ?: return "unknown-date_s" + hex(sn.boot)
        val t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli((m.wall(sn.ev.esp.first()) / 1000).toLong()), zone)
        return t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + "_s" + hex(sn.boot)
    }

    private fun dayOf(us: Double, zone: java.time.ZoneId) =
        java.time.Instant.ofEpochMilli((us / 1000).toLong()).atZone(zone).toLocalDate()
    private fun fmtFull(us: Double, zone: java.time.ZoneId): String =
        java.time.Instant.ofEpochMilli((us / 1000).toLong()).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    private fun fmtTime(us: Double, zone: java.time.ZoneId): String =
        java.time.Instant.ofEpochMilli((us / 1000).toLong()).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
    private fun zoneLabel(zone: java.time.ZoneId, us: Double): String {
        val off = zone.rules.getOffset(java.time.Instant.ofEpochMilli((us / 1000).toLong()))
        return "UTC" + (if (off.totalSeconds == 0) "" else off.id)
    }
    private fun hms(secs: Double): String {
        val s = secs.toLong().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }
    private fun dur(secs: Double): String {
        val s = Math.round(secs).coerceAtLeast(0)
        return when {
            s >= 3600 -> String.format(Locale.US, "%d h %02d min", s / 3600, s / 60 % 60)
            s >= 60 -> String.format(Locale.US, "%d min %02d s", s / 60, s % 60)
            else -> "$s s"
        }
    }
    private fun accuracy(us: Double): String =
        if (us < 1e6) String.format(Locale.US, "%.0f ms", us / 1000) else String.format(Locale.US, "%.0f s", us / 1e6)
    private fun prettyAddr(dir: String): String =
        if (dir.length == 12 && dir.all { it.isLetterOrDigit() }) dir.chunked(2).joinToString(":") else dir
    private fun safeName(s: String): String =
        s.map { if (it.isLetterOrDigit() || it in " ()-_.,") it else '_' }.joinToString("").trim()

    class ImportResult(val wheels: Int, val sessions: Int, val events: Long)

    /**
     * Слить ZIP из [input] (формат [export]) с архивом телефона. [tmp] — пустая
     * папка под распаковку, удаляется по окончании.
     */
    fun import(input: java.io.InputStream, tmp: File, progress: (String) -> Unit): ImportResult {
        if (!::root.isInitialized) throw IllegalStateException("archive is not ready")
        tmp.deleteRecursively(); tmp.mkdirs()
        try {
            progress("Unpacking…")
            val base = tmp.canonicalPath + File.separator
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(input)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val f = File(tmp, e.name)
                        // Имя из архива не должно вывести за пределы папки распаковки.
                        if (!f.canonicalPath.startsWith(base)) throw java.io.IOException("damaged archive")
                        f.parentFile?.mkdirs()
                        f.outputStream().use { zis.copyTo(it) }
                    }
                    e = zis.nextEntry
                }
            }
            val wheelFiles = tmp.walkTopDown().filter { it.isFile && it.name == "wheel.json" }.toList()
            if (wheelFiles.isEmpty()) throw java.io.IOException("this is not a POV Wheel log archive")
            var nWheels = 0; var nSessions = 0; var nEvents = 0L
            for (wf in wheelFiles) {
                val wj = runCatching { JSONObject(wf.readText()) }.getOrNull() ?: continue
                val dir = wj.optString("dir").ifEmpty {
                    Regex("([0-9A-Fa-f]{12})").findAll(wf.parentFile?.name ?: "").lastOrNull()?.value ?: ""
                }
                if (dir.isEmpty() || dir.any { !it.isLetterOrDigit() }) continue
                mergeWheel(dir, wj)
                nWheels++
                for (sf in wf.parentFile?.listFiles() ?: emptyArray()) {
                    if (!sf.name.endsWith(".json") || sf.name == "wheel.json") continue
                    val sj = runCatching { JSONObject(sf.readText()) }.getOrNull() ?: continue
                    val boot = sj.optString("boot").toLongOrNull(16) ?: continue
                    progress((wj.optString("name").ifEmpty { prettyAddr(dir) }) + " — session " + hex(boot))
                    val csv = File(sf.parentFile, sf.name.removeSuffix(".json") + ".csv")
                    val ev = if (csv.exists()) parseCsv(csv) else Events(LongArray(0), LongArray(0), ByteArray(0), ByteArray(0))
                    nEvents += mergeSession(dir, boot, sj, ev)
                    nSessions++
                }
            }
            return ImportResult(nWheels, nSessions, nEvents)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** CSV выгрузки → события. Время в первой колонке только для человека — берём esp_us. */
    private fun parseCsv(f: File): Events {
        var n = 0
        var esp = LongArray(4096); var seq = LongArray(4096)
        var ty = ByteArray(4096); var ar = ByteArray(4096)
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#' || line.startsWith("time,")) continue
                val c = line.split(',')
                if (c.size < 5) continue
                val t = evType(c[3].trim())
                val e = c[1].trim().toLongOrNull()
                val q = c[2].trim().toLongOrNull()
                val a = c[4].trim().toIntOrNull()
                if (t < 0 || e == null || q == null || a == null) continue
                if (n == esp.size) {
                    esp = esp.copyOf(n * 2); seq = seq.copyOf(n * 2)
                    ty = ty.copyOf(n * 2); ar = ar.copyOf(n * 2)
                }
                esp[n] = e; seq[n] = q; ty[n] = t.toByte(); ar[n] = a.toByte(); n++
            }
        }
        return Events(esp.copyOf(n), seq.copyOf(n), ty.copyOf(n), ar.copyOf(n))
    }

    /** Имя колеса — если своего нет; ошибки часов — по поколениям, точнее та, где сна больше. */
    @Synchronized
    private fun mergeWheel(dir: String, imp: JSONObject) {
        val w = loadWheel(dir)
        var changed = false
        val name = imp.optString("name")
        if (w.optString("name").isEmpty() && name.isNotEmpty()) { w.put("name", name); changed = true }
        val ig = imp.optJSONObject("gens")
        if (ig != null) {
            val gens = w.optJSONObject("gens") ?: JSONObject().also { w.put("gens", it) }
            for (k in ig.keys()) {
                val g = ig.optJSONObject(k) ?: continue
                val old = gens.optJSONObject(k)
                if (old == null || g.optLong("sleep") > old.optLong("sleep")) { gens.put(k, g); changed = true }
            }
        }
        if (changed) saveWheel(dir, w)
    }

    /** Слить сессию: события по номерам, которых ещё нет; пинги и опорные пары — объединением. */
    @Synchronized
    private fun mergeSession(dir: String, boot: Long, imp: JSONObject, ev: Events): Long {
        val s = loadSession(dir, boot)
        if (!s.has("cal")) imp.optJSONArray("cal")?.let { s.put("cal", it) }
        if (!s.has("rev") && imp.has("rev")) s.put("rev", imp.optBoolean("rev"))

        imp.optJSONArray("pings")?.let { ip ->
            val arr = s.optJSONArray("pings") ?: JSONArray().also { s.put("pings", it) }
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) arr.optJSONArray(i)?.let { seen.add(it.optLong(0).toString() + ":" + it.optLong(1)) }
            for (i in 0 until ip.length()) {
                val p = ip.optJSONArray(i) ?: continue
                if (seen.add(p.optLong(0).toString() + ":" + p.optLong(1))) arr.put(p)
            }
            if (arr.length() > 4000) s.put("pings", thinPings(arr))
        }
        imp.optJSONArray("refs")?.let { ir ->
            val all = ArrayList<JSONArray>()
            val seen = HashSet<String>()
            for (src in listOf(s.optJSONArray("refs") ?: JSONArray(), ir))
                for (i in 0 until src.length()) {
                    val r = src.optJSONArray(i) ?: continue
                    if (seen.add(r.optLong(0).toString() + ":" + r.optLong(1))) all.add(r)
                }
            // Модель часов берёт последнюю пару — после слияния это самая поздняя по esp.
            all.sortBy { it.optLong(0) }
            s.put("refs", JSONArray().apply { all.takeLast(200).forEach { put(it) } })
        }

        val have = ranges(s)
        val buf = ByteBuffer.allocate(ev.esp.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        var added = 0
        var minEsp = Long.MAX_VALUE; var maxEsp = Long.MIN_VALUE
        for (i in ev.esp.indices) {
            if (covered(have, ev.seq[i])) continue
            buf.putLong(ev.esp[i]).putInt(ev.seq[i].toInt()).put(ev.type[i]).put(ev.arg[i]).putShort(0)
            added++
            minEsp = min(minEsp, ev.esp[i]); maxEsp = max(maxEsp, ev.esp[i])
        }
        if (added > 0) {
            FileOutputStream(File(wheelDir(dir), "s_" + hex(boot) + ".ev"), true).use { it.write(buf.array(), 0, added * 16) }
            s.put("espLo", min(s.optLong("espLo", Long.MAX_VALUE), minEsp))
            s.put("espHi", max(s.optLong("espHi", Long.MIN_VALUE), maxEsp))
        }
        // Покрытые номера: из выгрузки, а нет их — по самим событиям.
        val ir = imp.optJSONArray("ranges")
        if (ir != null && ir.length() > 0) {
            for (i in 0 until ir.length()) ir.optJSONArray(i)?.let { addRange(s, it.optLong(0), it.optLong(1)) }
        } else if (ev.seq.isNotEmpty()) {
            val q = ev.seq.sorted()
            var lo = q[0]; var prev = q[0]
            for (x in q.drop(1)) { if (x > prev + 1) { addRange(s, lo, prev + 1); lo = x }; prev = x }
            addRange(s, lo, prev + 1)
        }
        saveSession(dir, boot, s)
        return added.toLong()
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
