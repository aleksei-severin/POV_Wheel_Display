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
import kotlin.math.ceil
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
 * Разметка видео на склейки — то же, что считает tools/pov_fps_blend/POV-BlendFPS.ps1
 * перед рендером. Элемент i плана:
 *  - kind 0 — [counts] кадров идут как есть (колесо не рисует или лога на это время нет);
 *  - kind 1 — [counts] кадров склеиваются в один, который стоит на экране всё их время
 *    (часть длинной прорисовки, разбитой ради нижнего предела частоты кадров);
 *  - kind 2 — окно ровно в одну прорисовку: смешивание шести сдвинутых окон с дробным
 *    началом ([segT0], [segT] — точное начало и длина прорисовки в кадрах).
 */
class PovPlan(
    val kinds: IntArray,
    val counts: IntArray,
    val segT0: DoubleArray,
    val segT: DoubleArray
) {
    val totalFrames: Int = counts.sum()
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
 * Синхронизация ролика по логу Холла из архива телефона (см. HallArchive).
 *
 * 1. Когда снят ролик — по метаданным: время создания в контейнере, DATE_TAKEN галереи,
 *    имя файла, время изменения. Каждый источник врёт по-своему (секунды, начало или
 *    конец записи, местное время или UTC), поэтому это не одно число, а набор кандидатов.
 * 2. Лог на это время — сессии колёс из архива; угол ротора по ним — [RotorModel].
 * 3. Точный сдвиг — по самому видео ([PovAlignCore]): пульсации яркости диска против фазы
 *    ротора из лога. Если скорость почти не менялась, сдвиг ни измерить, ни заметить
 *    нельзя — остаётся привязка по метаданным, а окна склейки чуть удлиняются на её
 *    неопределённость (окно длиннее прорисовки при смешивании «светлее» безвредно,
 *    короче — оставляет провал).
 */
internal object PovAnalyzer {

    const val MIN_FPS = 10.0
    const val MIN_WIN_FRAMES = 4         // = PovRenderer.MIN_WIN_FRAMES, -MinWindowFrames скрипта
    private const val ARMS = 6
    /** Не больше стольких секунд файла читаем ради привязки по видео. */
    private const val MAX_ALIGN_SEC = 60.0

    class NoLog(message: String) : Exception(message)

    /** Кандидат начала записи: часы телефона (мкс UTC) первого кадра в реальном времени. */
    private class Anchor(val wallUs: Double, val sigmaUs: Double, val what: String)

    /** Сессия лога с прогонами вращения и моментами «зажглась/погасла» (часы телефона). */
    private class Src(val s: HallArchive.Session, val runs: List<RotorModel>, val litT: DoubleArray, val litOn: BooleanArray) {
        /** 1 — лента светилась, 0 — нет, NaN — неизвестно (до первой отметки в архиве). */
        fun litAt(t: Double): Double {
            if (litT.isEmpty()) return Double.NaN
            var lo = -1
            var hi = litT.size
            while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (litT[m] <= t) lo = m else hi = m }
            return if (lo < 0) (if (litOn[0]) 0.0 else 1.0) else (if (litOn[lo]) 1.0 else 0.0)
        }
        /** Сколько мкс отрезка [a, b] покрыто прогонами. */
        fun covered(a: Double, b: Double): Double =
            runs.sumOf { max(0.0, min(b, it.t1) - max(a, it.t0)) }
        fun phiInto(tq: DoubleArray, out: DoubleArray, tmp: DoubleArray) {
            java.util.Arrays.fill(out, Double.NaN)
            for (r in runs) {
                r.phiAtSorted(tq, tmp)
                for (j in tq.indices) if (!tmp[j].isNaN()) out[j] = tmp[j]
            }
        }
    }

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
        val avgDt = (pts.last() - pts.first()).toDouble() / (pts.size - 1) / 1e6
        val fileFps = 1.0 / avgDt
        val durationSec = pts.last() / 1e6 + avgDt
        val videoBps = if (vi.bytes > 0 && durationSec > 0) vi.bytes * 8.0 / durationSec else fileBps
        if (cancelled()) throw InterruptedException()

        // Замедление: подсказка метаданных (частота съёмки против частоты файла).
        val hint = if (captureFps > 0 && fileFps > 0) (captureFps / fileFps).roundToInt() else 0
        val slows = if (hint >= 2) listOf(hint.toDouble(), 1.0) else listOf(1.0)

        // ---- когда снято ----
        step("Looking up the Hall log…")
        val ms = mediaStoreTimes(ctx, uri)
        val rep = ArrayList<String>()
        val dispW = if (rotation == 90 || rotation == 270) codedH else codedW
        val dispH = if (rotation == 90 || rotation == 270) codedW else codedH
        rep.add(fmt("Video: %d×%d, %s fps, %s s", dispW, dispH, num2(fileFps), num2(durationSec)) +
            if (videoBps > 0) ", " + num(Math.rint(videoBps / 1e5) / 10) + " Mbit/s" else "")
        if (captureFps > 0 && hint >= 2) rep.add(fmt("  captured at %s fps — looks like slow motion ×%d", num2(captureFps), hint))

        class Cand(val a: Anchor, val slow: Double, val src: Src, val cover: Double)
        val cands = ArrayList<Cand>()
        val srcCache = HashMap<Long, Src?>()
        for (slow in slows) {
            val durReal = durationSec * 1e6 / slow
            val anchors = anchorsOf(name, dateStr, ms, durReal)
            if (anchors.isEmpty()) continue
            val lo = anchors.minOf { it.wallUs } - 60e6
            val hi = anchors.maxOf { it.wallUs } + durReal + 60e6
            for (s in HallArchive.sessionsAround(lo, hi, 0.0)) {
                val src = srcCache.getOrPut(s.bootId) { srcOf(s) } ?: continue
                for (a in anchors) {
                    val pad = 3 * a.sigmaUs
                    val cov = src.covered(a.wallUs - pad, a.wallUs + durReal + pad)
                    if (cov > 0.5e6) cands.add(Cand(a, slow, src, cov))
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

        // ---- точная привязка по видео ----
        val best0 = cands.maxBy { it.cover / sqrt(it.a.sigmaUs) }
        val durReal0 = durationSec * 1e6 / best0.slow
        // Читаем отрезок файла, где лог есть у лучшего кандидата, с запасом на поиск.
        val w0 = searchHalf(best0.a.sigmaUs)
        var fromF = 0.0
        var toF = durationSec * 1e6
        run {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (r in best0.src.runs) {
                val a = max(r.t0, best0.a.wallUs - w0); val b = min(r.t1, best0.a.wallUs + durReal0 + w0)
                if (b > a) { lo = min(lo, a); hi = max(hi, b) }
            }
            if (lo < hi) {
                fromF = max(0.0, (lo - best0.a.wallUs - w0) * best0.slow)
                toF = min(durationSec * 1e6, (hi - best0.a.wallUs + w0) * best0.slow)
            }
        }
        if (toF - fromF > MAX_ALIGN_SEC * 1e6) {
            // Длинный ролик: берём минуту, где скорость менялась сильнее всего — там
            // привязка и измеряется, и нужна.
            val len = MAX_ALIGN_SEC * 1e6
            var bestStart = fromF
            var bestVar = -1.0
            var st = fromF
            while (st + len <= toF + 1) {
                var wMin = Double.MAX_VALUE; var wMax = 0.0
                var tf = st
                while (tf <= st + len) {
                    val t = best0.a.wallUs + tf / best0.slow
                    for (r in best0.src.runs) if (t >= r.t0 && t <= r.t1) {
                        val w = r.wAt(t); wMin = min(wMin, w); wMax = max(wMax, w)
                    }
                    tf += 0.25e6
                }
                val v = if (wMax > 0) wMax - wMin else -1.0
                if (v > bestVar) { bestVar = v; bestStart = st }
                st += 5e6
            }
            fromF = bestStart
            toF = fromF + len
        }
        step("Reading frames to match them with the log…")
        val binned = PovVisDecoder.bin(ctx, uri, videoStartUs, fromF.toLong(), toF.toLong(), cancelled) { f ->
            step(fmt("Reading frames to match them with the log… %d%%", (f * 100).roundToInt()))
        }
        if (cancelled()) throw InterruptedException()
        step("Matching the video with the log…")
        val scorer = if (binned.n >= 64) PovAlignCore.Scorer(binned, fileFps) else null

        class Fit(val c: Cand, val at: Double, val peak: PovAlignCore.Peak?, val excess: Double, val litPeak: Double)
        var bestFit: Fit? = null
        val tested = HashSet<String>()
        if (scorer != null) {
            val n = binned.n
            val tq = DoubleArray(n); val phi = DoubleArray(n); val tmp = DoubleArray(n); val lit = DoubleArray(n)
            for (c in cands.sortedByDescending { it.cover }) {
                val half = searchHalf(c.a.sigmaUs)
                val key = c.src.s.bootId.toString() + "/" + c.slow + "/" + (c.a.wallUs / (half * 0.5)).roundToInt()
                if (!tested.add(key)) continue
                if (cancelled()) throw InterruptedException()
                val stepUs = if (half <= 1.0e6) 5e3 else 10e3
                val xs = DoubleArray((2 * half / stepUs).toInt() + 1) { c.a.wallUs - half + it * stepUs }
                val js = DoubleArray(xs.size)
                val ls = DoubleArray(xs.size)
                for (k in xs.indices) {
                    for (j in 0 until n) tq[j] = xs[k] + scorer.pts[j] / c.slow
                    c.src.phiInto(tq, phi, tmp)
                    js[k] = scorer.coherence(phi)
                    for (j in 0 until n) lit[j] = c.src.litAt(tq[j])
                    ls[k] = scorer.litScore(lit)
                }
                val pk = PovAlignCore.peakOf(xs, js)
                val excess = if (pk.median > 1.0) (pk.value - pk.median) / (pk.median - 1.0) else 0.0
                // Включение или гашение ленты в кадре — привязка до кадра, она главнее.
                val lp = PovAlignCore.peakOf(xs, ls)
                val litOk = lp.value > 0.35 && lp.prominence > 1.5
                var at = if (litOk) lp.at else pk.at
                // Тонко, 1 мс: в лепестке когерентности (если привязка по включению
                // есть — вокруг неё, в пределах ±шага).
                if (excess >= 0.8 && pk.prominence >= 1.4 || litOk) {
                    val fx = DoubleArray(41) { at - 20e3 + it * 1e3 }
                    val fy = DoubleArray(fx.size) { k ->
                        for (j in 0 until n) tq[j] = fx[k] + scorer.pts[j] / c.slow
                        c.src.phiInto(tq, phi, tmp)
                        scorer.coherence(phi)
                    }
                    val fp = PovAlignCore.peakOf(fx, fy)
                    if (!litOk || abs(fp.at - at) <= stepUs * 2) at = fp.at
                }
                val score = excess + (if (litOk) 2.0 else 0.0)
                val f = Fit(c, at, pk, excess, if (litOk) lp.value else 0.0)
                val bf = bestFit
                val bscore = if (bf == null) -1.0 else bf.excess + (if (bf.litPeak > 0) 2.0 else 0.0)
                if (bf == null || score > bscore) bestFit = f
            }
        }

        // ---- выбор привязки ----
        val fit = bestFit
        val videoOk = fit != null && fit.peak != null &&
            ((fit.excess >= 0.8 && fit.peak.prominence >= 1.4) || fit.litPeak > 0)
        // Нет уверенного совпадения — остаёмся на самом надёжном кандидате метаданных,
        // а не на том, чей невыраженный пик оказался чуть выше.
        val chosen = if (videoOk && fit != null) fit.c else best0
        val slow = chosen.slow
        val anchor: Double
        val sigma: Double
        if (videoOk && fit != null) {
            anchor = fit.at
            val pk = fit.peak!!
            // Лепесток на полувысоте — сотни мс, а сам максимум повторяется по половинам
            // ролика до ±10 мс (проверено на записях с эталонными тиками): берём восьмую.
            sigma = if (fit.litPeak > 0) 1e6 / fileFps else max(2e3, (pk.lobeHi - pk.lobeLo) / 8)
        } else {
            anchor = chosen.a.wallUs
            sigma = chosen.a.sigmaUs
        }
        val src = chosen.src
        val durReal = durationSec * 1e6 / slow

        // ---- тики ----
        val tracks = ArrayList<TickTrack>()
        val margins = ArrayList<DoubleArray>()     // запас окна по интервалам каждого трека
        val syncRanges = ArrayList<DoubleArray>()
        for (r in src.runs) {
            for (iv in litIntervals(src, max(r.t0, anchor), min(r.t1, anchor + durReal))) {
                val tk = r.ticks(iv[0], iv[1])
                if (tk.size < 2) continue
                val times = DoubleArray(tk.size) { (tk[it] - anchor) * slow / 1e6 }
                tracks.add(TickTrack(times, BooleanArray(tk.size) { true }, r.dir))
                margins.add(DoubleArray(tk.size - 1) { k ->
                    val t = tk[k]; val w = r.wAt(t)
                    val m = max(abs(r.wAt(t + sigma) - w), abs(r.wAt(t - sigma) - w)) / max(1e-12, w)
                    min(0.5, m)
                })
                syncRanges.add(doubleArrayOf(times.first(), times.last()))
            }
        }
        tracks.sortBy { it.times.first() }

        // ---- разметка ----
        step("Planning…")
        class Seg(val native: Boolean, val t0: Double, val t1: Double, val wins: Int, val margin: Double)
        val segs = ArrayList<Seg>()
        var cursor = 0.0
        var sweeps = 0
        var fpsSplit = 0
        val coverEnd = durationSec
        val order = tracks.indices.sortedBy { tracks[it].times.first() }
        for (ti in order) {
            val ts = tracks[ti].times
            val mg = margins[ti]
            if (ts[0] < cursor) continue
            if (ts[0] > cursor) segs.add(Seg(true, cursor, ts[0], 0, 0.0))
            for (k in 0 until ts.size - 1) {
                val dur = ts[k + 1] - ts[k]
                val wins = max(1, ceil(dur / slow * MIN_FPS - 1e-6).toInt())
                if (wins > 1) fpsSplit++
                sweeps += wins
                segs.add(Seg(false, ts[k], ts[k + 1], wins, mg[k]))
            }
            cursor = ts.last()
        }
        if (coverEnd > cursor) segs.add(Seg(true, cursor, coverEnd, 0, 0.0))

        val ptsSec = DoubleArray(pts.size) { pts[it] / 1e6 }
        fun frameIdx(t: Double): Double = frameIndex(ptsSec, t)
        fun frameAt(t: Double): Int = Math.rint(frameIdx(t)).toInt().coerceIn(0, pts.size)
        val kinds = ArrayList<Int>()
        val counts = ArrayList<Int>()
        val segT0 = ArrayList<Double>()
        val segT = ArrayList<Double>()
        var maxMargin = 0.0
        for (sg in segs) {
            if (sg.native) {
                val nn = frameAt(sg.t1) - frameAt(sg.t0)
                if (nn > 0) { kinds.add(0); counts.add(nn); segT0.add(0.0); segT.add(0.0) }
                continue
            }
            for (w in 0 until sg.wins) {
                val ta = if (w == 0) sg.t0 else sg.t0 + (sg.t1 - sg.t0) * w / sg.wins
                val tb = if (w == sg.wins - 1) sg.t1 else sg.t0 + (sg.t1 - sg.t0) * (w + 1) / sg.wins
                val nn = frameAt(tb) - frameAt(ta)
                if (nn < 1) continue
                kinds.add(if (sg.wins == 1) 2 else 1); counts.add(nn)
                val fa = frameIdx(ta)
                var len = frameIdx(tb) - fa
                var start = fa
                if (sg.wins == 1 && sg.margin > 0) {
                    // Неопределённость привязки: окно чуть длиннее прорисовки —
                    // при смешивании «светлее» это безвредно, провал — нет.
                    val add = len * sg.margin
                    start -= add / 2; len += add
                    maxMargin = max(maxMargin, sg.margin)
                }
                segT0.add(start); segT.add(len)
            }
        }
        val plan = PovPlan(kinds.toIntArray(), counts.toIntArray(), segT0.toDoubleArray(), segT.toDoubleArray())

        // ---- сводка ----
        rep.add("Recorded (" + chosen.a.what + "): " + fmtWall(chosen.a.wallUs) +
            fmt(" ±%s s", num2(chosen.a.sigmaUs / 1e6)))
        if (slow > 1) rep.add(fmt("Slow motion ×%s: rpm below are real; segment times are in the file's own time; the result plays in real time.", num(slow)))
        rep.add(fmt("Hall log: %s, session %08x, clock from %s (±%s ms)",
            src.s.wheelName, src.s.bootId, src.s.map.how, num2(src.s.map.sigmaUs / 1e3)))
        val covered = syncRanges.sumOf { it[1] - it[0] }
        if (syncRanges.isEmpty()) {
            rep.add("The log covers this video only while the display was dark — nothing to stitch.")
        } else {
            rep.add(fmt("Sync available: %s of %s s (%d%%)", syncRanges.joinToString(", ") { num3(it[0]) + "–" + num3(it[1]) + " s" },
                num2(durationSec), (covered / durationSec * 100).roundToInt()))
        }
        if (videoOk && fit != null) {
            val d = (anchor - chosen.a.wallUs) / 1e3
            if (fit.litPeak > 0) rep.add(fmt("Matched to the video by the display switching on/off: %+.0f ms from the metadata time, ±%s ms",
                d, num2(sigma / 1e3)))
            else rep.add(fmt("Matched to the video by the rotor phase: %+.0f ms from the metadata time, ±%s ms (match ×%s above background)",
                d, num2(sigma / 1e3), num2(1 + fit.excess)))
        } else {
            rep.add("Video match: the speed hardly changed (or the wheel is too small in the frame) — " +
                "the metadata time is used. With a steady speed the exact offset does not affect the stitching.")
        }
        if (maxMargin > 0.002) rep.add(fmt("  blend windows widened by up to %s%% to cover the timing uncertainty", num2(maxMargin * 100)))
        tracks.forEachIndexed { i, t ->
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
        val natives = segs.filter { it.native && it.t1 - it.t0 > 0.05 }
        if (natives.isNotEmpty())
            rep.add("  frames kept as recorded (no log or display dark): " + natives.joinToString(", ") { num3(it.t0) + "–" + num3(it.t1) + " s" })
        rep.add(fmt("Blended frames (sweeps): %d", sweeps) +
            if (fpsSplit > 0) fmt(" — %d intervals longer than 1/%s s were split to keep ≥ %s fps", fpsSplit, num(MIN_FPS), num(MIN_FPS)) else "")
        val ivs = ArrayList<Double>()
        for (t in tracks) for (k in 1 until t.times.size) ivs.add(t.times[k] - t.times[k - 1])
        if (ivs.isNotEmpty()) {
            ivs.sort()
            val framesPerSweep = ivs[ivs.size / 2] * fileFps
            if (framesPerSweep < MIN_WIN_FRAMES)
                rep.add(fmt("  a sweep is only ~%s frames: blend windows widened to %d frames (~%s sweeps) to close the gaps the camera's shutter leaves. Film at 120–240 fps for crisp results.",
                    num(Math.rint(framesPerSweep * 10) / 10), MIN_WIN_FRAMES, num(Math.rint(MIN_WIN_FRAMES / framesPerSweep * 10) / 10)))
        }
        if (tracks.isNotEmpty())
            rep.add(fmt("Result: %s s at %s fps%s", num2(durationSec / slow), num2(fileFps * slow), if (slow > 1) " (real time)" else ""))

        return PovAnalysis(
            uri, name, relPath, codedW, codedH, rotation, fileFps, durationSec, videoBps, vi.mime, slow,
            tracks, pts, videoStartUs, plan, sweeps, syncRanges, rep
        )
    }

    /** Полуширина поиска сдвига вокруг кандидата, мкс. */
    private fun searchHalf(sigmaUs: Double) = (3 * sigmaUs).coerceIn(0.3e6, 4e6)

    private fun srcOf(s: HallArchive.Session): Src? {
        val ht = ArrayList<Double>(); val hs = ArrayList<Int>()
        val lt = ArrayList<Double>(); val lo = ArrayList<Boolean>()
        for (i in s.esp.indices) {
            val w = s.map.wall(s.esp[i])
            when (s.type[i].toInt()) {
                HallArchive.T_HALL -> { ht.add(w); hs.add(s.arg[i].toInt()) }
                HallArchive.T_LIT -> { lt.add(w); lo.add(true) }
                HallArchive.T_DARK -> { lt.add(w); lo.add(false) }
            }
        }
        val runs = RotorModel.build(ht.toDoubleArray(), hs.toIntArray(), s.calX100, s.armReverse)
        if (runs.isEmpty()) return null
        return Src(s, runs, lt.toDoubleArray(), lo.toBooleanArray())
    }

    /** Отрезки [a, b], где лента светилась (неизвестное состояние считаем светящимся). */
    private fun litIntervals(src: Src, a: Double, b: Double): List<DoubleArray> {
        if (b <= a) return emptyList()
        val out = ArrayList<DoubleArray>()
        var t = a
        var on = src.litAt(a).let { it.isNaN() || it > 0.5 }
        for (k in src.litT.indices) {
            val x = src.litT[k]
            if (x <= a) continue
            if (x >= b) break
            if (on && !src.litOn[k]) { out.add(doubleArrayOf(t, x)); on = false }
            else if (!on && src.litOn[k]) { t = x; on = true }
        }
        if (on) out.add(doubleArrayOf(t, b))
        return out
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
    private fun anchorsOf(name: String, dateStr: String?, ms: StoreTimes, durRealUs: Double): List<Anchor> {
        val out = ArrayList<Anchor>()
        if (ms.takenMs > 0) {
            out.add(Anchor(ms.takenMs * 1e3, 1.0e6, "gallery date"))
            out.add(Anchor(ms.takenMs * 1e3 - durRealUs, 1.5e6, "gallery date as the end"))
        }
        parseMp4Date(dateStr)?.let { ct ->
            out.add(Anchor(ct, 1.5e6, "video creation time"))
            out.add(Anchor(ct - durRealUs, 1.5e6, "video creation time as the end"))
        }
        Regex("(20\\d{6})[_-]?(\\d{6})(\\d{3})?").find(name)?.let { m ->
            val withMs = m.groupValues[3].isNotEmpty()
            for (tz in listOf(TimeZone.getDefault(), TimeZone.getTimeZone("UTC"))) {
                runCatching {
                    val f = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = tz }
                    val t = f.parse(m.groupValues[1] + m.groupValues[2])!!.time * 1e3 +
                        (if (withMs) m.groupValues[3].toInt() * 1e3 else 0.0)
                    out.add(Anchor(t, if (withMs) 0.5e6 else 1.2e6, "file name" + if (tz.id == "UTC") " (UTC)" else ""))
                }
            }
        }
        if (ms.modifiedS > 0) out.add(Anchor(ms.modifiedS * 1e6 - durRealUs, 2.5e6, "file time"))
        // Повторы в пределах полсекунды — один кандидат с лучшей точностью.
        out.sortBy { it.sigmaUs }
        val res = ArrayList<Anchor>()
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

    /** Дробный номер кадра для момента t (кадр j — от pts[j] до pts[j+1]). */
    fun frameIndex(pts: DoubleArray, t: Double): Double {
        val n = pts.size
        if (n == 0) return 0.0
        if (n == 1) return if (t >= pts[0]) 0.5 else 0.0
        if (t <= pts[0]) return (t - pts[0]) / (pts[1] - pts[0])
        if (t >= pts[n - 1]) return n - 1 + (t - pts[n - 1]) / (pts[n - 1] - pts[n - 2])
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (pts[m] <= t) lo = m else hi = m }
        return lo + (t - pts[lo]) / max(1e-9, pts[lo + 1] - pts[lo])
    }

    /** Имя файла и его папка в галерее (RELATIVE_PATH есть с Android 10). */
    private fun sourceInfo(ctx: Context, uri: Uri): Pair<String, String?> {
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
