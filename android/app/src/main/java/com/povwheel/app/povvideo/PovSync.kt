package com.povwheel.app.povvideo

import com.povwheel.app.hall.HallArchive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Чистая часть анализа ролика — без Android: привязка к логу Холла, оценка выдержки,
 * разметка склейки. Отдельно от [PovAnalyzer] (метаданные, декодер, сводка), чтобы её
 * можно было прогнать на настоящих роликах на компьютере.
 */
internal object PovSync {

    const val MIN_FPS = 10.0
    /** Замедления, которые проверяются всегда (кроме подсказки метаданных). */
    val SLOWS = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0)
    /** Оценка по гашениям, ниже которой совпадение не считается. */
    private const val LIT_MIN = 10.0
    /** Гашение длиннее этого (мкс) — колесо стояло или не набрало обороты: склейку рвём. */
    private const val DARK_SPLIT_US = 1_000_000.0
    /**
     * Доля оценки выдержки, с которой подбирается набор кадров. Оценка по свету завышена
     * (размытие, ореол, насыщение): на полном разрешении освещённая дуга точки — около трети
     * её. Недооценка стоит лишних кадров, но шейдер берёт для точки ближайший по времени
     * снимавший её кадр, так что лишние дальние кадры не мутят картинку; переоценка — щели.
     */
    private const val ARC_SAFETY = 0.35
    /** Не больше стольких кадров в одной склейке (= размер массива слоёв в шейдере склейки). */
    const val MAX_SET = 32
    /** Дальше этого (мкс реального времени) от середины прорисовки кадры не берём. */
    private const val SET_RADIUS_US = 300_000.0
    /** Ячеек круга 60° в модели покрытия (по 0.25°). */
    private const val NB = 240

    /** Кандидат начала записи: часы телефона (мкс UTC) первого кадра в реальном времени. */
    class Anchor(val wallUs: Double, val sigmaUs: Double, val what: String)

    /** Сессия лога с прогонами вращения и моментами «зажглась/погасла» (часы телефона). */
    class Src(
        val s: HallArchive.Session, val runs: List<RotorModel>, val litT: DoubleArray, val litOn: BooleanArray,
        /** Гашения [darkA[i], darkB[i]] — от «погасла» до следующей «зажглась». */
        val darkA: DoubleArray, val darkB: DoubleArray
    ) {
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
        /**
         * Номер светлого промежутка, в котором момент [t]: −1 — лента не горела. Кадры для
         * склейки прорисовки берутся только из её собственного промежутка — иначе в одну
         * склейку попали бы картинка до смены файла и после неё.
         */
        fun litSeg(t: Double): Int {
            if (litT.isEmpty()) return Int.MAX_VALUE
            var lo = -1
            var hi = litT.size
            while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (litT[m] <= t) lo = m else hi = m }
            return if (lo < 0) (if (litOn[0]) -1 else Int.MAX_VALUE) else (if (litOn[lo]) lo else -1)
        }
        /** Скорость, градусы/мкс; NaN вне прогонов. */
        fun wAt(t: Double): Double {
            for (r in runs) if (t >= r.t0 && t <= r.t1) return r.wAt(t)
            return Double.NaN
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
     * Сдвиг и замедление по кадрам [st]. Сначала гашения дисплея ([PovAlignCore.LitScorer])
     * в окне вокруг каждого кандидата метаданных, для каждого замедления; близкие по оценке
     * гипотезы (двойник через цикл слайдшоу, соседнее замедление) решает пульсация общей
     * яркости ([PovAlignCore.Coherence]). Без гашений — она же, если скорость менялась;
     * иначе — [fallback] по метаданным.
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

        val hyps = ArrayList<Hyp>()
        if (lit != null) {
            val tested = HashSet<String>()
            for (c in cands) {
                if (c.src.darkA.isEmpty()) continue
                val half = searchHalf(c.a.sigmaUs)
                val key = c.src.s.bootId.toString() + "/" + c.slow + "/" + Math.round(c.a.wallUs / 0.5e6)
                if (!tested.add(key)) continue
                if (cancelled()) throw InterruptedException()
                var bestAt = c.a.wallUs; var bestS = Double.NEGATIVE_INFINITY; var bestEv = 0
                var x = c.a.wallUs - half
                while (x <= c.a.wallUs + half) {
                    val s = lit.score(c.src.darkA, c.src.darkB, c.slow, x)
                    if (s.value > bestS) { bestS = s.value; bestAt = x; bestEv = s.events }
                    x += 20e3
                }
                if (bestEv == 0) continue
                // тонко: ±30 мс по 2 мс
                val x0 = bestAt
                for (k in -15..15) {
                    val xx = x0 + k * 2e3
                    val s = lit.score(c.src.darkA, c.src.darkB, c.slow, xx)
                    if (s.value > bestS) { bestS = s.value; bestAt = xx; bestEv = s.events }
                }
                hyps.add(Hyp(c, bestAt, bestS, bestEv))
            }
        }

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
        hyps.filter { it.c.slow == prefSlow && it.score >= LIT_MIN }.maxByOrNull { it.score }?.let { one ->
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
                val xs = DoubleArray((2 * half / 20e3).toInt() + 1) { c.a.wallUs - half + it * 20e3 }
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

    /** Полуширина поиска сдвига вокруг кандидата, мкс. */
    private fun searchHalf(sigmaUs: Double) = (3 * sigmaUs).coerceIn(2e6, 5e6)

    // ------------------------------------------------------------------ выдержка

    /**
     * Выдержка в реальном времени, мкс: по окнам [PovAlignCore.WIN] кадров, где лента
     * светилась, а фазы кадров закрывают круг плотно (щель не шире 6°) — тогда склейка окна
     * и есть весь диск, и доля света кадра от неё — доля 60°, которую он ловит. 0 — не
     * удалось оценить.
     */
    fun exposureOf(st: PovAlignCore.Stats, src: Src, anchor: Double, slow: Double): Double {
        val win = PovAlignCore.WIN
        val tq = DoubleArray(win); val ph = DoubleArray(win); val tmp = DoubleArray(win)
        val est = ArrayList<Double>()
        for (s in st.winStart) {
            if (s + win > st.n) continue
            var ok = true
            for (k in 0 until win) {
                tq[k] = anchor + st.ptsUs[s + k] / slow
                if (src.litAt(tq[k]) < 0.5) { ok = false; break }
            }
            if (!ok) continue
            src.phiInto(tq, ph, tmp)
            if (ph.any { it.isNaN() }) continue
            val m = DoubleArray(win) { ((ph[it] % 60.0) + 60.0) % 60.0 }
            m.sort()
            var gap = m[0] + 60.0 - m[win - 1]
            for (k in 1 until win) gap = max(gap, m[k] - m[k - 1])
            if (gap > 6.0) continue
            val r = (0 until win).map { st.winRatio[s + it].toDouble() }.filter { !it.isNaN() }
            val wm = median((0 until win).map { src.wAt(tq[it]) }.filter { !it.isNaN() }) ?: continue
            val rm = median(r) ?: continue
            if (rm <= 0 || wm <= 0) continue
            est.add(60.0 * rm / wm)
        }
        return median(est) ?: 0.0
    }

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
        /** Кадров в склейке каждой прорисовки и их разброс во времени (мкс реального времени). */
        val setSizes: IntArray,
        val setSpansUs: DoubleArray,
        /** Прорисовок, которые и по модели выдержки остались не закрыты (< 90 % круга). */
        val openSweeps: Int,
        /** Оценка выдержки (мкс реального времени, 0 — нет) и средняя скорость, °/мкс. */
        val expUs: Double,
        val wMed: Double
    )

    /**
     * Тики — через весь прогон вращения: короткое гашение слайдшоу склейку не рвёт.
     * Каждая прорисовка — набор кадров по фазе ([phaseSet]); прорисовка длиннее 1/[MIN_FPS] с
     * делится на части из своих кадров. [pts] — метки кадров, мкс от первого.
     */
    fun plan(st: PovAlignCore.Stats, pts: LongArray, fileFps: Double, durationSec: Double, al: Alignment): Planned {
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

        val expUs = exposureOf(st, src, anchor, slow)
        val expModel = if (expUs > 0) expUs * ARC_SAFETY else 0.25e6 / (fileFps * slow)

        val fq = DoubleArray(nF) { anchor + pts[it] / slow }
        val phiAll = DoubleArray(nF)
        src.phiInto(fq, phiAll, DoubleArray(nF))
        val wAll = DoubleArray(nF) { src.wAt(fq[it]) }
        val halfFrameReal = 0.5e6 / (fileFps * slow)
        val halfFrameFile = 0.5e6 / fileFps
        val segAll = IntArray(nF) { src.litSeg(fq[it] + halfFrameReal) }

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
                segs.add(Seg(false, ts[k], ts[k + 1], wins))
            }
            cursor = ts.last()
        }
        if (durationSec > cursor) segs.add(Seg(true, cursor, durationSec, 0))

        val ptsSec = DoubleArray(nF) { pts[it] / 1e6 }
        fun frameAt(t: Double): Int = Math.rint(frameIndex(ptsSec, t)).toInt().coerceIn(0, nF)
        val kinds = ArrayList<Int>()
        val counts = ArrayList<Int>()
        val setStart = ArrayList<Int>().apply { add(0) }
        val setIdx = ArrayList<Int>()
        val setRing = ArrayList<Int>()
        val setPhase = ArrayList<Float>()
        val near = ArrayList<Int>()
        val setSizes = ArrayList<Int>()
        val setSpans = ArrayList<Double>()
        var openSweeps = 0
        for (sg in segs) {
            if (sg.native) {
                val nn = frameAt(sg.t1) - frameAt(sg.t0)
                if (nn > 0) { kinds.add(0); counts.add(nn); setStart.add(setIdx.size); near.add(frameAt(sg.t0)) }
                continue
            }
            for (w in 0 until sg.wins) {
                val ta = if (w == 0) sg.t0 else sg.t0 + (sg.t1 - sg.t0) * w / sg.wins
                val tb = if (w == sg.wins - 1) sg.t1 else sg.t0 + (sg.t1 - sg.t0) * (w + 1) / sg.wins
                val fa = frameAt(ta)
                val nn = frameAt(tb) - fa
                if (nn < 1) continue
                if (sg.wins == 1) {
                    val seg = src.litSeg(anchor + 0.5 * (ta + tb) * 1e6 / slow)
                    val ps = if (seg < 0) PhaseSet(IntArray(0), 0.0)
                             else phaseSet(pts, phiAll, wAll, segAll, seg, slow, fileFps, ta * 1e6, tb * 1e6, expModel)
                    val set = if (ps.frames.isNotEmpty()) ps.frames else IntArray(min(nn, MAX_SET)) { fa + it }
                    kinds.add(2); counts.add(nn)
                    // кольцо — удаление середины кадра от середины прорисовки, в прорисовках
                    val tc = 0.5 * (ta + tb) * 1e6
                    val sweepUs = max(1.0, (tb - ta) * 1e6)
                    var best = set[0]
                    for (j in set) {
                        val dt = abs(pts[j] + halfFrameFile - tc)
                        setIdx.add(j)
                        setRing.add(floor(dt / sweepUs + 0.5).toInt())
                        setPhase.add(phase60(phiAll[j]))
                        if (dt < abs(pts[best] + halfFrameFile - tc)) best = j
                    }
                    near.add(best)
                    setSizes.add(set.size)
                    setSpans.add((pts[set.last()] - pts[set.first()]) / slow)
                    if (seg >= 0 && ps.coverage < 0.9) openSweeps++
                } else {
                    // часть длинной прорисовки — её собственные кадры (не больше MAX_SET, равномерно)
                    kinds.add(1); counts.add(nn)
                    val m = min(nn, MAX_SET)
                    for (q in 0 until m) {
                        val j = fa + (q.toLong() * nn / m).toInt()
                        setIdx.add(j); setRing.add(0); setPhase.add(phase60(phiAll[min(j, nF - 1)]))
                    }
                    near.add(fa + nn / 2)
                }
                setStart.add(setIdx.size)
                sweeps++
            }
        }
        val natives = segs.filter { it.native && it.t1 - it.t0 > 0.05 }.map { doubleArrayOf(it.t0, it.t1) }
        val wMed = median(wAll.filter { !it.isNaN() }) ?: 0.0
        // Окно фаз, в котором шейдер собирает кадры, снимавшие точку: шире освещённой дуги
        // (≈ треть оценки по свету) с запасом, но не настолько, чтобы дрожь камеры и
        // размытие собрали кадры, которые точку не видели.
        val refDeg = if (expUs > 0 && wMed > 0) (0.6 * expUs * wMed).coerceIn(4.0, 12.0) else 6.0
        return Planned(
            tracks, syncRanges,
            PovPlan(kinds.toIntArray(), counts.toIntArray(), setStart.toIntArray(), setIdx.toIntArray(),
                setRing.toIntArray(), setPhase.toFloatArray(), near.toIntArray(), refDeg),
            sweeps, fpsSplit, natives, setSizes.toIntArray(), setSpans.toDoubleArray(), openSweeps, expUs, wMed
        )
    }

    /** Фаза ротора по модулю 60°, градусы (NaN — 0: кадр вне лога в набор не попадает). */
    private fun phase60(p: Double): Float {
        if (p.isNaN()) return 0f
        var x = p % 60.0
        if (x < 0) x += 60.0
        return x.toFloat()
    }

    /** Набор кадров одной прорисовки и доля круга, которую закрывают их дуги (по модели выдержки). */
    class PhaseSet(val frames: IntArray, val coverage: Double)

    /**
     * Кадры для склейки прорисовки [taUs]…[tbUs] (мкс файла) — только из светлого
     * промежутка [seg] (номера промежутков кадров — [segOf]). Кадр j ловит дугу
     * [φ_j, φ_j + ω_j·e] по модулю 60° (e — выдержка в реальном времени). Кандидаты — по
     * близости к середине прорисовки; кадр берётся, только если закрывает заметную часть ещё
     * не закрытого круга, и сбор кончается, как только круг закрыт. Так прорисовка с
     * длинной выдержкой (240 к/с) берёт свои же кадры, а GoPro с дугой в 10° — ближайшие из
     * соседних прорисовок, чьи фазы ложатся в щели. Сдвиг выдержки относительно метки кадра
     * и rolling shutter сдвигают все дуги одинаково — покрытие от них не зависит.
     */
    fun phaseSet(
        pts: LongArray, phi: DoubleArray, w: DoubleArray, segOf: IntArray, seg: Int, slow: Double, fileFps: Double,
        taUs: Double, tbUs: Double, expUs: Double
    ): PhaseSet {
        val n = pts.size
        val tc = 0.5 * (taUs + tbUs)
        val frameUs = 1e6 / fileFps
        var jc = java.util.Arrays.binarySearch(pts, tc.toLong())
        if (jc < 0) jc = -jc - 1
        // радиус: полпрорисовки и полтора круга по модели выдержки, не дальше SET_RADIUS_US реального времени
        var ws = 0.0; var wc = 0
        for (j in max(0, jc - 2)..min(n - 1, jc + 2)) if (!w[j].isNaN()) { ws += w[j]; wc++ }
        val arcMid = if (wc == 0) 10.0 else ws / wc * expUs
        val need = 60.0 / max(arcMid, 0.5)
        val sweepFrames = (tbUs - taUs) / frameUs
        val maxHalf = max(1, (SET_RADIUS_US * slow / frameUs).toInt())
        val half = min(maxHalf, ceil(max(sweepFrames / 2 + 1, 1.5 * need)).toInt())
        val lo = max(0, jc - half - 1)
        val hi = min(n - 1, jc + half)
        val cand = (lo..hi).sortedBy { abs(pts[it] + frameUs / 2 - tc) }
        val cov = BooleanArray(NB)
        var covN = 0
        val pick = ArrayList<Int>()
        for (j in cand) {
            val p = phi[j]
            if (p.isNaN() || w[j].isNaN() || segOf[j] != seg) continue
            val arc = w[j] * expUs
            var pm = p % 60.0
            if (pm < 0) pm += 60.0
            val b0 = floor(pm / 60.0 * NB).toInt()
            val nb = min(NB, max(1, ceil(arc / 60.0 * NB).toInt()))
            var fresh = 0
            for (q in 0 until nb) if (!cov[(b0 + q) % NB]) fresh++
            if (fresh >= max(1, nb / 6)) {
                for (q in 0 until nb) { val b = (b0 + q) % NB; if (!cov[b]) { cov[b] = true; covN++ } }
                pick.add(j)
            }
            if (covN == NB || pick.size >= MAX_SET) break
        }
        pick.sort()
        return PhaseSet(pick.toIntArray(), covN.toDouble() / NB)
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
