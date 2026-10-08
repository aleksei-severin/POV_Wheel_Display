package com.povwheel.app.povvideo

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.povwheel.app.hall.HallArchive
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Участок отрисовки: моменты границ прорисовок (каждые 60° поворота ротора, с от первого
 * кадра файла) и признак «измерен» (у лога Холла — всегда да). [dir] — направление
 * вращения: +1 переднее колесо, −1 заднее или вращение назад.
 */
class TickTrack(val times: DoubleArray, val real: BooleanArray, val dir: Int = 1)

/**
 * Разметка видео на кадры результата. Элемент i плана занимает [counts] кадров исходника
 * подряд (столько времени он стоит на экране) и бывает трёх видов:
 *  - kind 0 — эти кадры идут как есть (колесо не рисует или лога на это время нет);
 *  - kind 1 — часть длинной прорисовки (нижний предел частоты кадров): склейка её кадров;
 *  - kind 2 — ровно одна прорисовка (1/6 оборота): склейка набора кадров, подобранного по
 *    фазе ротора так, чтобы их дуги выдержки закрыли все 60° (см. [PovSync.phaseSet]).
 * Набор кадров элемента i — [setIdx] с [setStart][i] до [setStart][i + 1] (номера кадров
 * исходника, по возрастанию); у kind 0 он пуст. Для каждого кадра набора — [setRing]
 * (удаление от середины прорисовки в прорисовках, 0 — своя) и [setPhase] (фаза ротора по
 * модулю 60°, градусы): по ним шейдер выбирает для каждой точки ближайший по времени кадр,
 * который её снимал. [near] — ближайший кадр элемента (для фона), [refDeg] — ширина окна
 * фаз, в котором ищутся кадры, снимавшие точку (см. PovGl.FS_BLEND).
 */
class PovPlan(
    val kinds: IntArray,
    val counts: IntArray,
    val setStart: IntArray,
    val setIdx: IntArray,
    val setRing: IntArray,
    val setPhase: FloatArray,
    val near: IntArray,
    val refDeg: Double
) {
    val totalFrames: Int = counts.sum()
    fun setOf(i: Int): IntArray = setIdx.copyOfRange(setStart[i], setStart[i + 1])
}

/** Всё, что нужно рендеру, плюс строки сводки для экрана. */
class PovAnalysis(
    val uri: Uri,
    val displayName: String,
    /** Папка исходника в галерее (MediaStore RELATIVE_PATH), если её удалось узнать. */
    val relativePath: String?,
    val codedW: Int,
    val codedH: Int,
    val rotation: Int,
    val fileFps: Double,
    val durationSec: Double,
    /** Битрейт видеодорожки исходника, бит/с (0 — неизвестен). От него считается битрейт результата. */
    val videoBps: Double,
    /** Кодек исходника (MIME). */
    val videoMime: String,
    /** Во сколько раз файл медленнее реального времени (1 — обычная съёмка). */
    val slow: Double,
    val tracks: List<TickTrack>,
    /** Метки времени кадров, мкс от первого кадра, по возрастанию. */
    val ptsUs: LongArray,
    /** Метка первого кадра по шкале контейнера, мкс. */
    val videoStartUs: Long,
    val plan: PovPlan,
    val sweeps: Int,
    /** Отрезки ролика (с файла), где есть синхронизация по логу Холла. */
    val syncRanges: List<DoubleArray>,
    val report: List<String>
) {
    val renderable: Boolean get() = tracks.isNotEmpty() && plan.totalFrames > 0
    val outName: String get() = displayName.substringBeforeLast('.') + "_sync_" + sweeps + "sweeps.mp4"
}

/** Звук ролика, декодированный в PCM. */
internal class DecodedAudio(
    val sampleRate: Int,
    val channels: Int,
    /** Время первого сэмпла, мкс по шкале контейнера. */
    val startUs: Long,
    /** −1..1, [detChannels] каналов вперемешку (не больше двух). */
    val det: FloatArray?,
    val detChannels: Int,
    /** Чередующиеся каналы, 16 бит — для звука результата. */
    val pcm: ShortArray?,
    val frames: Int
)

/**
 * Синхронизация ролика по логу Холла из архива телефона (см. HallArchive) и разметка
 * склейки.
 *
 * 1. Когда снят ролик — по метаданным: время создания в контейнере, DATE_TAKEN галереи,
 *    имя файла, время изменения. Каждый источник врёт по-своему (секунды, начало или
 *    конец записи, местное время или UTC), поэтому это не одно число, а набор кандидатов.
 * 2. Лог на это время — сессии колёс из архива; угол ротора по ним — [RotorModel].
 * 3. Точный сдвиг и замедление slow motion — по гашениям дисплея в кадре
 *    ([PovAlignCore.LitScorer]): у GoPro и части телефонов частоты съёмки в метаданных нет
 *    вовсе, а у Samsung подсказка есть, но прежде проигрывала гипотезе «×1» по покрытию
 *    логом. Двойников (слайдшоу повторяет файлы по кругу) и ролики без гашений решает
 *    пульсация общей яркости ([PovAlignCore.Coherence]). Без гашений и без изменения
 *    скорости остаётся привязка по метаданным — сдвиг тогда склейке и не важен.
 * 4. Тики — через весь прогон вращения: короткое гашение слайдшоу (загрузка файла) склейку
 *    не рвёт, прорисовки идут дальше, просто тёмные. Прежде на эти доли секунды шли
 *    исходные кадры с полной частотой, и смена картинок выбивалась из ролика.
 * 5. Склейка прорисовки — набор кадров по фазе ротора ([PovSync.phaseSet]): каждый кадр ловит лишь
 *    дугу выдержки (у GoPro — 10–15° из 60°), и кадров одной прорисовки на круг часто не
 *    хватает. Ближайшие по времени кадры берутся, пока их дуги не закроют круг, — из
 *    соседних прорисовок, если надо. Прежде шесть сдвинутых окон усреднялись: щели между
 *    клиньями становились полупрозрачными радиальными полосами, разными от кадра к кадру.
 */
internal object PovAnalyzer {

    private const val ARMS = 6
    /** Не больше стольких кадров читаем ради привязки (4K60 — полторы минуты). */
    private const val MAX_ALIGN_FRAMES = 6000

    class NoLog(message: String) : Exception(message)

    fun analyze(
        ctx: Context, uri: Uri,
        step: (String) -> Unit,
        cancelled: () -> Boolean
    ): PovAnalysis {
        step("Reading video…")
        HallArchive.init(ctx)
        val (name, relPath) = sourceInfo(ctx, uri)

        val mmr = MediaMetadataRetriever()
        var codedW = 0; var codedH = 0; var rotation = 0; var captureFps = 0.0; var fileBps = 0.0
        var dateStr: String? = null
        try {
            mmr.setDataSource(ctx, uri)
            codedW = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            codedH = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            captureFps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull() ?: 0.0
            fileBps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toDoubleOrNull() ?: 0.0
            dateStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        } finally { runCatching { mmr.release() } }
        rotation = ((rotation % 360) + 360) % 360

        // Метки кадров — из индекса контейнера, без декодирования.
        val vi = videoIndex(ctx, uri)
        val ptsAbs = vi.pts
        val videoStartUs = vi.startUs
        if (ptsAbs.size < 2) throw IllegalStateException("no video frames found")
        val pts = LongArray(ptsAbs.size) { ptsAbs[it] - videoStartUs }
        val nF = pts.size
        val avgDt = (pts.last() - pts.first()).toDouble() / (nF - 1) / 1e6
        val fileFps = 1.0 / avgDt
        val durationSec = pts.last() / 1e6 + avgDt
        val videoBps = if (vi.bytes > 0 && durationSec > 0) vi.bytes * 8.0 / durationSec else fileBps
        if (cancelled()) throw InterruptedException()

        // Замедление: подсказка метаданных (частота съёмки против частоты файла) — первой,
        // но проверяются все: у GoPro её нет вовсе. Метки кадров замедленного файла —
        // настоящие, умноженные на целое (проверено: ровно 4.000 и 8.000, не 4.03).
        val hint = if (captureFps > 0 && fileFps > 0) (captureFps / fileFps).roundToInt() else 0
        val slows = LinkedHashSet<Double>().apply { if (hint >= 2) add(hint.toDouble()); for (s in PovSync.SLOWS) add(s) }.toList()
        val prefSlow = if (hint >= 2) hint.toDouble() else 1.0

        // ---- когда снято ----
        step("Looking up the Hall log…")
        val ms = mediaStoreTimes(ctx, uri)
        val rep = ArrayList<String>()
        val dispW = if (rotation == 90 || rotation == 270) codedH else codedW
        val dispH = if (rotation == 90 || rotation == 270) codedW else codedH
        rep.add(fmt("Video: %d×%d, %s fps, %s s", dispW, dispH, num2(fileFps), num2(durationSec)) +
            if (videoBps > 0) ", " + num(Math.rint(videoBps / 1e5) / 10) + " Mbit/s" else "")
        if (captureFps > 0 && hint >= 2) rep.add(fmt("  metadata: captured at %s fps — slow motion ×%d?", num2(captureFps), hint))

        val cands = ArrayList<PovSync.Cand>()
        val srcCache = HashMap<Long, PovSync.Src?>()
        for (slow in slows) {
            val durReal = durationSec * 1e6 / slow
            val anchors = anchorsOf(name, dateStr, ms, durReal)
            if (anchors.isEmpty()) continue
            val lo = anchors.minOf { it.wallUs } - 60e6
            val hi = anchors.maxOf { it.wallUs } + durReal + 60e6
            for (s in HallArchive.sessionsAround(lo, hi, 0.0)) {
                val src = srcCache.getOrPut(s.bootId) { PovSync.srcOf(s) } ?: continue
                for (a in anchors) {
                    val pad = 3 * a.sigmaUs
                    val cov = src.covered(a.wallUs - pad, a.wallUs + durReal + pad)
                    if (cov > 0.5e6) cands.add(PovSync.Cand(a, slow, src, cov))
                }
            }
        }
        if (cands.isEmpty()) {
            val any = anchorsOf(name, dateStr, ms, durationSec * 1e6)
            throw NoLog(
                if (any.isEmpty()) "Cannot tell when this video was recorded: there is no date in its metadata or file name."
                else "There is no Hall log for " + fmtWall(any.first().wallUs) + " in the archive. The log is collected while a wheel is connected to this phone, or when it connects afterwards before going to sleep."
            )
        }
        if (cancelled()) throw InterruptedException()

        // ---- кадры для привязки: весь ролик, а у очень длинного — окно с наибольшим числом гашений ----
        val best0 = cands.filter { it.slow == prefSlow }.ifEmpty { cands }.maxBy { it.cover / sqrt(it.a.sigmaUs) }
        var fromI = 0
        var toI = nF - 1
        if (nF > MAX_ALIGN_FRAMES) {
            var bestK = -1
            var st = 0
            val stepI = max(1, MAX_ALIGN_FRAMES / 20)
            while (st + MAX_ALIGN_FRAMES <= nF) {
                val a = best0.a.wallUs + pts[st] / best0.slow
                val b = best0.a.wallUs + pts[st + MAX_ALIGN_FRAMES - 1] / best0.slow
                val k = best0.src.darksIn(a, b) * 1000 + (best0.src.covered(a, b) / (b - a + 1) * 999).toInt()
                if (k > bestK) { bestK = k; fromI = st }
                st += stepI
            }
            toI = fromI + MAX_ALIGN_FRAMES - 1
        }
        step("Reading frames to match them with the log…")
        val stats = PovVisDecoder.bin(ctx, uri, videoStartUs, pts[fromI], pts[toI], cancelled) { f ->
            step(fmt("Reading frames to match them with the log… %d%%", (f * 100).roundToInt()))
        }
        if (cancelled()) throw InterruptedException()

        // ---- привязка и разметка ----
        step("Matching the video with the log…")
        val al = PovSync.align(stats, fileFps, cands, prefSlow, best0, cancelled)
        step("Planning…")
        val pl = PovSync.plan(stats, pts, fileFps, durationSec, al)
        val cand = al.cand
        val src = al.src
        val slow = al.slow

        // ---- сводка ----
        if (slow > 1) rep.add(fmt("Slow motion ×%s%s: rpm below are real; segment times are in the file's own time; the result plays in real time.",
            num(slow), when { al.how == 1 -> " (found from the display switching on/off)"; slow.roundToInt() == hint -> " (from the metadata)"; else -> "" }))
        else if (hint >= 2 && al.how == 1) rep.add(fmt("  the display switching says real time, not ×%d — the metadata hint is ignored", hint))
        rep.add("Recorded (" + cand.a.what + "): " + fmtWall(cand.a.wallUs) + fmt(" ±%s s", num2(cand.a.sigmaUs / 1e6)))
        rep.add(fmt("Hall log: %s, session %08x, clock from %s (±%s ms)",
            src.s.wheelName, src.s.bootId, src.s.map.how, num2(src.s.map.sigmaUs / 1e3)))
        val syncRanges = pl.syncRanges
        val covered = syncRanges.sumOf { it[1] - it[0] }
        if (syncRanges.isEmpty()) {
            rep.add("The log covers this video only while the display was dark — nothing to stitch.")
        } else {
            rep.add(fmt("Sync available: %s of %s s (%d%%)", syncRanges.joinToString(", ") { num3(it[0]) + "–" + num3(it[1]) + " s" },
                num2(durationSec), (covered / durationSec * 100).roundToInt()))
        }
        val d = (al.anchor - cand.a.wallUs) / 1e3
        when (al.how) {
            1 -> rep.add(fmt("Matched by %d display switches (slideshow, start/stop): %+.0f ms from the metadata time, ±%s ms",
                al.events, d, num2(al.sigma / 1e3)))
            2 -> rep.add(fmt("Matched by one display switch: %+.0f ms from the metadata time, ±%s ms", d, num2(al.sigma / 1e3)))
            3 -> rep.add(fmt("Matched by the brightness pulsing with the rotor: %+.0f ms from the metadata time", d))
            else -> rep.add("Video match: no display switch in the video and the speed hardly changed — the metadata time is used. " +
                "With a steady speed the exact offset does not affect the stitching." +
                if (hint < 2) " If this is slow motion, its factor could not be found: film a moment when the display switches (a slideshow does it every few seconds)." else "")
        }
        if (pl.expUs > 0) rep.add(fmt("Camera exposure ≈ 1/%d s: one frame catches ~%d° of the 60° each arm draws",
            (1e6 / pl.expUs).roundToInt(), (pl.expUs * pl.wMed).roundToInt()))
        pl.tracks.forEachIndexed { i, t ->
            val ts = t.times
            var rMin = Double.MAX_VALUE; var rMax = 0.0
            for (k in 0 until ts.size - 1) {
                val rpm = 60.0 / ((ts[k + 1] - ts[k]) / slow * ARMS)
                rMin = min(rMin, rpm); rMax = max(rMax, rpm)
            }
            rep.add(fmt("  segment %d: %s–%s s, %d sweeps, %d..%d rpm, %s", i + 1, num3(ts.first()), num3(ts.last()),
                ts.size - 1, rMin.roundToInt(), rMax.roundToInt(),
                if (t.dir < 0) "rear wheel (or spinning backwards)" else "front wheel"))
        }
        if (pl.natives.isNotEmpty())
            rep.add("  frames kept as recorded (no log or display dark): " + pl.natives.joinToString(", ") { num3(it[0]) + "–" + num3(it[1]) + " s" })
        rep.add(fmt("Blended frames (sweeps): %d", pl.sweeps) +
            if (pl.fpsSplit > 0) fmt(" — %d intervals longer than 1/%s s were split to keep ≥ %s fps", pl.fpsSplit, num(PovSync.MIN_FPS), num(PovSync.MIN_FPS)) else "")
        if (pl.setSizes.isNotEmpty()) {
            val sz = pl.setSizes.sorted(); val sp = pl.setSpansUs.sorted()
            rep.add(fmt("  each sweep is stitched from %d frames picked by rotor phase (median), spanning %d ms",
                sz[sz.size / 2], (sp[sp.size / 2] / 1e3).roundToInt()))
            if (pl.openSweeps > pl.setSizes.size / 5) {
                val fps = PovSync.median(pl.tracks.flatMap { t -> (1 until t.times.size).map { (t.times[it] - t.times[it - 1]) * fileFps } }) ?: 0.0
                rep.add(fmt("  %d%% of sweeps stay partly open: the camera frame rate is nearly locked to the wheel (%s frames per 1/6 turn), " +
                    "so some angles were never filmed and show as thin dark gaps. Another frame rate or a slower shutter fixes it.",
                    (pl.openSweeps * 100.0 / pl.setSizes.size).roundToInt(), num2(fps)))
            }
        }
        if (pl.tracks.isNotEmpty())
            rep.add(fmt("Result: %s s at %s fps%s", num2(durationSec / slow), num2(fileFps * slow), if (slow > 1) " (real time)" else ""))

        return PovAnalysis(
            uri, name, relPath, codedW, codedH, rotation, fileFps, durationSec, videoBps, vi.mime, slow,
            pl.tracks, pts, videoStartUs, pl.plan, pl.sweeps, syncRanges, rep
        )
    }

    // ---- время записи по метаданным ----

    private class StoreTimes(val takenMs: Long, val modifiedS: Long)

    private fun mediaStoreTimes(ctx: Context, uri: Uri): StoreTimes {
        var taken = 0L; var mod = 0L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) taken = c.getLong(0)
                    if (!c.isNull(1)) mod = c.getLong(1)
                }
            }
        }
        return StoreTimes(taken, mod)
    }

    /**
     * Кандидаты начала записи. Каждый источник врёт по-своему: время в контейнере — до
     * секунды и у разных телефонов то начало, то конец записи; DATE_TAKEN — обычно начало
     * с миллисекундами; имя файла — местное время (у Pixel — UTC с миллисекундами);
     * время изменения — конец записи файла. Решает покрытие логом и сверка по видео.
     */
    private fun anchorsOf(name: String, dateStr: String?, ms: StoreTimes, durRealUs: Double): List<PovSync.Anchor> {
        val out = ArrayList<PovSync.Anchor>()
        if (ms.takenMs > 0) {
            out.add(PovSync.Anchor(ms.takenMs * 1e3, 1.0e6, "gallery date"))
            out.add(PovSync.Anchor(ms.takenMs * 1e3 - durRealUs, 1.5e6, "gallery date as the end"))
        }
        parseMp4Date(dateStr)?.let { ct ->
            out.add(PovSync.Anchor(ct, 1.5e6, "video creation time"))
            out.add(PovSync.Anchor(ct - durRealUs, 1.5e6, "video creation time as the end"))
        }
        Regex("(20\\d{6})[_-]?(\\d{6})(\\d{3})?").find(name)?.let { m ->
            val withMs = m.groupValues[3].isNotEmpty()
            for (tz in listOf(TimeZone.getDefault(), TimeZone.getTimeZone("UTC"))) {
                runCatching {
                    val f = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = tz }
                    val t = f.parse(m.groupValues[1] + m.groupValues[2])!!.time * 1e3 +
                        (if (withMs) m.groupValues[3].toInt() * 1e3 else 0.0)
                    out.add(PovSync.Anchor(t, if (withMs) 0.5e6 else 1.2e6, "file name" + if (tz.id == "UTC") " (UTC)" else ""))
                }
            }
        }
        if (ms.modifiedS > 0) out.add(PovSync.Anchor(ms.modifiedS * 1e6 - durRealUs, 2.5e6, "file time"))
        // Повторы в пределах полсекунды — один кандидат с лучшей точностью.
        out.sortBy { it.sigmaUs }
        val res = ArrayList<PovSync.Anchor>()
        for (a in out) if (res.none { abs(it.wallUs - a.wallUs) < 0.5e6 }) res.add(a)
        return res
    }

    /** METADATA_KEY_DATE: "20261002T230600.000Z" → мкс UTC. */
    private fun parseMp4Date(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        for (p in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
            val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val d = runCatching { f.parse(s) }.getOrNull() ?: continue
            if (d.time < 1_600_000_000_000L) return null     // «1904/1970»: даты нет
            return d.time * 1e3
        }
        return null
    }

    private fun fmtWall(us: Double): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date((us / 1e3).toLong()))

    /** Имя файла и его папка в галерее (RELATIVE_PATH есть с Android 10). */
    fun sourceInfo(ctx: Context, uri: Uri): Pair<String, String?> {
        var name = "video.mp4"
        var rel: String? = null
        val cols = if (Build.VERSION.SDK_INT >= 29)
            arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        else arrayOf(OpenableColumns.DISPLAY_NAME)
        runCatching {
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (cols.size > 1) rel = c.getString(1)
                }
            }
        }.onFailure {
            // Не все провайдеры знают RELATIVE_PATH — спросим одно имя.
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
            }
        }
        return Pair(name, rel)
    }

    /** Метки времени всех кадров видео (мкс, по возрастанию) и самая ранняя из них. */
    /** Индекс видеодорожки: метки кадров, первая метка, объём сжатых кадров (0 — неизвестен), кодек. */
    private class VideoIndex(val pts: LongArray, val startUs: Long, val bytes: Long, val mime: String)

    private fun videoIndex(ctx: Context, uri: Uri): VideoIndex {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var mime = ""
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("video/")) { track = i; mime = m; break }
            }
            if (track < 0) throw IllegalStateException("no video track")
            ex.selectTrack(track)
            var arr = LongArray(4096)
            var n = 0
            var bytes = 0L
            val sized = Build.VERSION.SDK_INT >= 28   // getSampleSize — с Android 9
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (n == arr.size) arr = arr.copyOf(arr.size * 2)
                arr[n++] = t
                if (sized) bytes += max(0L, ex.sampleSize)
                if (!ex.advance()) break
            }
            val out = arr.copyOf(n)
            out.sort()
            return VideoIndex(out, if (n > 0) out[0] else 0L, bytes, mime)
        } finally { runCatching { ex.release() } }
    }

    /**
     * Декодирует звуковую дорожку целиком: для детектора — float, не больше двух каналов
     * вперемешку (стерео не сводится в моно: два микрофона телефона на 15–20 кГц бывают в
     * противофазе, и сумма гасила бы чирп — детектор складывает их по энергиям); и/или
     * 16-битный PCM всех каналов.
     */
    fun decodeAudio(
        ctx: Context, uri: Uri, wantDet: Boolean, wantPcm: Boolean,
        cancelled: () -> Boolean
    ): DecodedAudio? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            val startUs = max(0L, ex.sampleTime)
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var isFloat = false
            var det = FloatArray(if (wantDet) 1 shl 20 else 0)
            var pcm = ShortArray(if (wantPcm) 1 shl 20 else 0)
            var nDet = 0
            var detCh = 0
            var nPcm = 0
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            var sinceCheck = 0
            while (!outEos) {
                if (++sinceCheck >= 64) { sinceCheck = 0; if (cancelled()) throw InterruptedException() }
                if (!inEos) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        isFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    continue
                }
                if (oi < 0) continue
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                if (info.size > 0) {
                    val ob = c.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val chn = max(1, ch)
                    val dch = min(chn, 2)                 // детектору — первые два канала
                    if (wantDet && detCh == 0) detCh = dch
                    if (isFloat) {
                        val fb = ob.asFloatBuffer()
                        val frames = fb.remaining() / chn
                        if (wantDet && nDet + frames * dch > det.size) det = det.copyOf(max(det.size * 2, nDet + frames * dch))
                        if (wantPcm && nPcm + frames * chn > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, nPcm + frames * chn))
                        for (f in 0 until frames) {
                            for (k in 0 until chn) {
                                val v = fb.get()
                                if (wantPcm) pcm[nPcm++] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                if (wantDet && k < dch) det[nDet++] = v
                            }
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        val frames = sb.remaining() / chn
                        if (wantDet && nDet + frames * dch > det.size) det = det.copyOf(max(det.size * 2, nDet + frames * dch))
                        if (wantPcm && nPcm + frames * chn > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, nPcm + frames * chn))
                        for (f in 0 until frames) {
                            for (k in 0 until chn) {
                                val v = sb.get()
                                if (wantPcm) pcm[nPcm++] = v
                                if (wantDet && k < dch) det[nDet++] = v / 32768f
                            }
                        }
                    }
                }
                c.releaseOutputBuffer(oi, false)
            }
            val frames = if (wantDet) nDet / max(1, detCh) else nPcm / max(1, ch)
            return DecodedAudio(
                sr, max(1, ch), startUs,
                if (wantDet) det.copyOf(nDet) else null, max(1, detCh),
                if (wantPcm) pcm.copyOf(nPcm) else null,
                frames
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    private fun fmt(f: String, vararg a: Any?) = String.format(Locale.US, f, *a)
    /** Число без лишних нулей: 4 → «4», 4.5 → «4.5». */
    fun num(v: Double): String =
        if (v == floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    private fun num2(v: Double) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    private fun num3(v: Double) = String.format(Locale.US, "%.3f", v)
}
