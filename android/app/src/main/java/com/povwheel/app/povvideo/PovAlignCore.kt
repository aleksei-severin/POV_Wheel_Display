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
 *    мимо, и каждое такое промахнувшееся гашение штрафуется. Проверено на 21 ролике
 *    (Samsung, iPhone, GoPro, Blackmagic; ×1, ×4, ×8): верное замедление в 1.4–3.9 раза
 *    выше лучшего неверного, сдвиг — до кадра (как искать далеко от метаданных — см.
 *    PovSync.align). Днём на улице, с движущейся камеры и с мелким в кадре колесом
 *    провалов не видно: межкадровую разность задаёт фон. Такое совпадение не выделяется
 *    над фоном перебора, и PovSync.align его не засчитывает.
 *    Разность считается только по крупным переменам пикселя (сверх [DIFF_FLOOR]): лучи
 *    меняют пиксель на десятки и сотни уровней, а мерцание комнатного света (100 Гц,
 *    в slo-mo — период в несколько кадров) и шум — на единицы. Со средней |разностью|
 *    мелкое колесо в светлой комнате тонуло в мерцании (IMG_5297: контраст гашений
 *    0.3–1.2 σ против 12–40 σ теперь).
 *
 * 2. ПУЛЬСАЦИЯ ОБЩЕЙ ЯРКОСТИ. Кадр ловит лучи на дуге выдержки, и сумма света в кадре —
 *    функция фазы ротора по модулю 60°. При верной привязке эта зависимость согласована
 *    на всём ролике. Слайдшоу повторяет файлы по кругу, и рисунок гашений повторяется с
 *    периодом цикла — из двух таких двойников верный выбирает эта мера (проверено и на
 *    скоростях 362 против 364 об/мин). Сама по себе, без гашений, сдвиг она не задаёт:
 *    на уличных роликах её случайные пики не слабее настоящих.
 */
object PovAlignCore {

    /** Перемена пикселя меньше этого (уровней из 255) — мерцание света и шум, а не лучи. */
    const val DIFF_FLOOR = 20

    /**
     * Сводка кадров отрезка: метки (мкс файла), средняя яркость и разность с предыдущим
     * кадром — среднее по пикселям превышения |разности| над [DIFF_FLOOR].
     */
    class Stats(
        val ptsUs: LongArray,
        val mean: FloatArray,
        val diff: FloatArray
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
        private val prev = ByteArray(np)

        fun frame(luma: ByteArray, ptsUs: Long) {
            if (n == pts.size) {
                pts = pts.copyOf(n * 2); mean = mean.copyOf(n * 2); diff = diff.copyOf(n * 2)
            }
            var s = 0L
            for (i in 0 until np) s += luma[i].toInt() and 0xFF
            mean[n] = (s.toDouble() / np).toFloat()
            if (n > 0) {
                var d = 0L
                for (i in 0 until np) {
                    val v = abs((luma[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
                    if (v > DIFF_FLOOR) d += v - DIFF_FLOOR
                }
                diff[n] = (d.toDouble() / np).toFloat()
            } else diff[n] = Float.NaN
            pts[n] = ptsUs
            System.arraycopy(luma, 0, prev, 0, np)
            n++
        }

        fun finish(): Stats {
            if (n > 1 && diff[0].isNaN()) diff[0] = diff[1]
            if (n == 1) diff[0] = 0f
            return Stats(pts.copyOf(n), mean.copyOf(n), diff.copyOf(n))
        }
    }

    // ------------------------------------------------------------------ гашения

    /**
     * Оценка гипотезы «кадр j снят в момент anchor + map.real(pts_j) (мкс часов телефона)»
     * по гашениям ленты из лога; [TimeMap] — замедление, постоянное или по участкам. У
     * каждого гашения [a, b] — контраст лог-энергии межкадровой разности: светлые соседи
     * (до [EDGE_US] с каждой стороны) против кадров внутри гашения у его краёв, в долях
     * шума. Вклад гашения — отношение правдоподобия mu·c − mu²/2: совпавшее гашение даёт
     * много, гашение, которого на видео нет, — штраф. Длинное гашение (колесо стояло)
     * считается только у краёв, как и короткое.
     *
     * Одна оценка — O(гашений в ролике · log кадров): суммы по кадрам — разностью префиксных
     * сумм, первое гашение ролика — бинарным поиском. Привязка перебирает десятки тысяч
     * сдвигов (±2 минуты вокруг метаданных на каждое замедление), прямые циклы по кадрам и
     * по всем гашениям сессии на телефоне заняли бы минуты. Моменты кадров на реальной шкале
     * при данной [TimeMap] считаются один раз ([timed]) — сдвиг их только переносит.
     */
    class LitScorer(st: Stats) {
        private val n = st.n
        private val pts = DoubleArray(n) { st.ptsUs[it].toDouble() }
        private val dtf: DoubleArray
        private val y = DoubleArray(n) { ln(max(0f, st.diff[it]) + 1.0) }
        /** ys[j] — сумма y[0 until j]. */
        private val ys = DoubleArray(n + 1)
        private val sig: Double

        init {
            val avg = if (n > 1) (pts[n - 1] - pts[0]) / (n - 1) else 33_333.0
            dtf = DoubleArray(n) { if (it + 1 < n) max(1.0, pts[it + 1] - pts[it]) else avg }
            for (j in 0 until n) ys[j + 1] = ys[j] + y[j]
            val d = DoubleArray(max(0, n - 1)) { abs(y[it + 1] - y[it]) }
            d.sort()
            sig = (if (d.isEmpty()) 0.0 else 1.4826 * d[d.size / 2] / Math.sqrt(2.0)) + 1e-6
        }

        class Score(val value: Double, val events: Int)

        /** Начало и конец каждого кадра на реальной шкале, мкс от первого кадра ролика. */
        class Timed(val map: TimeMap, val t0: DoubleArray, val t1: DoubleArray)

        fun timed(map: TimeMap): Timed =
            Timed(map, DoubleArray(n) { map.real(pts[it]) }, DoubleArray(n) { map.real(pts[it] + dtf[it]) })

        /** [darkA]/[darkB] — начала и концы гашений, мкс часов телефона, по возрастанию. */
        fun score(darkA: DoubleArray, darkB: DoubleArray, tm: Timed, anchor: Double): Score {
            if (n < 2) return Score(0.0, 0)
            val fs = tm.t0
            val fe = tm.t1
            val tFirst = anchor + fs[0]
            val tLast = anchor + fe[n - 1]
            var tot = 0.0
            var cnt = 0
            // первое гашение, которое кончается не раньше начала ролика (+50 мс)
            var lo = 0
            var hi = darkB.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (darkB[m] < tFirst + 50_000) lo = m + 1 else hi = m }
            for (i in lo until darkA.size) {
                val a = darkA[i]; val b = darkB[i]
                if (a > tLast - 50_000) break
                val prevLitStart = if (i > 0) darkB[i - 1] else Double.NEGATIVE_INFINITY
                val nextLitEnd = if (i + 1 < darkA.size) darkA[i + 1] else Double.POSITIVE_INFINITY
                // кадры, пересекающие гашение: t1 > a и t0 < b
                val j0 = firstEndAfter(fe, a - anchor)
                val j1 = firstStartAtOrAfter(fs, b - anchor)
                if (j1 <= j0) continue
                // Считаются кадры у краёв: конец не позже a + EDGE (начало отрезка [j0, jA))
                // или начало не раньше b − EDGE (конец [jB, j1)); у короткого гашения — все.
                val jA = min(j1, firstEndAfter(fe, a + EDGE_US - anchor))
                val jB = max(j0, firstStartAtOrAfter(fs, b - EDGE_US - anchor))
                val jl = j1 - 1
                var m = 0.0; var gmax = 0.0; var yd = 0.0
                // Доля кадра внутри гашения g: кадры между j0 и jl закрыты целиком (g = 1),
                // неполными бывают только крайние.
                fun edge(j: Int) {
                    val t0 = anchor + fs[j]
                    val t1 = anchor + fe[j]
                    val g = max(0.0, min(t1, b) - max(t0, a)) / (t1 - t0)
                    m += g - 1; yd += (g - 1) * y[j]
                    if (g > gmax) gmax = g
                }
                fun take(r0: Int, r1: Int) {
                    if (r1 <= r0) return
                    m += (r1 - r0).toDouble(); yd += ys[r1] - ys[r0]
                    var edges = 0
                    if (j0 in r0 until r1) { edge(j0); edges++ }
                    if (jl != j0 && jl in r0 until r1) { edge(jl); edges++ }
                    if (r1 - r0 > edges) gmax = 1.0
                }
                if (jA >= jB) take(j0, j1) else { take(j0, jA); take(jB, j1) }
                if (m < 0.25) continue
                yd /= m
                // светлые соседи: кадры целиком в светлом промежутке у краёв гашения
                val ja = firstStartAtOrAfter(fs, max(a - EDGE_US, prevLitStart) - anchor)
                val jb = firstEndAfter(fe, min(b + EDGE_US, nextLitEnd) - anchor)
                val lc = max(0, j0 - ja) + max(0, jb - j1)
                if (lc < 2) continue
                val ls = (if (j0 > ja) ys[j0] - ys[ja] else 0.0) + (if (jb > j1) ys[jb] - ys[j1] else 0.0)
                val c = (ls / lc - yd) / sig
                val mu = MU * min(1.0, gmax)
                tot += mu * c - mu * mu / 2
                cnt++
            }
            return Score(tot, cnt)
        }

        /** Первый кадр, чей конец ([fe], реальная шкала) позже [t]. */
        private fun firstEndAfter(fe: DoubleArray, t: Double): Int {
            var lo = 0; var hi = n
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (fe[m] > t) hi = m else lo = m + 1
            }
            return lo
        }

        /** Первый кадр, чьё начало ([fs]) не раньше [t]. */
        private fun firstStartAtOrAfter(fs: DoubleArray, t: Double): Int {
            var lo = 0; var hi = n
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (fs[m] >= t) hi = m else lo = m + 1
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
}
