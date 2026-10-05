package com.povwheel.app.povvideo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Угол ротора по логу Холла — то, чем склейка видео заменила звуковые чирпы.
 *
 * На входе события датчиков (время в часах телефона, мкс UTC, и номер датчика), на
 * выходе — непрерывный поворот Φ(t) в градусах (растёт в любую сторону вращения) и
 * моменты, когда ротор проходит очередные 60°: это границы прорисовок, ровно то, что
 * раньше давали чирпы, только без звука и без задержки звука до микрофона.
 *
 * Три шага:
 *  1. Каждое событие датчика k ставится на свой угол: sgn·60·k + cal[k] (как якорь
 *     фазы в renderingTask, см. main.cpp). Калибровка — поправка на разброс установки
 *     датчиков; без неё границы прорисовок дрожали бы на этот разброс.
 *  2. Развёртка в непрерывный поворот: пропущенный датчик — это 120° вместо 60°, по
 *     времени и текущей скорости видно, сколько целых проходов пропало.
 *  3. Сглаживание локальной параболой по полутора оборотам с каждой стороны с
 *     отбрасыванием выбросов: метка ISR иногда опаздывает на доли миллисекунды (кеш
 *     выключен на время операции с флешем), а склейке нужна именно гладкая кривая.
 *     Колесо в реальном времени так не может — его фильтр видит только прошлое; здесь
 *     весь ролик известен заранее, и кривая строится по обе стороны от точки.
 */
class RotorModel private constructor(
    /** Моменты событий, мкс (часы телефона), по возрастанию — внутри одного прогона. */
    val t: DoubleArray,
    /** Сглаженный поворот в эти моменты, градусы. */
    val phi: DoubleArray,
    /** Сглаженная скорость, градусы/мкс. */
    val w: DoubleArray,
    /** +1 — сектор растёт со временем (переднее колесо), −1 — убывает. */
    val dir: Int
) {
    val t0: Double get() = t.first()
    val t1: Double get() = t.last()

    /** Поворот в момент [x] (кубический Эрмит по сглаженным точкам); NaN — вне прогона. */
    fun phiAt(x: Double): Double {
        if (x < t[0] || x > t[t.size - 1]) return Double.NaN
        var lo = 0
        var hi = t.size - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (t[m] <= x) lo = m else hi = m }
        return herm(lo, x)
    }

    /** То же для отсортированных [xs] за один проход; NaN вне прогона. */
    fun phiAtSorted(xs: DoubleArray, out: DoubleArray) {
        var i = 0
        for (j in xs.indices) {
            val x = xs[j]
            if (x < t[0] || x > t[t.size - 1]) { out[j] = Double.NaN; continue }
            while (i < t.size - 2 && t[i + 1] <= x) i++
            while (i > 0 && t[i] > x) i--
            out[j] = herm(i, x)
        }
    }

    /** Скорость в момент [x], градусы/мкс (линейно между точками). */
    fun wAt(x: Double): Double {
        if (x <= t[0]) return w[0]
        if (x >= t[t.size - 1]) return w[w.size - 1]
        var lo = 0
        var hi = t.size - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (t[m] <= x) lo = m else hi = m }
        val f = (x - t[lo]) / (t[lo + 1] - t[lo])
        return w[lo] + (w[lo + 1] - w[lo]) * f
    }

    private fun herm(i: Int, x: Double): Double {
        val h = t[i + 1] - t[i]
        if (h <= 0) return phi[i]
        val s = (x - t[i]) / h
        val s2 = s * s
        val s3 = s2 * s
        return (2 * s3 - 3 * s2 + 1) * phi[i] + (s3 - 2 * s2 + s) * h * w[i] +
            (-2 * s3 + 3 * s2) * phi[i + 1] + (s3 - s2) * h * w[i + 1]
    }

    /**
     * Моменты, когда поворот проходит кратные 60° — границы прорисовок, в пределах
     * [from]…[to] (мкс). Сглаженная кривая монотонна, корень на отрезке единственный.
     */
    fun ticks(from: Double, to: Double): DoubleArray {
        val a = max(from, t0)
        val b = min(to, t1)
        if (b <= a) return DoubleArray(0)
        val pa = phiAt(a)
        val pb = phiAt(b)
        val m0 = ceil(pa / 60.0 - 1e-9).toLong()
        val m1 = floor(pb / 60.0 + 1e-9).toLong()
        if (m1 < m0) return DoubleArray(0)
        val out = DoubleArray((m1 - m0 + 1).toInt())
        var i = 0
        for (k in out.indices) {
            val target = (m0 + k) * 60.0
            while (i < phi.size - 2 && phi[i + 1] < target) i++
            // Бисекция по Эрмиту на [t[i], t[i+1]].
            var lo = t[i]
            var hi = t[i + 1]
            repeat(40) {
                val mid = 0.5 * (lo + hi)
                if (herm(i, mid) < target) lo = mid else hi = mid
            }
            out[k] = 0.5 * (lo + hi)
        }
        return out
    }

    companion object {
        /** Разрыв прогона: дольше этого между событиями — колесо почти стоит. */
        private const val GAP_US = 400_000.0
        private const val HALF_WIN = 9            // событий по каждую сторону (≈1.5 оборота)

        /**
         * Прогоны непрерывного вращения из событий датчиков. [tUs] — мкс по часам
         * телефона, по возрастанию; [sensor] — номер датчика 0..5; [calX100] —
         * калибровка, сотые доли градуса; [armReverse] — настройка «Arm Order».
         */
        fun build(tUs: DoubleArray, sensor: IntArray, calX100: IntArray, armReverse: Boolean): List<RotorModel> {
            val n = tUs.size
            if (n < 8) return emptyList()
            val sgn = if (armReverse) 1.0 else -1.0
            val ang = DoubleArray(n) { i ->
                val k = sensor[i].coerceIn(0, 5)
                wrap360(sgn * 60.0 * k + calX100.getOrElse(k) { 0 } / 100.0)
            }
            val out = ArrayList<RotorModel>()
            var s = 0
            for (i in 1..n) {
                if (i == n || tUs[i] - tUs[i - 1] > GAP_US) {
                    if (i - s >= 8) unwrapRun(tUs, ang, s, i)?.let { out.add(it) }
                    s = i
                }
            }
            return out
        }

        private fun wrap360(a: Double): Double { var x = a % 360.0; if (x < 0) x += 360.0; return x }

        private fun unwrapRun(tUs: DoubleArray, ang: DoubleArray, s: Int, e: Int): RotorModel? {
            // Направление — голосованием по соседним парам, как в ISR.
            var fw = 0; var bw = 0
            for (i in s + 1 until e) {
                val d = wrap360(ang[i] - ang[i - 1])
                if (d in 20.0..100.0) fw++ else if (d in 260.0..340.0) bw++
            }
            val dir = if (fw >= bw) 1 else -1
            val tt = ArrayList<Double>(e - s)
            val pp = ArrayList<Double>(e - s)
            var phi = if (dir > 0) ang[s] else wrap360(-ang[s])
            tt.add(tUs[s]); pp.add(phi)
            var wEst = Double.NaN                      // градусы/мкс
            for (i in s + 1 until e) {
                val d = wrap360(ang[i] - ang[i - 1])
                var step = if (dir > 0) d else wrap360(-d)
                if (step < 10.0) continue                  // дребезг: тот же угол дважды
                val dt = tUs[i] - tUs[i - 1]
                if (!wEst.isNaN()) {
                    // Пропущенные целые обороты: по времени и скорости.
                    val exp = wEst * dt
                    val k = ((exp - step) / 360.0).roundToLong()
                    if (k > 0) step += 360.0 * k
                }
                phi += step
                val wNew = step / dt
                wEst = if (wEst.isNaN()) wNew else 0.7 * wEst + 0.3 * wNew
                tt.add(tUs[i]); pp.add(phi)
            }
            if (tt.size < 8) return null
            val t = tt.toDoubleArray()
            val p = pp.toDoubleArray()
            val sm = DoubleArray(t.size)
            val sw = DoubleArray(t.size)
            smooth(t, p, sm, sw)
            // Сглаженная кривая обязана быть монотонной — иначе обратный поиск 60° врёт.
            for (i in 1 until sm.size) if (sm[i] <= sm[i - 1]) sm[i] = sm[i - 1] + 1e-6
            for (i in sw.indices) if (sw[i] <= 0) sw[i] = 1e-9
            return RotorModel(t, sm, sw, dir)
        }

        /**
         * Локальная парабола по окну ±[HALF_WIN] событий; веса Тьюки по невязке,
         * два прохода. Оценка шума — медиана модулей невязки, не меньше 0.05°.
         */
        private fun smooth(t: DoubleArray, p: DoubleArray, outP: DoubleArray, outW: DoubleArray) {
            val n = t.size
            val wts = DoubleArray(n) { 1.0 }
            for (pass in 0 until 2) {
                val res = DoubleArray(n)
                for (i in 0 until n) {
                    val lo = max(0, i - HALF_WIN)
                    val hi = min(n - 1, i + HALF_WIN)
                    val f = fitQuad(t, p, wts, lo, hi, t[i])
                    outP[i] = f[0]; outW[i] = f[1]
                    res[i] = p[i] - f[0]
                }
                if (pass == 1) break
                val mad = res.map { abs(it) }.sorted()[n / 2]
                val sc = max(0.05, 1.4826 * mad) * 4.685
                for (i in 0 until n) {
                    val u = res[i] / sc
                    wts[i] = if (abs(u) >= 1) 0.0 else (1 - u * u) * (1 - u * u)
                }
            }
        }

        /** Взвешенный МНК для a + b·x + c·x² (x = t − t0, в мкс); возвращает [a, b]. */
        private fun fitQuad(t: DoubleArray, p: DoubleArray, w: DoubleArray, lo: Int, hi: Int, t0: Double): DoubleArray {
            // Масштаб — среднее время между событиями, чтобы матрица была обусловлена.
            val sc = max(1.0, (t[hi] - t[lo]) / max(1, hi - lo))
            var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
            var y0 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (j in lo..hi) {
                val wj = w[j]
                if (wj <= 0) continue
                val x = (t[j] - t0) / sc
                val x2 = x * x
                s0 += wj; s1 += wj * x; s2 += wj * x2; s3 += wj * x2 * x; s4 += wj * x2 * x2
                y0 += wj * p[j]; y1 += wj * p[j] * x; y2 += wj * p[j] * x2
            }
            // Решаем 3×3 (Крамер); вырожденно — прямая.
            val det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2)
            if (abs(det) < 1e-12) {
                val d2 = s0 * s2 - s1 * s1
                if (abs(d2) < 1e-12) return doubleArrayOf(p[(lo + hi) / 2], 0.0)
                val b = (s0 * y1 - s1 * y0) / d2
                val a = (y0 - b * s1) / s0
                return doubleArrayOf(a, b / sc)
            }
            val a = (y0 * (s2 * s4 - s3 * s3) - s1 * (y1 * s4 - s3 * y2) + s2 * (y1 * s3 - s2 * y2)) / det
            val b = (s0 * (y1 * s4 - y2 * s3) - y0 * (s1 * s4 - s3 * s2) + s2 * (s1 * y2 - y1 * s2)) / det
            return doubleArrayOf(a, b / sc)
        }
    }
}
