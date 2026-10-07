package com.povwheel.app.povvideo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Привязка ролика ко времени лога Холла — по самому видео, но без машинного зрения:
 * ни центра колеса, ни ячеек вокруг него. Только числа на кадр, которые от положения
 * колеса в кадре не зависят.
 *
 * 1. ГАШЕНИЯ ДИСПЛЕЯ. Слайдшоу гасит ленту на время загрузки файла (от миллисекунд до
 *    полсекунды), колесо гаснет на разгоне и торможении — лог знает каждый такой момент
 *    до микросекунды, а на видео он виден как провал энергии межкадровой разности (лучи
 *    перестают бегать по кадру). Рисунок провалов разной длины однозначно задаёт и сдвиг,
 *    и замедление slow motion: при неверном замедлении предсказанные гашения ложатся
 *    мимо, и каждое такое промахнувшееся гашение штрафуется. Проверено на 14 роликах
 *    (Samsung, iPhone, GoPro, Blackmagic; ×1, ×4, ×8): верная гипотеза в 1.1–3.9 раза
 *    выше лучшей неверной, сдвиг — до кадра.
 *
 * 2. ПУЛЬСАЦИЯ ОБЩЕЙ ЯРКОСТИ. Кадр ловит лучи на дуге выдержки, и сумма света в кадре —
 *    функция фазы ротора по модулю 60°. При верной привязке эта зависимость согласована
 *    на всём ролике. Слайдшоу повторяет файлы по кругу, и рисунок гашений повторяется с
 *    периодом цикла — из двух таких двойников верный выбирает эта мера (проверено и на
 *    скоростях 362 против 364 об/мин). Если гашений в ролике нет вовсе, она же уточняет
 *    сдвиг — когда скорость менялась; при ровной скорости сдвиг склейке и не важен.
 *
 * 3. ВЫДЕРЖКА. Окна по [WIN] кадров: свет каждого кадра над фоном окна (поточечный
 *    минимум) против света их общей склейки. Если фазы кадров окна плотно закрывают
 *    круг, отношение — доля 60°, которую ловит один кадр. Размытие и ореол свет не
 *    добавляют, поэтому отношение от них почти не зависит (насыщение белого его чуть
 *    завышает — запас на это закладывает планировщик склейки).
 */
object PovAlignCore {

    /** Кадров в окне оценки выдержки. */
    const val WIN = 24
    /** Отсечка шума (уровни яркости 0..255) при подсчёте света над фоном. */
    private const val THR = 10

    /**
     * Сводка кадров отрезка: метки (мкс файла), средняя яркость, средняя |разность| с
     * предыдущим кадром; для окон выдержки — начало окна и доля света каждого кадра окна
     * от их общей склейки (NaN — окно без света).
     */
    class Stats(
        val ptsUs: LongArray,
        val mean: FloatArray,
        val diff: FloatArray,
        val winStart: IntArray,
        val winRatio: FloatArray
    ) {
        val n: Int get() = ptsUs.size
    }

    /** Накопитель: кадры приходят по порядку, яркость уменьшенного кадра [w]×[h]. */
    class Collector(private val w: Int, private val h: Int) {
        private val np = w * h
        private var pts = LongArray(4096)
        private var mean = FloatArray(4096)
        private var diff = FloatArray(4096)
        private var n = 0
        private var prev: ByteArray? = null
        private val win = Array(WIN) { ByteArray(np) }
        private var inWin = 0
        private val bg = IntArray(np)
        private val mx = IntArray(np)
        private val winStart = ArrayList<Int>()
        private var ratio = FloatArray(4096)

        fun frame(luma: ByteArray, ptsUs: Long) {
            if (n == pts.size) {
                pts = pts.copyOf(n * 2); mean = mean.copyOf(n * 2); diff = diff.copyOf(n * 2); ratio = ratio.copyOf(n * 2)
            }
            var s = 0L
            for (i in 0 until np) s += luma[i].toInt() and 0xFF
            mean[n] = (s.toDouble() / np).toFloat()
            val p = prev
            if (p != null) {
                var d = 0L
                for (i in 0 until np) d += abs((luma[i].toInt() and 0xFF) - (p[i].toInt() and 0xFF))
                diff[n] = (d.toDouble() / np).toFloat()
            } else diff[n] = Float.NaN
            pts[n] = ptsUs
            ratio[n] = Float.NaN
            val slot = win[inWin]
            System.arraycopy(luma, 0, slot, 0, np)
            prev = slot
            n++
            if (++inWin == WIN) closeWindow()
        }

        fun finish(): Stats {
            if (n > 1 && diff[0].isNaN()) diff[0] = diff[1]
            if (n == 1) diff[0] = 0f
            return Stats(pts.copyOf(n), mean.copyOf(n), diff.copyOf(n), winStart.toIntArray(), ratio.copyOf(n))
        }

        /** Окно набрано: фон и склейка поточечно, свет каждого кадра против света склейки. */
        private fun closeWindow() {
            java.util.Arrays.fill(bg, 255)
            java.util.Arrays.fill(mx, 0)
            for (f in win) for (i in 0 until np) {
                val v = f[i].toInt() and 0xFF
                if (v < bg[i]) bg[i] = v
                if (v > mx[i]) mx[i] = v
            }
            var u = 0L
            for (i in 0 until np) u += max(0, mx[i] - bg[i] - THR)
            val first = n - WIN
            winStart.add(first)
            if (u > 0) for (k in 0 until WIN) {
                val f = win[k]
                var e = 0L
                for (i in 0 until np) e += max(0, (f[i].toInt() and 0xFF) - bg[i] - THR)
                ratio[first + k] = (e.toDouble() / u).toFloat()
            }
            // Последний кадр окна — опора разности следующего кадра: копия, слоты сейчас перезапишутся.
            val keep = win[WIN - 1].copyOf()
            prev = keep
            inWin = 0
        }
    }

    // ------------------------------------------------------------------ гашения

    /**
     * Оценка гипотезы «кадр j снят в момент anchor + pts_j/slow (мкс часов телефона)» по
     * гашениям ленты из лога. У каждого гашения [a, b] — контраст лог-энергии межкадровой
     * разности: светлые соседи (до [EDGE_US] с каждой стороны) против кадров внутри
     * гашения у его краёв, в долях шума. Вклад гашения — отношение правдоподобия
     * mu·c − mu²/2: совпавшее гашение даёт много, гашение, которого на видео нет, — штраф.
     * Длинное гашение (колесо стояло) считается только у краёв, как и короткое.
     */
    class LitScorer(st: Stats) {
        private val n = st.n
        private val pts = DoubleArray(n) { st.ptsUs[it].toDouble() }
        private val dtf: DoubleArray
        private val y = DoubleArray(n) { ln(max(0f, st.diff[it]) + 1.0) }
        private val sig: Double

        init {
            val avg = if (n > 1) (pts[n - 1] - pts[0]) / (n - 1) else 33_333.0
            dtf = DoubleArray(n) { if (it + 1 < n) max(1.0, pts[it + 1] - pts[it]) else avg }
            val d = DoubleArray(max(0, n - 1)) { abs(y[it + 1] - y[it]) }
            d.sort()
            sig = (if (d.isEmpty()) 0.0 else 1.4826 * d[d.size / 2] / Math.sqrt(2.0)) + 1e-6
        }

        class Score(val value: Double, val events: Int)

        /** [darkA]/[darkB] — начала и концы гашений, мкс часов телефона, по возрастанию. */
        fun score(darkA: DoubleArray, darkB: DoubleArray, slow: Double, anchor: Double): Score {
            if (n < 2) return Score(0.0, 0)
            val tFirst = anchor + pts[0] / slow
            val tLast = anchor + (pts[n - 1] + dtf[n - 1]) / slow
            var tot = 0.0
            var cnt = 0
            for (i in darkA.indices) {
                val a = darkA[i]; val b = darkB[i]
                if (b < tFirst + 50_000 || a > tLast - 50_000) continue
                val prevLitStart = if (i > 0) darkB[i - 1] else Double.NEGATIVE_INFINITY
                val nextLitEnd = if (i + 1 < darkA.size) darkA[i + 1] else Double.POSITIVE_INFINITY
                // кадры, пересекающие гашение: t1 > a и t0 < b
                val j0 = firstEndAfter(a, slow, anchor)
                val j1 = firstStartAtOrAfter(b, slow, anchor)
                if (j1 <= j0) continue
                var m = 0.0; var gmax = 0.0; var yd = 0.0
                for (j in j0 until j1) {
                    val t0 = anchor + pts[j] / slow
                    val t1 = t0 + dtf[j] / slow
                    if (!(t1 <= a + EDGE_US || t0 >= b - EDGE_US)) continue
                    val g = max(0.0, min(t1, b) - max(t0, a)) / (t1 - t0)
                    m += g; yd += g * y[j]; if (g > gmax) gmax = g
                }
                if (m < 0.25) continue
                yd /= m
                // светлые соседи: целиком в светлом промежутке у краёв гашения
                var ls = 0.0; var lc = 0
                val loA = max(a - EDGE_US, prevLitStart)
                var j = firstStartAtOrAfter(loA, slow, anchor)
                while (j < n) {
                    val t0 = anchor + pts[j] / slow
                    if (t0 + dtf[j] / slow > a) break
                    ls += y[j]; lc++; j++
                }
                val hiB = min(b + EDGE_US, nextLitEnd)
                j = firstStartAtOrAfter(b, slow, anchor)
                while (j < n) {
                    val t0 = anchor + pts[j] / slow
                    if (t0 + dtf[j] / slow > hiB) break
                    ls += y[j]; lc++; j++
                }
                if (lc < 2) continue
                val c = (ls / lc - yd) / sig
                val mu = MU * min(1.0, gmax)
                tot += mu * c - mu * mu / 2
                cnt++
            }
            return Score(tot, cnt)
        }

        /** Первый кадр, чей конец позже [t]. */
        private fun firstEndAfter(t: Double, slow: Double, anchor: Double): Int {
            var lo = 0; var hi = n
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (anchor + (pts[m] + dtf[m]) / slow > t) hi = m else lo = m + 1
            }
            return lo
        }

        /** Первый кадр, чьё начало не раньше [t]. */
        private fun firstStartAtOrAfter(t: Double, slow: Double, anchor: Double): Int {
            var lo = 0; var hi = n
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (anchor + pts[m] / slow >= t) hi = m else lo = m + 1
            }
            return lo
        }

        private companion object {
            const val EDGE_US = 400_000.0
            const val MU = 3.0
        }
    }

    // ------------------------------------------------------------------ пульсация общей яркости

    /**
     * Согласованность средней яркости кадра с фазой ротора: Σ Re(Z_s·conj(ΣZ соседних
     * отрезков)) / Σ|Z_s|², где Z_s — сумма x·e^(−i·h·2π·φ/60) по кадрам отрезка, h = 1..3.
     * Больше единицы — пульсации идут в такт с ротором; около единицы и ниже — нет.
     */
    class Coherence(st: Stats, fileFps: Double) {
        private val n = st.n
        private val x = DoubleArray(n)
        private val segOf = IntArray(n)
        private val nSeg: Int

        init {
            // минус скользящее среднее ~0.1 с: остаётся мерцание лучей, уходят свет в комнате и движение камеры
            val k = max(1, (fileFps * 0.05).toInt())
            val cs = DoubleArray(n + 1)
            for (j in 0 until n) cs[j + 1] = cs[j] + st.mean[j]
            for (j in 0 until n) {
                val lo = max(0, j - k); val hi = min(n, j + k + 1)
                x[j] = st.mean[j] - (cs[hi] - cs[lo]) / (hi - lo)
            }
            val a = DoubleArray(n) { abs(x[it]) }
            a.sort()
            val lim = if (n == 0) 0.0 else a[((n - 1) * 0.995).toInt()]
            if (lim > 0) for (j in 0 until n) x[j] = x[j].coerceIn(-lim, lim)
            val t0 = if (n > 0) st.ptsUs[0] else 0L
            var s = 0
            for (j in 0 until n) { s = ((st.ptsUs[j] - t0) / (SEG_SEC * 1e6)).toInt(); segOf[j] = s }
            nSeg = s + 1
        }

        private val zr = DoubleArray(nSeg * 3)
        private val zi = DoubleArray(nSeg * 3)
        private val pr = DoubleArray(nSeg + 1)
        private val pi = DoubleArray(nSeg + 1)

        /** [phi] — угол ротора в градусах по кадрам (NaN — кадр вне лога). */
        fun value(phi: DoubleArray): Double {
            java.util.Arrays.fill(zr, 0.0)
            java.util.Arrays.fill(zi, 0.0)
            var used = 0
            for (j in 0 until n) {
                val p = phi[j]
                if (p.isNaN()) continue
                used++
                val ang = -2 * PI * p / 60.0
                val c1 = cos(ang); val s1 = sin(ang)
                val c2 = c1 * c1 - s1 * s1; val s2 = 2 * c1 * s1
                val c3 = c2 * c1 - s2 * s1; val s3 = c2 * s1 + s2 * c1
                val o = segOf[j] * 3
                val v = x[j]
                zr[o] += v * c1; zi[o] += v * s1
                zr[o + 1] += v * c2; zi[o + 1] += v * s2
                zr[o + 2] += v * c3; zi[o + 2] += v * s3
            }
            if (used < 16) return 0.0
            var num = 0.0; var den = 0.0
            for (h in 0 until 3) {
                pr[0] = 0.0; pi[0] = 0.0
                for (s in 0 until nSeg) { pr[s + 1] = pr[s] + zr[s * 3 + h]; pi[s + 1] = pi[s] + zi[s * 3 + h] }
                for (s in 0 until nSeg) {
                    val r = zr[s * 3 + h]; val i = zi[s * 3 + h]
                    if (r == 0.0 && i == 0.0) continue
                    val lo = max(0, s - COH_WIN); val hi = min(nSeg, s + COH_WIN + 1)
                    num += r * (pr[hi] - pr[lo]) + i * (pi[hi] - pi[lo])
                    den += r * r + i * i
                }
            }
            return if (den > 0) num / den else 0.0
        }

        private companion object {
            const val SEG_SEC = 0.25
            const val COH_WIN = 8
        }
    }

    /** Результат перебора сдвига: лучшая точка, её мера и выраженность пика. */
    class Peak(val at: Double, val value: Double, val median: Double, val second: Double) {
        /** Во сколько раз пик выше лучшего постороннего максимума (над медианой). */
        val prominence: Double get() = if (second > median) (value - median) / (second - median) else 99.0
        /** Насколько пик выше фона, в долях превышения фона над единицей. */
        val excess: Double get() = if (median > 1.0) (value - median) / (median - 1.0) else value - median
    }

    /** Максимум [ys] на сетке [xs] с параболическим уточнением и вторым максимумом вне лепестка. */
    fun peakOf(xs: DoubleArray, ys: DoubleArray): Peak {
        var i = 0
        for (k in ys.indices) if (ys[k] > ys[i]) i = k
        val med = ys.sorted()[ys.size / 2]
        val half = (ys[i] + med) / 2
        var j0 = i; while (j0 > 0 && ys[j0] > half) j0--
        var j1 = i; while (j1 < ys.size - 1 && ys[j1] > half) j1++
        var sec = med
        for (k in ys.indices) if ((k < j0 - 1 || k > j1 + 1) && ys[k] > sec) sec = ys[k]
        var at = xs[i]
        if (i > 0 && i < ys.size - 1) {
            val y0 = ys[i - 1]; val y1 = ys[i]; val y2 = ys[i + 1]
            val d = y0 - 2 * y1 + y2
            if (d < 0) at += 0.5 * (y0 - y2) / d * (xs[i + 1] - xs[i])
        }
        return Peak(at, ys[i], med, sec)
    }
}
