package com.povwheel.app.povvideo

import com.povwheel.app.hall.HallArchive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Чистая часть анализа ролика — без Android: привязка к логу Холла и разметка склейки.
 * Отдельно от [PovAnalyzer] (метаданные, декодер, сводка), чтобы её можно было прогнать
 * на настоящих роликах на компьютере.
 */
internal object PovSync {

    const val MIN_FPS = 10.0
    /**
     * Окно склейки не короче стольких кадров (-MinWindowFrames скрипта): на съёмке 60 к/с с
     * выдержкой короче 1/fps прорисовка в ~2 кадра оставляет провалы между клиньями, а
     * соседние прорисовки закрывают их кадрами с другой фазой выдержки.
     */
    const val MIN_WIN_FRAMES = 4
    /** Замедления, которые проверяются всегда (кроме подсказки метаданных). */
    val SLOWS = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0)
    /** Оценка по гашениям, ниже которой совпадение не считается. */
    private const val LIT_MIN = 10.0
    /** Гашение длиннее этого (мкс) — колесо стояло или не набрало обороты: склейку рвём. */
    private const val DARK_SPLIT_US = 1_000_000.0
    /**
     * Дальний поиск сдвига: ±столько (мкс) вокруг каждого кандидата метаданных. Часы камеры,
     * которую не подводит сеть, уходят на десятки секунд (у GoPro в тестах — на 18–22 с), и
     * поиск только ±2…5 с вокруг метаданных находил там двойника или чужое замедление:
     * ×2 вместо ×8, ×6 и ×1 вместо ×4.
     */
    const val FAR_US = 120e6
    /**
     * Дальняя гипотеза заменяет ближнюю, только если её оценка выше во столько раз. Двойник
     * через цикл слайдшоу (тот же рисунок гашений) набирал до 1.12 от верной ближней, а
     * верная дальняя при уехавших часах — от 1.48 до 2.2 от лучшей ближней.
     */
    private const val FAR_GAIN = 1.3
    /** Дальняя гипотеза должна быть убедительна и сама: не меньше стольких очков и 3 гашений. */
    private const val FAR_MIN = 40.0
    /** Шаг грубого перебора сдвига, мкс; точный — 2 мс вокруг лучших. */
    private const val STEP_US = 20e3
    /** Сколько лучших пиков дальнего перебора уточнять на каждую сессию и замедление. */
    private const val FAR_PEAKS = 3

    /** Кандидат начала записи: часы телефона (мкс UTC) первого кадра в реальном времени. */
    class Anchor(val wallUs: Double, val sigmaUs: Double, val what: String)

    /** Сессия лога с прогонами вращения и моментами «зажглась/погасла» (часы телефона). */
    class Src(
        val s: HallArchive.Session, val runs: List<RotorModel>, val litT: DoubleArray, val litOn: BooleanArray,
        /** Гашения [darkA[i], darkB[i]] — от «погасла» до следующей «зажглась». */
        val darkA: DoubleArray, val darkB: DoubleArray
    ) {
        /** Сколько мкс отрезка [a, b] покрыто прогонами. */
        fun covered(a: Double, b: Double): Double =
            runs.sumOf { max(0.0, min(b, it.t1) - max(a, it.t0)) }
        /** Сколько гашений целиком внутри [a, b]. */
        fun darksIn(a: Double, b: Double): Int {
            var c = 0
            for (i in darkA.indices) if (darkA[i] >= a && darkB[i] <= b) c++
            return c
        }
        fun phiInto(tq: DoubleArray, out: DoubleArray, tmp: DoubleArray) {
            java.util.Arrays.fill(out, Double.NaN)
            for (r in runs) {
                r.phiAtSorted(tq, tmp)
                for (j in tq.indices) if (!tmp[j].isNaN()) out[j] = tmp[j]
            }
        }
    }

    class Cand(val a: Anchor, val slow: Double, val src: Src, val cover: Double)

    /** Гипотеза привязки: кандидат, точное начало (мкс часов телефона), её оценка. */
    private class Hyp(val c: Cand, val at: Double, val score: Double, val events: Int) {
        var coh = Double.NaN
    }

    fun srcOf(s: HallArchive.Session): Src? {
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
        // Гашения — только закрытые: «погасла» и следующая за ней «зажглась».
        val da = ArrayList<Double>(); val db = ArrayList<Double>()
        var darkSince = Double.NaN
        for (k in lt.indices) {
            if (!lo[k]) { if (darkSince.isNaN()) darkSince = lt[k] }
            else if (!darkSince.isNaN()) { da.add(darkSince); db.add(lt[k]); darkSince = Double.NaN }
        }
        return Src(s, runs, lt.toDoubleArray(), lo.toBooleanArray(), da.toDoubleArray(), db.toDoubleArray())
    }

    // ------------------------------------------------------------------ привязка

    /** Как привязано: 1 — по гашениям, 2 — по одному гашению, 3 — по пульсации яркости, 0 — по метаданным. */
    class Alignment(val cand: Cand, val anchor: Double, val sigma: Double, val how: Int, val events: Int) {
        val slow: Double get() = cand.slow
        val src: Src get() = cand.src
    }

    /**
     * Сдвиг и замедление по кадрам [st]. Гашения дисплея ([PovAlignCore.LitScorer])
     * перебираются для каждой сессии и замедления дважды: близко (±2…5 с вокруг каждого
     * кандидата метаданных) и далеко (±[FAR_US]) — на случай, когда у камеры уехали часы.
     * Дальняя гипотеза побеждает, только если она заметно сильнее ближней ([FAR_GAIN]):
     * иначе выигрывал бы двойник через цикл слайдшоу. Близкие по оценке гипотезы (двойник,
     * соседнее замедление) решает пульсация общей яркости ([PovAlignCore.Coherence]). Без
     * гашений — она же, если скорость менялась; иначе — [fallback] по метаданным.
     */
    fun align(
        st: PovAlignCore.Stats, fileFps: Double, cands: List<Cand>, prefSlow: Double, fallback: Cand,
        cancelled: () -> Boolean
    ): Alignment {
        val sn = st.n
        val lit = if (sn >= 16) PovAlignCore.LitScorer(st) else null
        val coh = if (sn >= 64) PovAlignCore.Coherence(st, fileFps) else null
        val tq = DoubleArray(sn); val phi = DoubleArray(sn); val tmp = DoubleArray(sn)
        fun cohAt(c: Cand, at: Double): Double {
            val co = coh ?: return 0.0
            for (j in 0 until sn) tq[j] = at + st.ptsUs[j] / c.slow
            c.src.phiInto(tq, phi, tmp)
            return co.value(phi)
        }

        val near = ArrayList<Hyp>()
        val far = ArrayList<Hyp>()
        if (lit != null && sn >= 2) {
            val spanFirst = st.ptsUs[0].toDouble()
            val spanLast = st.ptsUs[sn - 1].toDouble()
            for ((key, cs) in cands.filter { it.src.darkA.isNotEmpty() }.groupBy { Pair(it.src, it.slow) }) {
                if (cancelled()) throw InterruptedException()
                val (src, slow) = key
                // Где вообще может что-то совпасть: ролик задевает хотя бы одно гашение.
                val lim0 = src.darkA[0] - spanLast / slow - 1e6
                val lim1 = src.darkB[src.darkB.size - 1] - spanFirst / slow + 1e6
                val ivs = cs.map { doubleArrayOf(max(lim0, it.a.wallUs - FAR_US), min(lim1, it.a.wallUs + FAR_US)) }
                    .filter { it[1] > it[0] }.sortedBy { it[0] }
                val merged = ArrayList<DoubleArray>()
                for (iv in ivs) {
                    val last = merged.lastOrNull()
                    if (last != null && iv[0] <= last[1]) last[1] = max(last[1], iv[1]) else merged.add(iv.copyOf())
                }
                // грубый перебор: 20 мс
                var cnt = 0
                for (iv in merged) cnt += ((iv[1] - iv[0]) / STEP_US).toInt() + 1
                val xs = DoubleArray(cnt); val sc = DoubleArray(cnt); val ev = IntArray(cnt)
                var k = 0
                for (iv in merged) {
                    if (cancelled()) throw InterruptedException()
                    val m = ((iv[1] - iv[0]) / STEP_US).toInt() + 1
                    for (q in 0 until m) {
                        val x = iv[0] + q * STEP_US
                        val s = lit.score(src.darkA, src.darkB, slow, x)
                        xs[k] = x; sc[k] = s.value; ev[k] = s.events; k++
                    }
                }
                fun refine(c: Cand, x0: Double): Hyp {
                    var bestAt = x0
                    var best = lit.score(src.darkA, src.darkB, slow, x0)
                    for (q in -15..15) {
                        if (q == 0) continue
                        val s = lit.score(src.darkA, src.darkB, slow, x0 + q * 2e3)
                        if (s.value > best.value) { best = s; bestAt = x0 + q * 2e3 }
                    }
                    return Hyp(c, bestAt, best.value, best.events)
                }
                // ближние: лучшее в окне ±2…5 с вокруг каждого кандидата
                for (c in cs) {
                    val half = searchHalf(c.a.sigmaUs)
                    var bi = -1
                    for (i in 0 until cnt) {
                        if (abs(xs[i] - c.a.wallUs) > half || ev[i] == 0) continue
                        if (bi < 0 || sc[i] > sc[bi]) bi = i
                    }
                    if (bi >= 0) near.add(refine(c, xs[bi]))
                }
                // дальние: несколько лучших локальных максимумов, не ближе 0.5 с друг к другу
                val peaks = (0 until cnt).filter { i ->
                    ev[i] > 0 && (i == 0 || xs[i - 1] < xs[i] - 1.5 * STEP_US || sc[i] >= sc[i - 1]) &&
                        (i == cnt - 1 || xs[i + 1] > xs[i] + 1.5 * STEP_US || sc[i] > sc[i + 1])
                }.sortedByDescending { sc[it] }
                val taken = ArrayList<Double>()
                for (i in peaks) {
                    if (taken.size >= FAR_PEAKS) break
                    if (taken.any { abs(it - xs[i]) < 0.5e6 }) continue
                    taken.add(xs[i])
                    val c = cs.minBy { abs(it.a.wallUs - xs[i]) }
                    far.add(refine(c, xs[i]))
                }
            }
        }

        val nearTop = near.maxByOrNull { it.score }
        val farTop = far.maxByOrNull { it.score }
        val useFar = farTop != null && farTop.events >= 3 && farTop.score >= FAR_MIN &&
            (nearTop == null || nearTop.events < 2 || farTop.score >= FAR_GAIN * max(nearTop.score, LIT_MIN))
        val hyps = if (useFar) far + near else near
        val top = hyps.maxByOrNull { it.score }
        if (top != null && top.events >= 2 && top.score >= LIT_MIN) {
            val kept = ArrayList<Hyp>()
            for (h in hyps.sortedByDescending { it.score }) {
                if (h.score < 0.6 * top.score) break
                if (kept.any { it.c.src === h.c.src && it.c.slow == h.c.slow && abs(it.at - h.at) < 150e3 }) continue
                kept.add(h)
            }
            var pick = kept[0]
            if (kept.size > 1) {
                for (h in kept) {
                    var best = Double.NEGATIVE_INFINITY
                    for (k in -5..5) best = max(best, cohAt(h.c, h.at + k * 10e3))
                    h.coh = best
                }
                val bc = kept.maxBy { it.coh }
                if (bc.coh > kept[0].coh * 1.03) pick = bc
            }
            return Alignment(pick.c, pick.at, 1e6 / (fileFps * pick.c.slow) / 2, 1, pick.events)
        }
        // Одно гашение в ролике: сдвиг по нему, замедление — подсказка метаданных или ×1.
        near.filter { it.c.slow == prefSlow && it.score >= LIT_MIN }.maxByOrNull { it.score }?.let { one ->
            return Alignment(one.c, one.at, 1e6 / (fileFps * one.c.slow) / 2, 2, one.events)
        }
        // Гашений нет: пульсация яркости — пик есть, если скорость заметно менялась.
        if (coh != null) {
            var bestC: Cand? = null
            var bestPk: PovAlignCore.Peak? = null
            val tested = HashSet<String>()
            for (c in cands.filter { it.slow == prefSlow }.sortedByDescending { it.cover }) {
                val half = searchHalf(c.a.sigmaUs)
                val key = c.src.s.bootId.toString() + "/" + Math.round(c.a.wallUs / 0.5e6)
                if (!tested.add(key)) continue
                if (cancelled()) throw InterruptedException()
                val xs = DoubleArray((2 * half / STEP_US).toInt() + 1) { c.a.wallUs - half + it * STEP_US }
                val ys = DoubleArray(xs.size) { cohAt(c, xs[it]) }
                val pk = PovAlignCore.peakOf(xs, ys)
                if (pk.excess >= 0.8 && pk.prominence >= 1.4 && (bestPk == null || pk.excess > bestPk.excess)) {
                    bestC = c; bestPk = pk
                }
            }
            if (bestC != null && bestPk != null) return Alignment(bestC, bestPk.at, 10e3, 3, 0)
        }
        return Alignment(fallback, fallback.a.wallUs, fallback.a.sigmaUs, 0, 0)
    }

    /** Полуширина ближнего поиска сдвига вокруг кандидата, мкс. */
    private fun searchHalf(sigmaUs: Double) = (3 * sigmaUs).coerceIn(2e6, 5e6)

    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return s[s.size / 2]
    }

    // ------------------------------------------------------------------ разметка

    /** Разметка и то, что о ней нужно сводке. */
    class Planned(
        val tracks: List<TickTrack>,
        val syncRanges: List<DoubleArray>,
        val plan: PovPlan,
        val sweeps: Int,
        val fpsSplit: Int,
        /** Отрезки (с файла), где кадры идут как есть. */
        val natives: List<DoubleArray>,
        /** Кадров исходника на прорисовку (медиана; 0 — прорисовок нет). */
        val framesPerSweep: Double
    )

    /**
     * Разметка склейки — та же, что у звуковой синхронизации, только тики (каждые 60°
     * поворота ротора) берутся из лога, а не из чирпов. Каждая прорисовка — один кадр
     * результата: окно ровно в одну прорисовку (см. PovRenderer.windows). Тики идут через
     * весь прогон вращения: короткое гашение слайдшоу склейку не рвёт. Прорисовка длиннее
     * 1/[MIN_FPS] с делится на части, каждая — склейка своих кадров. [pts] — метки кадров,
     * мкс от первого.
     */
    fun plan(pts: LongArray, fileFps: Double, durationSec: Double, al: Alignment): Planned {
        val src = al.src
        val slow = al.slow
        val anchor = al.anchor
        val nF = pts.size
        val durReal = durationSec * 1e6 / slow

        val tracks = ArrayList<TickTrack>()
        val syncRanges = ArrayList<DoubleArray>()
        for (r in src.runs) {
            for (iv in stitchIntervals(src, max(r.t0, anchor), min(r.t1, anchor + durReal))) {
                val tk = r.ticks(iv[0], iv[1])
                if (tk.size < 2) continue
                val times = DoubleArray(tk.size) { (tk[it] - anchor) * slow / 1e6 }
                tracks.add(TickTrack(times, BooleanArray(tk.size) { true }, r.dir))
                syncRanges.add(doubleArrayOf(times.first(), times.last()))
            }
        }
        tracks.sortBy { it.times.first() }
        syncRanges.sortBy { it[0] }

        // Отрисовка (интервалы между тиками) и всё остальное (кадры как есть).
        class Seg(val native: Boolean, val t0: Double, val t1: Double, val wins: Int)
        val segs = ArrayList<Seg>()
        var cursor = 0.0
        var sweeps = 0
        var fpsSplit = 0
        for (t in tracks) {
            val ts = t.times
            if (ts[0] < cursor) continue
            if (ts[0] > cursor) segs.add(Seg(true, cursor, ts[0], 0))
            for (k in 0 until ts.size - 1) {
                val dur = ts[k + 1] - ts[k]
                val wins = max(1, ceil(dur / slow * MIN_FPS - 1e-6).toInt())
                if (wins > 1) fpsSplit++
                sweeps += wins
                segs.add(Seg(false, ts[k], ts[k + 1], wins))
            }
            cursor = ts.last()
        }
        if (durationSec > cursor) segs.add(Seg(true, cursor, durationSec, 0))

        val ptsSec = DoubleArray(nF) { pts[it] / 1e6 }
        fun frameIdx(t: Double): Double = frameIndex(ptsSec, t)
        fun frameAt(t: Double): Int = Math.rint(frameIdx(t)).toInt().coerceIn(0, nF)
        val kinds = ArrayList<Int>()
        val counts = ArrayList<Int>()
        val segT0 = ArrayList<Double>()
        val segT = ArrayList<Double>()
        for (sg in segs) {
            if (sg.native) {
                val n = frameAt(sg.t1) - frameAt(sg.t0)
                if (n > 0) { kinds.add(0); counts.add(n); segT0.add(0.0); segT.add(0.0) }
                continue
            }
            for (w in 0 until sg.wins) {
                val ta = if (w == 0) sg.t0 else sg.t0 + (sg.t1 - sg.t0) * w / sg.wins
                val tb = if (w == sg.wins - 1) sg.t1 else sg.t0 + (sg.t1 - sg.t0) * (w + 1) / sg.wins
                val n = frameAt(tb) - frameAt(ta)
                if (n < 1) continue
                kinds.add(if (sg.wins == 1) 2 else 1); counts.add(n)
                val fa = frameIdx(ta)
                segT0.add(fa); segT.add(frameIdx(tb) - fa)
            }
        }
        val natives = segs.filter { it.native && it.t1 - it.t0 > 0.05 }.map { doubleArrayOf(it.t0, it.t1) }
        val per = median(tracks.flatMap { t -> (1 until t.times.size).map { (t.times[it] - t.times[it - 1]) * fileFps } }) ?: 0.0
        return Planned(
            tracks, syncRanges,
            PovPlan(kinds.toIntArray(), counts.toIntArray(), segT0.toDoubleArray(), segT.toDoubleArray()),
            sweeps, fpsSplit, natives, per
        )
    }

    /**
     * Отрезки [a, b], которые склеиваются: всё, кроме гашений длиннее [DARK_SPLIT_US] —
     * там колесо стояло или не набрало оборотов, и кадры идут как есть. Короткие гашения
     * (смена файла в слайдшоу) склейку не рвут.
     */
    fun stitchIntervals(src: Src, a: Double, b: Double): List<DoubleArray> {
        if (b <= a) return emptyList()
        val ivs = ArrayList<DoubleArray>()
        for (i in src.darkA.indices) ivs.add(doubleArrayOf(src.darkA[i], src.darkB[i]))
        if (src.litT.isNotEmpty()) {
            // до первой «зажглась» лента не горела; после последней «погасла» — тоже
            if (src.litOn[0]) ivs.add(0, doubleArrayOf(Double.NEGATIVE_INFINITY, src.litT[0]))
            if (!src.litOn[src.litOn.size - 1]) ivs.add(doubleArrayOf(src.litT[src.litT.size - 1], Double.POSITIVE_INFINITY))
        }
        val out = ArrayList<DoubleArray>()
        var t = a
        for (iv in ivs) {
            if (iv[1] - iv[0] < DARK_SPLIT_US || iv[1] <= t || iv[0] >= b) continue
            if (iv[0] > t) out.add(doubleArrayOf(t, iv[0]))
            t = max(t, iv[1])
        }
        if (b > t) out.add(doubleArrayOf(t, b))
        return out
    }

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
}
