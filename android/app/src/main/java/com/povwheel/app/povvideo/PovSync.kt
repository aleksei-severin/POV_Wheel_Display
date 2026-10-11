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
    /**
     * Совпадение по гашениям засчитывается, только если оно выделяется над фоном — над тем,
     * что то же замедление набирает при посторонних сдвигах: (оценка − медиана) / MAD по
     * всему перебору сдвигов не меньше этого. Днём на улице, с движущейся камеры и с мелким
     * в кадре колесом гашений в межкадровой разности не видно вовсе, а её колыхания от
     * движения камеры при каком-нибудь сдвиге и замедлении набирают десятки очков: прежде
     * этого хватало, и ролик S25+ ×4 уходил в ×12, iPhone ×1 — в ×6. С разностью только по
     * крупным переменам пикселя (PovAlignCore.DIFF_FLOOR) на 26 роликах с проверенной
     * привязкой у верных совпадений 8.0…18, у шума уличного ролика — до 6.5 (со средней
     * |разностью| было 6.3…16 против 4.9, порог стоял на 6).
     */
    private const val Z_MIN = 7.5
    /**
     * Замедление из метаданных (частота съёмки против частоты файла — у Samsung есть)
     * уступает другому, только если совпадение с тем сильнее лучшего совпадения с
     * подсказкой во столько раз: на проверенных роликах неверное замедление набирало не
     * больше половины верного.
     */
    private const val HINT_GAIN = 2.0
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
    /** Граница замедленного участка уточняется по гашениям в пределах ±столько (мкс файла). */
    private const val EDGE_SEARCH_US = 400e3

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

    /** Кандидат: начало по метаданным, замедление (постоянное или по участкам), сессия, покрытие логом. */
    class Cand(val a: Anchor, val map: TimeMap, val src: Src, val cover: Double) {
        val slow: Double get() = map.slow
    }

    /**
     * Гипотеза привязки: кандидат, точное начало (мкс часов телефона), её оценка и [z] —
     * насколько оценка выше фона своего перебора (см. [Z_MIN]).
     */
    private class Hyp(val c: Cand, val at: Double, val score: Double, val events: Int, val z: Double) {
        var coh = Double.NaN
        /** Совпадение настоящее, а не колыхание межкадровой разности. */
        val real: Boolean get() = score >= LIT_MIN && z >= Z_MIN
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

    /**
     * Как привязано: 1 — по гашениям, 2 — по одному гашению, 0 — по метаданным. [anchor] —
     * момент первого кадра (мкс часов телефона); кадр файла t снят в anchor + map.real(t).
     */
    class Alignment(val cand: Cand, val anchor: Double, val sigma: Double, val how: Int, val events: Int) {
        val map: TimeMap get() = cand.map
        val slow: Double get() = cand.slow
        val src: Src get() = cand.src
    }

    /**
     * Сдвиг и замедление по кадрам [st]. Гашения дисплея ([PovAlignCore.LitScorer])
     * перебираются для каждой сессии и замедления дважды: близко (±2…5 с вокруг каждого
     * кандидата метаданных) и далеко (±[FAR_US]) — на случай, когда у камеры уехали часы.
     * Дальняя гипотеза побеждает, только если она заметно сильнее ближней ([FAR_GAIN]):
     * иначе выигрывал бы двойник через цикл слайдшоу. Близкие по оценке гипотезы (двойник,
     * соседнее замедление) решает пульсация общей яркости ([PovAlignCore.Coherence]).
     * Гашения засчитываются, только если совпадение выделяется над фоном перебора
     * ([Z_MIN]), а замедление [prefSlow] из метаданных уступает только вдвое более
     * сильному совпадению ([HINT_GAIN]): если гашений в кадре не видно, ролик остаётся при
     * замедлении и времени из метаданных ([fallback]), а не при случайном пике шума.
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
            for (j in 0 until sn) tq[j] = at + c.map.real(st.ptsUs[j].toDouble())
            c.src.phiInto(tq, phi, tmp)
            return co.value(phi)
        }

        val near = ArrayList<Hyp>()
        val far = ArrayList<Hyp>()
        if (lit != null && sn >= 2) {
            val spanFirst = st.ptsUs[0].toDouble()
            val spanLast = st.ptsUs[sn - 1].toDouble()
            for ((key, cs) in cands.filter { it.src.darkA.isNotEmpty() }.groupBy { Pair(it.src, it.map) }) {
                if (cancelled()) throw InterruptedException()
                val (src, map) = key
                val tm = lit.timed(map)
                // Где вообще может что-то совпасть: ролик задевает хотя бы одно гашение.
                val lim0 = src.darkA[0] - map.real(spanLast) - 1e6
                val lim1 = src.darkB[src.darkB.size - 1] - map.real(spanFirst) + 1e6
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
                        val s = lit.score(src.darkA, src.darkB, tm, x)
                        xs[k] = x; sc[k] = s.value; ev[k] = s.events; k++
                    }
                }
                // Фон — оценки при посторонних сдвигах (их в переборе подавляющее большинство):
                // медиана и MAD там, где ролик задевает гашения. На коротком переборе фон не
                // оценить, и совпадение проходит без этой проверки, как прежде.
                val bg = DoubleArray(cnt)
                var nb = 0
                for (i in 0 until cnt) if (ev[i] > 0) bg[nb++] = sc[i]
                var bgMed = Double.NEGATIVE_INFINITY
                var bgMad = 1.0
                if (nb >= 50) {
                    bg.sort(0, nb)
                    bgMed = bg[nb / 2]
                    for (i in 0 until nb) bg[i] = abs(bg[i] - bgMed)
                    bg.sort(0, nb)
                    bgMad = max(1.0, 1.4826 * bg[nb / 2])
                }
                fun refine(c: Cand, x0: Double): Hyp {
                    var bestAt = x0
                    var best = lit.score(src.darkA, src.darkB, tm, x0)
                    for (q in -15..15) {
                        if (q == 0) continue
                        val s = lit.score(src.darkA, src.darkB, tm, x0 + q * 2e3)
                        if (s.value > best.value) { best = s; bestAt = x0 + q * 2e3 }
                    }
                    return Hyp(c, bestAt, best.value, best.events, (best.value - bgMed) / bgMad)
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

        // В счёт идут только настоящие совпадения (Hyp.real), а другое замедление вместо
        // подсказки метаданных — лишь при вдвое более сильном совпадении, чем лучшее с ней.
        val hintBest = if (prefSlow > 1) (near + far).filter { it.c.slow == prefSlow }.maxOfOrNull { it.score } ?: 0.0 else 0.0
        fun ok(h: Hyp) = h.real && (prefSlow <= 1 || h.c.slow == prefSlow || h.score >= HINT_GAIN * max(hintBest, LIT_MIN))
        val nearOk = near.filter(::ok)
        val farOk = far.filter(::ok)
        val nearTop = nearOk.maxByOrNull { it.score }
        val farTop = farOk.maxByOrNull { it.score }
        val useFar = farTop != null && farTop.events >= 3 && farTop.score >= FAR_MIN &&
            (nearTop == null || nearTop.events < 2 || farTop.score >= FAR_GAIN * max(nearTop.score, LIT_MIN))
        val hyps = if (useFar) farOk + nearOk else nearOk
        val top = hyps.maxByOrNull { it.score }
        if (top != null && top.events >= 2) {
            val kept = ArrayList<Hyp>()
            for (h in hyps.sortedByDescending { it.score }) {
                if (h.score < 0.6 * top.score) break
                if (kept.any { it.c.src === h.c.src && it.c.map === h.c.map && abs(it.at - h.at) < 150e3 }) continue
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
            val al = Alignment(pick.c, pick.at, 1e6 / (fileFps * pick.c.slow) / 2, 1, pick.events)
            return if (lit != null && !pick.c.map.constant) refineEdges(lit, st, al) else al
        }
        // Одно гашение в ролике: сдвиг по нему, замедление — подсказка метаданных или ×1.
        nearOk.filter { it.c.slow == prefSlow }.maxByOrNull { it.score }?.let { one ->
            return Alignment(one.c, one.at, 1e6 / (fileFps * one.c.slow) / 2, 2, one.events)
        }
        // Гашений не нашлось — время из метаданных. Пульсацию яркости здесь больше не
        // спрашиваем: на уличных роликах её пики шума (excess до 13, prominence ~2.2)
        // неотличимы от настоящих (1.4…7.4 и 1.9…3.4 на проверенных роликах), и она
        // уверенно ставила сдвиг на секунды мимо. Склейке нужна скорость ротора, а она
        // за секунду ошибки метаданных почти не меняется.
        return Alignment(fallback, fallback.a.wallUs, fallback.a.sigmaUs, 0, 0)
    }

    /**
     * Границы замедленного участка ×1 → ×k → ×1 — точнее по гашениям. Звук их показывает с
     * разбросом в десятые доли секунды ([SlowMoAudio.LAG_US]), а гашение в обычной части
     * сдвигается в 1 − 1/k раза быстрее границы: 0.2 с ошибки — 150 мс мимо, и короткие
     * гашения слайдшоу (70–170 мс) уже не совпадают. Каждая граница двигается в пределах
     * ±[EDGE_SEARCH_US], середина остаётся на месте — её привязали гашения внутри. Ровная
     * оценка (у края нет гашений) границу не трогает; из равных лучших берётся средняя.
     */
    private fun refineEdges(lit: PovAlignCore.LitScorer, st: PovAlignCore.Stats, al: Alignment): Alignment {
        val map0 = al.map
        val src = al.src
        val k = map0.slow
        var from = map0.slowFrom
        var to = map0.slowTo
        val last = st.ptsUs[st.n - 1].toDouble()
        // точка середины, которую держим на месте при любых границах из перебора
        val mid = ((from ?: 0.0) + EDGE_SEARCH_US + ((to ?: last) - EDGE_SEARCH_US)) / 2
        fun anchorOf(m: TimeMap) = al.anchor + map0.real(mid) - m.real(mid)
        fun scoreOf(m: TimeMap) = lit.score(src.darkA, src.darkB, lit.timed(m), anchorOf(m)).value
        fun best(cur: Double, make: (Double) -> TimeMap): Double {
            val base = scoreOf(make(cur))
            val xs = ArrayList<Double>()
            val vs = ArrayList<Double>()
            var q = -EDGE_SEARCH_US
            while (q <= EDGE_SEARCH_US + 1) { xs.add(cur + q); vs.add(scoreOf(make(cur + q))); q += 10e3 }
            val im = vs.indices.maxBy { vs[it] }
            if (vs[im] < base + LIT_MIN) return cur
            // середина плато вокруг максимума: гашение совпадает целыми кадрами, и оценка
            // стоит ровно на отрезке сдвигов
            var lo = im
            var hi = im
            while (lo > 0 && vs[lo - 1] >= vs[im] - 0.5) lo--
            while (hi < vs.size - 1 && vs[hi + 1] >= vs[im] - 0.5) hi++
            return (xs[lo] + xs[hi]) / 2
        }
        from?.let { f -> from = best(f) { TimeMap.ramp(it, to, k) } }
        to?.let { t -> to = best(t) { TimeMap.ramp(from, it, k) } }
        if (from == map0.slowFrom && to == map0.slowTo) return al
        val m = TimeMap.ramp(from, to, k)
        val c = al.cand
        val s = lit.score(src.darkA, src.darkB, lit.timed(m), anchorOf(m))
        return Alignment(Cand(c.a, m, c.src, c.cover), anchorOf(m), al.sigma, al.how, s.events)
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
     * 1/[MIN_FPS] с (реального времени) делится на части, каждая — склейка своих кадров.
     * [pts] — метки кадров, мкс от первого. Тики переводятся во время файла через
     * [Alignment.map]: у slo-mo, замедленного лишь в середине, прорисовка в начале и в конце
     * занимает в разы меньше кадров, чем в середине.
     */
    fun plan(pts: LongArray, fileFps: Double, durationSec: Double, al: Alignment): Planned {
        val src = al.src
        val map = al.map
        val anchor = al.anchor
        val nF = pts.size
        val durReal = map.real(durationSec * 1e6)

        val tracks = ArrayList<TickTrack>()
        val syncRanges = ArrayList<DoubleArray>()
        for (r in src.runs) {
            for (iv in stitchIntervals(src, max(r.t0, anchor), min(r.t1, anchor + durReal))) {
                val tk = r.ticks(iv[0], iv[1])
                if (tk.size < 2) continue
                val times = DoubleArray(tk.size) { map.file(tk[it] - anchor) / 1e6 }
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
                val dur = (map.real(ts[k + 1] * 1e6) - map.real(ts[k] * 1e6)) / 1e6
                val wins = max(1, ceil(dur * MIN_FPS - 1e-6).toInt())
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
