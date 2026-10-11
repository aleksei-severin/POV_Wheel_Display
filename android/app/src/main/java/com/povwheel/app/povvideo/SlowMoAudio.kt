package com.povwheel.app.povvideo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Где в ролике замедлен звук — так видно slo-mo, «запечённое» при экспорте ([TimeMap]).
 *
 * iOS замедляет звук вместе с картинкой простым растяжением: ×4 переносит всё, что микрофон
 * писал до ~20 кГц, ниже ~5 кГц, и выше этого края в замедленной части нет ничего — на
 * 65–90 дБ тише всего окна. В соседних обычных секундах шум комнаты и микрофона идёт до
 * 16–18 кГц, в тех же полосах там −13…−35 дБ (IMG_9082, IMG_5297: разрыв ≥ 30 дБ даже в
 * тихой комнате). Отсюда разметка: окно «пустое» выше среза или «полное», и ищется отрезок,
 * лучше всего отделяющий пустые окна внутри от полных снаружи. Срез перебирается: ниже края
 * замедленного звука пустых окон нет, слишком высоко — у обычного звука там тоже пусто.
 *
 * Звук только подсказывает, ГДЕ замедление; во сколько раз и где ролик во времени лога,
 * решают гашения ([PovSync.align]) — они же проверяют, что подсказка верна: у постоянного
 * замедления (Samsung объявляет у звука частоту в k раз ниже) пустых окон нет вовсе или
 * пусто всё, и подсказки нет. Край спектра замедленной части ограничивает замедление
 * сверху: исходный звук не выше 24 кГц, ×k кладёт его ниже 24/k кГц.
 *
 * Звук подаётся кусками по мере декодирования ([feed]): держать весь PCM длинного ролика
 * незачем — на окно хранятся только энергии полос по [BAND_HZ].
 */
internal class SlowMoAudio(private val sr: Int) {

    /**
     * Замедленный участок звука: [fromUs] / [toUs] — мкс файла (null — с начала / до конца),
     * край спектра в нём. Видео замедлено чуть шире: см. [LAG_US].
     */
    class Found(val fromUs: Double?, val toUs: Double?, val edgeHz: Double) {
        /** Больше такого замедления звук внутри не допускает (24 кГц / край, с запасом). */
        val maxSlow: Double get() = 24000.0 / edgeHz * 1.1
    }

    private val n = if (sr >= 32000) 2048 else 1024
    private val hop = n / 2
    private val bands = max(1, (sr / 2 / BAND_HZ).toInt())
    private val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
    private val bufL = DoubleArray(n)
    private val bufR = DoubleArray(n)
    private var fill = 0
    private var stereo = false
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private var energy = FloatArray(4096 * bands)
    private var windows = 0

    /** [pcm] — [frames] кадров по [ch] каналов вперемешку, −1..1; берутся первые два канала. */
    fun feed(pcm: FloatArray, frames: Int, ch: Int) {
        if (ch >= 2) stereo = true
        for (f in 0 until frames) {
            bufL[fill] = pcm[f * ch].toDouble()
            bufR[fill] = if (ch >= 2) pcm[f * ch + 1].toDouble() else 0.0
            if (++fill == n) {
                window()
                System.arraycopy(bufL, hop, bufL, 0, n - hop)
                System.arraycopy(bufR, hop, bufR, 0, n - hop)
                fill = n - hop
            }
        }
    }

    /**
     * Окно: оба канала одним комплексным БПФ (L — вещественная часть, R — мнимая), энергии
     * каналов складываются — два микрофона телефона на высоких частотах бывают в противофазе,
     * и сумма сигналов гасила бы именно то, что здесь ищется.
     */
    private fun window() {
        for (i in 0 until n) { re[i] = bufL[i] * win[i]; im[i] = bufR[i] * win[i] }
        fft(re, im)
        if ((windows + 1) * bands > energy.size) energy = energy.copyOf(energy.size * 2)
        val o = windows * bands
        val hz = sr.toDouble() / n
        for (k in 1..n / 2) {
            val nk = (n - k) % n
            val p = if (stereo) {
                // X[k] = L[k] + i·R[k]: L = (X[k] + conj X[n−k]) / 2, R = (X[k] − conj X[n−k]) / 2i
                val lr = (re[k] + re[nk]) / 2; val li = (im[k] - im[nk]) / 2
                val rr = (im[k] + im[nk]) / 2; val ri = (re[nk] - re[k]) / 2
                lr * lr + li * li + rr * rr + ri * ri
            } else re[k] * re[k] + im[k] * im[k]
            val b = min(bands - 1, (k * hz / BAND_HZ).toInt())
            energy[o + b] += p.toFloat()
        }
        windows++
    }

    /**
     * Итог. [startUs] — момент файла (мкс от первого кадра) первого поданного сэмпла,
     * [durUs] — длина ролика. null — замедленного участка звук не показывает.
     */
    fun finish(startUs: Double, durUs: Double): Found? {
        val w = windows
        if (w < 16) return null
        val tot = DoubleArray(w)
        for (i in 0 until w) { var s = 0.0; for (b in 0 until bands) s += energy[i * bands + b]; tot[i] = s }
        val med = tot.sorted()[w / 2]
        if (med <= 0) return null
        val audible = BooleanArray(w) { tot[it] > med * 1e-4 }
        // моменты окон (мкс файла): начало окна и сдвиг между окнами
        val hopUs = hop * 1e6 / sr
        val winUs = n * 1e6 / sr
        fun startOf(i: Int) = startUs + i * hopUs

        var best: Found? = null
        var bestScore = 0.0
        val lab = IntArray(w)
        val pin = DoubleArray(w + 1)
        val pout = DoubleArray(w + 1)
        for (c in 3 until bands) {
            if (c * BAND_HZ > 0.85 * sr / 2) break
            for (i in 0 until w) {
                var hi = 0.0
                for (b in c until bands) hi += energy[i * bands + b]
                val r = hi / tot[i]
                lab[i] = when {
                    !audible[i] -> 0
                    r < EMPTY -> 1
                    r > FULL -> -1
                    else -> 0
                }
            }
            // Отрезок [w1, w2): внутри пустое (+1, полное −4), снаружи полное (+1, пустое −4).
            for (i in 0 until w) {
                pin[i + 1] = pin[i] + when (lab[i]) { 1 -> 1.0; -1 -> -4.0; else -> 0.0 }
                pout[i + 1] = pout[i] + when (lab[i]) { -1 -> 1.0; 1 -> -4.0; else -> 0.0 }
            }
            var bestHead = Double.NEGATIVE_INFINITY
            var headAt = 0
            var sc = Double.NEGATIVE_INFINITY
            var w1 = 0
            var w2 = 0
            for (e in 0..w) {
                val h = pout[e] - pin[e]
                if (h > bestHead) { bestHead = h; headAt = e }
                val s = bestHead + (pin[e] - pout[e]) + pout[w]
                if (s > sc) { sc = s; w1 = headAt; w2 = e }
            }
            val f = check(lab, w1, w2, w, startOf(0), hopUs, winUs, durUs) ?: continue
            if (sc / w > bestScore) {
                val e = edge(w1, w2, audible)
                // Край выше 8 кГц — замедление меньше ×3: так не снимают (slo-mo — 120 и 240 к/с),
                // зато так выглядят обычные ролики, где звук просто стих (GoPro, улица).
                if (e > MAX_EDGE_HZ) continue
                bestScore = sc / w
                best = Found(f.first, f.second, e)
            }
        }
        return best
    }

    /**
     * Годится ли отрезок окон [w1, w2): внутри почти только пустые, снаружи хотя бы с одной
     * стороны есть заметный кусок — и он почти весь полный. Иначе это не slo-mo с обычными
     * краями (пусто везде — звук просто узкополосный или замедлен весь). Возвращает границы,
     * мкс файла: окно с куском обычного звука — уже полное, поэтому переход лежит в
     * последнем шаге перед первым пустым окном (и в первом шаге после последнего).
     */
    private fun check(lab: IntArray, w1: Int, w2: Int, w: Int, t0: Double, hopUs: Double, winUs: Double, durUs: Double): Pair<Double?, Double?>? {
        if (w2 <= w1) return null
        fun count(a: Int, b: Int, v: Int): Int { var c = 0; for (i in a until b) if (lab[i] == v) c++; return c }
        val inE = count(w1, w2, 1); val inF = count(w1, w2, -1)
        if (inE < 0.9 * (inE + inF) || inE + inF < 0.5 * (w2 - w1)) return null
        val from = if (w1 > 0) t0 + w1 * hopUs - hopUs / 2 else null
        val to = if (w2 < w) t0 + (w2 - 1) * hopUs + winUs + hopUs / 2 else null
        val a = from ?: 0.0
        val b = to ?: durUs
        if (b - a < max(MIN_SLOW_US, 0.2 * durUs)) return null
        var sides = 0
        if (from != null && from >= MIN_SIDE_US) {
            val f = count(0, w1, -1); val e = count(0, w1, 1)
            if (f < 3 || f < 0.6 * (f + e)) return null
            sides++
        }
        if (to != null && durUs - to >= MIN_SIDE_US) {
            val f = count(w2, w, -1); val e = count(w2, w, 1)
            if (f < 3 || f < 0.6 * (f + e)) return null
            sides++
        }
        if (sides == 0) return null
        return Pair(from?.takeIf { it >= MIN_SIDE_US }, to?.takeIf { durUs - it >= MIN_SIDE_US })
    }

    /** Край спектра замедленной части: последняя полоса не тише пиковой на 60 дБ. */
    private fun edge(w1: Int, w2: Int, audible: BooleanArray): Double {
        val avg = DoubleArray(bands)
        for (i in w1 until w2) if (audible[i]) for (b in 0 until bands) avg[b] += energy[i * bands + b].toDouble()
        var pk = 0.0
        for (b in 0 until bands) pk = max(pk, avg[b])
        var e = 0
        for (b in 0 until bands) if (avg[b] > pk * 1e-6) e = b
        return (e + 1) * BAND_HZ
    }

    companion object {
        /**
         * У iOS звук переключается на 0.2–0.3 с внутри видео: замедленная картинка начинается
         * раньше замедленного звука и кончается позже (IMG_9082: +0.29 / −0.21 с, IMG_5297:
         * −0.21 с — по гашениям и по фазе лучей). Столько добавляется к звуковым границам с
         * каждой стороны; точнее их ставят гашения ([PovSync.align]), если они есть у краёв.
         */
        const val LAG_US = 250_000.0
        private const val MAX_EDGE_HZ = 8000.0
        private const val BAND_HZ = 500.0
        /** Окно пустое выше среза: там меньше этой доли энергии (−55 дБ); полное — больше −45 дБ. */
        private const val EMPTY = 3.16e-6
        private const val FULL = 3.16e-5
        /** Замедленный участок не короче этого (и пятой части ролика), мкс файла. */
        private const val MIN_SLOW_US = 2e6
        /** Обычный край короче этого — не в счёт (мкс). */
        private const val MIN_SIDE_US = 0.4e6

        /** БПФ на месте, n — степень двойки. */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k; val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
