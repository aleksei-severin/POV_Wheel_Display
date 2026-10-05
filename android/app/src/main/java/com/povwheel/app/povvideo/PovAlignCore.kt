package com.povwheel.app.povvideo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Точная привязка ролика ко времени лога Холла — по самому видео.
 *
 * Метаданные (имя файла, время создания) называют начало записи с точностью до секунды,
 * а склейке на разгоне и торможении нужно ±10 мс. Добираем по картинке: яркость каждой
 * точки диска пульсирует с проходом лучей, то есть с фазой 6·θ(t), где θ(t) — угол
 * ротора, известный из лога. Перебираем сдвиг ролика относительно лога и ищем тот, при
 * котором пульсации видео согласуются с фазой из лога дольше всего.
 *
 * ЧТО ИЗМЕРЯЕТСЯ И ПОЧЕМУ ЭТОГО ДОСТАТОЧНО. Постоянная скорость сдвигает фазу на
 * константу — согласованность от сдвига не зависит, но и склейке он тогда безразличен
 * (окну нужна длительность прорисовки, а не её фаза). Чем сильнее менялась скорость,
 * тем резче пик — ровно там, где точность и нужна. Пик без выраженного максимума — честный
 * ответ «скорость почти не менялась», и анализатор тогда остаётся на привязке по
 * метаданным.
 *
 * ПОЧЕМУ ПОЛЯРНЫЕ ЯЧЕЙКИ, А НЕ БЛОКИ КАДРА. Камера в руке, колесо гуляет по кадру, и
 * фиксированный блок изображения за секунду видит уже другой кусок диска. Поэтому центр
 * колеса ищется по «энергии мерцания» на каждом полусекундном отрезке, а яркость
 * собирается в ячейки по углу и радиусу вокруг него — их фаза от сдвига камеры не зависит.
 * Проверено на записях с чирпами (POV-BlendFPS.ps1 даёт эталонные тики): с блоками кадра
 * пик был едва выше фона, с полярными ячейками — вдвое-втрое, и сдвиг по половинам ролика
 * сходится до миллисекунды.
 */
object PovAlignCore {

    const val NA = 48            // ячеек по углу
    const val NR = 3             // колец: 0.15–0.5, 0.5–0.85, 0.85–1.2 радиуса
    const val NB = NA * NR
    private const val CENTER_SEG_SEC = 0.5
    private const val COH_SEG_SEC = 0.25
    private const val COH_WIN = 16          // ±4 с: дальше камера в руке уводит фазу ячеек
    private val HARM = intArrayOf(1, 2, 3)

    /**
     * Накопитель кадров: яркость уменьшенного кадра [w]×[h] → ячейки вокруг колеса.
     * Кадры приходят по порядку; центр считается на каждом полусекундном отрезке по
     * межкадровой разности (там, где проходят лучи, она велика, а у медленно
     * сдвигающегося фона — мала).
     */
    class Binner(private val w: Int, private val h: Int) {
        /** Отрезок: его кадры (пока не разложены по ячейкам) и найденный центр колеса. */
        private class Seg(var frames: ArrayList<ByteArray>?, val pts: LongArray,
                          val cx: Float, val cy: Float, val r: Float, val tMid: Double)

        private val cur = ArrayList<ByteArray>()
        private val curPts = ArrayList<Long>()
        private var prevLast: ByteArray? = null
        private val pool = ArrayList<ByteArray>()
        private val segs = ArrayList<Seg>()
        private var nextToBin = 0
        private var rows = FloatArray(1 shl 16)
        private val ptsOut = ArrayList<Long>()
        private var n = 0
        private val energy = FloatArray(w * h)

        fun frame(luma: ByteArray, ptsUs: Long) {
            if (curPts.isNotEmpty() && ptsUs - curPts[0] >= CENTER_SEG_SEC * 1e6) closeSeg()
            val b = if (pool.isNotEmpty()) pool.removeAt(pool.size - 1) else ByteArray(w * h)
            System.arraycopy(luma, 0, b, 0, w * h)
            cur.add(b); curPts.add(ptsUs)
        }

        fun finish(): Binned {
            closeSeg()
            while (nextToBin < segs.size) binSeg(nextToBin++)
            return Binned(rows.copyOf(n * NB), LongArray(n) { ptsOut[it] }, n)
        }

        /** Отрезок набран: центр по межкадровой разности. Раскладывать по ячейкам его
         *  будем, когда станет известен центр СЛЕДУЮЩЕГО — между ними центр
         *  интерполируется: камера в руке за полсекунды уводит колесо заметно, и с
         *  постоянным центром на отрезок пик согласованности был втрое ниже. */
        private fun closeSeg() {
            if (cur.isEmpty()) return
            java.util.Arrays.fill(energy, 0f)
            var prev = prevLast
            for (f in cur) {
                if (prev != null) for (i in 0 until w * h) {
                    val d = ((f[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)).toFloat()
                    energy[i] += d * d
                }
                prev = f
            }
            val c = centerOf()
            val p = LongArray(curPts.size) { curPts[it] }
            segs.add(Seg(ArrayList(cur), p, c[0], c[1], c[2], (p.first() + p.last()) / 2.0))
            // Последний кадр — опора разности следующего отрезка; сам он остаётся в
            // кадрах отрезка, так что держим ссылку, а не копию.
            prevLast = cur[cur.size - 1]
            cur.clear(); curPts.clear()
            while (nextToBin < segs.size - 1) binSeg(nextToBin++)
        }

        private fun binSeg(s: Int) {
            val sg = segs[s]
            val frames = sg.frames ?: return
            // Радиус — среднее по соседним отрезкам: по одному отрезку он шумит.
            var rs = 0f; var rc = 0
            for (k in max(0, s - 2)..min(segs.size - 1, s + 1)) { rs += segs[k].r; rc++ }
            val r = rs / rc
            // Четыре карты ячеек на отрезок, центр — линейно между серединами соседей.
            val q = 4
            val maps = Array(q) { qi ->
                val tq = sg.pts.first() + (sg.pts.last() - sg.pts.first()) * (qi + 0.5) / q
                val c = centerAt(s, tq)
                binMap(c[0], c[1], r)
            }
            val cnts = Array(q) { qi -> IntArray(NB).also { cnt -> for (b in maps[qi]) if (b >= 0) cnt[b]++ } }
            val span = max(1L, sg.pts.last() - sg.pts.first())
            for (k in frames.indices) {
                if ((n + 1) * NB > rows.size) rows = rows.copyOf(rows.size * 2)
                val qi = (((sg.pts[k] - sg.pts.first()).toDouble() / span) * q).toInt().coerceIn(0, q - 1)
                val map = maps[qi]; val cnt = cnts[qi]
                val f = frames[k]
                val base = n * NB
                for (i in 0 until w * h) {
                    val b = map[i]
                    if (b >= 0) rows[base + b] += (f[i].toInt() and 0xFF).toFloat()
                }
                for (b in 0 until NB) if (cnt[b] > 0) rows[base + b] /= cnt[b].toFloat()
                ptsOut.add(sg.pts[k]); n++
            }
            for (f in frames) if (f !== prevLast) pool.add(f)
            sg.frames = null
        }

        private fun centerAt(s: Int, t: Double): FloatArray {
            val a: Seg; val b: Seg
            if (t < segs[s].tMid) {
                if (s == 0) return floatArrayOf(segs[s].cx, segs[s].cy)
                a = segs[s - 1]; b = segs[s]
            } else {
                if (s + 1 >= segs.size) return floatArrayOf(segs[s].cx, segs[s].cy)
                a = segs[s]; b = segs[s + 1]
            }
            val f = ((t - a.tMid) / max(1.0, b.tMid - a.tMid)).toFloat().coerceIn(0f, 1f)
            return floatArrayOf(a.cx + (b.cx - a.cx) * f, a.cy + (b.cy - a.cy) * f)
        }

        /** Центр — по самым «мерцающим» точкам (верхний процент), радиус — 90-й процентиль
         *  их удаления среди верхних пяти процентов. */
        private fun centerOf(): FloatArray {
            val t99 = percentile(energy, 0.99f)
            var sw = 0.0; var sx = 0.0; var sy = 0.0
            for (y in 0 until h) for (x in 0 until w) {
                val e = energy[y * w + x]
                if (e > t99) { sw += e; sx += e * x; sy += e * y }
            }
            if (sw <= 0) return floatArrayOf(w / 2f, h / 2f, min(w, h) / 3f)
            val cx = (sx / sw).toFloat(); val cy = (sy / sw).toFloat()
            val t95 = percentile(energy, 0.95f)
            val ds = ArrayList<Float>()
            for (y in 0 until h) for (x in 0 until w) {
                if (energy[y * w + x] > t95) { val dx = x - cx; val dy = y - cy; ds.add(sqrt(dx * dx + dy * dy)) }
            }
            ds.sort()
            val r = if (ds.isEmpty()) min(w, h) / 3f else max(4f, ds[(ds.size * 0.9).toInt().coerceAtMost(ds.size - 1)])
            return floatArrayOf(cx, cy, r)
        }

        private fun binMap(cx: Float, cy: Float, r: Float): IntArray {
            val m = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val dx = x - cx; val dy = y - cy
                val rr = sqrt(dx * dx + dy * dy) / r
                val ring = floor((rr - 0.15f) / 0.35f).toInt()
                if (ring < 0 || ring >= NR) { m[y * w + x] = -1; continue }
                val a = ((atan2(dy.toDouble(), dx.toDouble()) / (2 * PI) + 0.5) * NA).toInt().coerceIn(0, NA - 1)
                m[y * w + x] = ring * NA + a
            }
            return m
        }

        private fun percentile(a: FloatArray, q: Float): Float {
            var mx = 0f
            for (v in a) if (v > mx) mx = v
            if (mx <= 0f) return 0f
            val hist = IntArray(4096)
            for (v in a) hist[((v / mx) * 4095).toInt().coerceIn(0, 4095)]++
            val want = (a.size * q).toInt()
            var acc = 0
            for (i in hist.indices) { acc += hist[i]; if (acc >= want) return (i + 1) / 4096f * mx }
            return mx
        }
    }

    /** Ячейки по кадрам: [x] — n × [NB], [ptsUs] — метки кадров в файле. */
    class Binned(val x: FloatArray, val ptsUs: LongArray, val n: Int)

    /**
     * Мера согласованности для разных сдвигов. Строится один раз по ячейкам; дальше
     * [coherence] считает её для готового угла ротора в каждом кадре.
     */
    class Scorer(b: Binned, fileFps: Double) {
        private val n = b.n
        private val x: FloatArray = b.x.copyOf()
        /** Метки кадров, мкс файла. */
        val pts: DoubleArray = DoubleArray(n) { b.ptsUs[it].toDouble() }
        private val segOf = IntArray(n)
        private val nSeg: Int
        /** Энергия мерцания по кадрам — для привязки по моментам «зажглась/погасла». */
        val energy = DoubleArray(n)

        init {
            // Фильтр верхних частот по каждой ячейке: минус скользящее среднее ~0.1 с.
            val k = max(1, (fileFps * 0.05).toInt())
            val col = DoubleArray(n)
            val cs = DoubleArray(n + 1)
            for (bi in 0 until NB) {
                for (j in 0 until n) col[j] = x[j * NB + bi].toDouble()
                cs[0] = 0.0
                for (j in 0 until n) cs[j + 1] = cs[j] + col[j]
                for (j in 0 until n) {
                    val lo = max(0, j - k); val hi = min(n, j + k + 1)
                    x[j * NB + bi] = (col[j] - (cs[hi] - cs[lo]) / (hi - lo)).toFloat()
                }
            }
            // Грубое выравнивание вклада: отсечка по 99.5-му процентилю модуля.
            val abs = FloatArray(x.size) { abs(x[it]) }
            abs.sort()
            val lim = if (abs.isEmpty()) 0f else abs[((abs.size - 1) * 0.995).toInt()]
            if (lim > 0) for (i in x.indices) x[i] = x[i].coerceIn(-lim, lim)
            var s = 0
            val t0 = if (n > 0) pts[0] else 0.0
            for (j in 0 until n) {
                s = ((pts[j] - t0) / (COH_SEG_SEC * 1e6)).toInt()
                segOf[j] = s
                var e = 0.0
                for (bi in 0 until NB) { val v = x[j * NB + bi].toDouble(); e += v * v }
                energy[j] = e
            }
            nSeg = s + 1
        }

        private val zr = DoubleArray(nSeg * NB * HARM.size)
        private val zi = DoubleArray(nSeg * NB * HARM.size)

        /**
         * Согласованность пульсаций с фазой ротора [phi] (градусы, по кадрам; NaN —
         * кадр вне лога). 1 — нет согласованности между отрезками, больше — есть.
         */
        fun coherence(phi: DoubleArray): Double {
            java.util.Arrays.fill(zr, 0.0)
            java.util.Arrays.fill(zi, 0.0)
            val nh = HARM.size
            var used = 0
            for (j in 0 until n) {
                val p = phi[j]
                if (p.isNaN()) continue
                used++
                val a = -2 * PI * p / 60.0
                val c1 = cos(a); val s1 = sin(a)
                val c2 = c1 * c1 - s1 * s1; val s2 = 2 * c1 * s1
                val c3 = c2 * c1 - s2 * s1; val s3 = c2 * s1 + s2 * c1
                val base = segOf[j] * NB * nh
                val xb = j * NB
                for (bi in 0 until NB) {
                    val v = x[xb + bi].toDouble()
                    if (v == 0.0) continue
                    val o = base + bi * nh
                    zr[o] += v * c1; zi[o] += v * s1
                    zr[o + 1] += v * c2; zi[o + 1] += v * s2
                    zr[o + 2] += v * c3; zi[o + 2] += v * s3
                }
            }
            if (used < 16) return 0.0
            // Σ_s Re(Z_s · conj(Σ_{|s'−s|≤W} Z_s')) / Σ_s |Z_s|²
            val stride = NB * nh
            var num = 0.0; var den = 0.0
            val pr = DoubleArray(nSeg + 1); val pi = DoubleArray(nSeg + 1)
            for (q in 0 until stride) {
                pr[0] = 0.0; pi[0] = 0.0
                for (s in 0 until nSeg) { pr[s + 1] = pr[s] + zr[s * stride + q]; pi[s + 1] = pi[s] + zi[s * stride + q] }
                for (s in 0 until nSeg) {
                    val r = zr[s * stride + q]; val i = zi[s * stride + q]
                    if (r == 0.0 && i == 0.0) continue
                    val lo = max(0, s - COH_WIN); val hi = min(nSeg, s + COH_WIN + 1)
                    val yr = pr[hi] - pr[lo]; val yi = pi[hi] - pi[lo]
                    num += r * yr + i * yi
                    den += r * r + i * i
                }
            }
            return if (den > 0) num / den else 0.0
        }

        /**
         * Корреляция энергии мерцания с тем, светилась ли лента ([lit] по кадрам: 1/0, NaN —
         * неизвестно). Даёт привязку до кадра, если в ролик попало включение или гашение.
         */
        fun litScore(lit: DoubleArray): Double {
            var n0 = 0; var se = 0.0; var sl = 0.0
            for (j in 0 until n) if (!lit[j].isNaN()) { n0++; se += energy[j]; sl += lit[j] }
            if (n0 < 16) return 0.0
            val me = se / n0; val ml = sl / n0
            var sel = 0.0; var see = 0.0; var sll = 0.0
            for (j in 0 until n) if (!lit[j].isNaN()) {
                val de = energy[j] - me; val dl = lit[j] - ml
                sel += de * dl; see += de * de; sll += dl * dl
            }
            return if (see > 0 && sll > 0) sel / sqrt(see * sll) else 0.0
        }
    }

    /** Результат перебора сдвига: лучшая точка, её мера и выраженность пика. */
    class Peak(val at: Double, val value: Double, val median: Double, val second: Double, val lobeLo: Double, val lobeHi: Double) {
        /** Во сколько раз пик выше лучшего постороннего максимума (над медианой). */
        val prominence: Double get() = if (second > median) (value - median) / (second - median) else 99.0
    }

    /** Максимум [ys] на сетке [xs] с параболическим уточнением, полушириной лепестка и вторым максимумом вне его. */
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
        return Peak(at, ys[i], med, sec, xs[j0], xs[j1])
    }
}
