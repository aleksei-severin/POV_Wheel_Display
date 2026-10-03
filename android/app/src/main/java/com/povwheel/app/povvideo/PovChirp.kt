package com.povwheel.app.povvideo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Участок отрисовки: времена тиков (равномерная по углу сетка, с) и признак, найден ли тик
 * на записи (false — достроен). [dir] — направление свипа: +1 вверх, −1 вниз (заднее колесо
 * или вращение назад).
 */
class TickTrack(val times: DoubleArray, val real: BooleanArray, val dir: Int = 1)

/**
 * Детектор чирпов синхро-датчика — перенос `PovChirp` из tools/pov_fps_blend/POV-BlendFPS.ps1
 * (C#) строка в строку; правки делать в обоих. Почему он устроен именно так — в комментарии
 * перед `$PovSource` в скрипте и у каждой функции здесь.
 *
 * Прошивка (include/config.h, PIEZO_CHIRP_*) на каждое событие Холла подаёт на пьезо
 * линейный чирп [fLo] → [fHi] за [dur] с плавными краями [fade]; при rotation_dir < 0 — вниз.
 */
internal class PovChirp(
    private val fLo: Double = 15000.0,
    private val fHi: Double = 20000.0,
    private val dur: Double = 0.015,
    private val fade: Double = 0.0015
) {
    companion object {
        const val SEG = 5                    // кусков шаблона (полос по (fHi−fLo)/SEG)
        const val SMOOTH_STAT_SEC = 0.004    // сглаживание статистики (прямой звук + эхо)
        const val NMS_SEC = 0.005            // радиус подавления соседних пиков
        const val EV_REF = 2.5               // уровень статистики, при котором тик «нейтрален» для трекера
        const val OPP_THR = 5.0              // обратный отклик выше этого — вычитается его доля OPP_K
        const val OPP_K = 0.5
        const val CLICK_LO = 6000.0
        const val CLICK_HI = 13000.0
        const val CLICK_THR = 4.0
        const val MISS_PEN = 0.4             // штраф за пропущенный тик
        const val START_PEN = 2.0            // штраф за начало трека
        const val TRANS_BONUS = 0.4          // за переход (уравнивает полную частоту с кратной)
        const val IGNORE_TOL_SEC = 0.0015
        const val IGNORE_K = 1.0
        const val MAX_SKIP = 8               // пропусков подряд внутри трека
        const val BEAM = 12                  // состояний трекера на кандидата (по одному на период)
        const val BEAM_DIV = 0.03            // интервалы ближе 3 % — один период
        const val PER_MIN = 0.2              // проверка периодичностью (см. validate)
        const val PER_WIN_SEC = 0.75
        const val PER_STEP_SEC = 0.25
        const val PER_CUT_SEC = 1.0
        const val PER_EDGE_SEC = 0.5
        const val EDGE_WIN = 6               // края трека: в EDGE_WIN позициях не меньше EDGE_MIN найденных
        const val EDGE_MIN = 3
        const val JITTER_SEC = 0.0005        // разброс времени тика
        const val ACC_TYP = 6.0              // типичное угловое ускорение колеса, рад/с²
        const val HALL_SPREAD = 0.012        // разброс интервалов от расстановки датчиков
        const val MIN_SCORE = 10.0           // минимальный счёт трека
        const val OPP_DIR_K = 4.0            // трек обратного направления — счёт ≥ OPP_DIR_K·MIN_SCORE
        const val SMOOTH_HALF = 6            // сглаживание тиков: ±тиков
        const val BRIDGE_SEC = 2.0           // сращивание соседних треков
    }

    class Track {
        var times = DoubleArray(0); var real = BooleanArray(0); var dir = 1
        var score = 0.0; var snrDb = 0.0; var detected = 0; var residMs = 0.0
    }

    class Result {
        var tracks: List<Track> = ArrayList()
        var candidates = 0; var detected = 0
        var weights: DoubleArray? = null     // веса полос снизу вверх
        var hyp = ""                         // какие веса выиграли
        var bandLo = 0.0; var bandHi = 0.0   // где чирп слышен (вес ≥ 0.1), Гц
        var diag = ""
    }

    private var cancelled: () -> Boolean = { false }
    private fun check() { if (cancelled()) throw InterruptedException() }

    // ================================================================ FFT
    private fun fft(re: DoubleArray, im: DoubleArray, n: Int, inverse: Boolean) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val h = len shr 1
            for (k in 0 until h) {
                val cr = cos(ang * k); val ci = sin(ang * k)
                var i = k
                while (i < n) {
                    val b = i + h
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[i] - xr; im[b] = im[i] - xi; re[i] += xr; im[i] += xi
                    i += len
                }
            }
            len = len shl 1
        }
        if (inverse) { val s = 1.0 / n; for (i in 0 until n) { re[i] *= s; im[i] *= s } }
    }

    // ================================================================ базовая полоса
    // Полоса чирпа сдвигается к нулю, фильтруется и прореживается до ~8 кГц.
    private class Band {
        var sr = 0; var d = 0; var l = 0; var ns = 0; var hs = 0
        var fc = 0.0; var fsb = 0.0; var hiEff = 0.0; var t0 = 0.0; var winPow = 0.0
        var h = DoubleArray(0); var win = DoubleArray(0)
    }

    private fun makeBand(sr: Int): Band? {
        val b = Band()
        b.sr = sr
        b.hiEff = min(fHi, 0.47 * sr)
        if (b.hiEff - fLo < 1000) return null
        b.fc = 0.5 * (fLo + b.hiEff)
        val half = 0.5 * (b.hiEff - fLo) + 500
        b.d = max(1, floor(sr / (2 * half + 2000)).toInt())
        b.fsb = sr.toDouble() / b.d
        // ФНЧ до прореживания: пропускание ±half, задерживание с fsb − half (там уже наложение)
        val tw = max(500.0, b.fsb - 2 * half)
        val l = ceil(3.5 * sr / tw).toInt() or 1
        val fcut = 0.5 * b.fsb / sr
        b.l = l; b.h = DoubleArray(l)
        var sum = 0.0
        for (k in 0 until l) {
            val m = k - (l - 1) / 2.0
            val sinc = if (m == 0.0) 2 * fcut else sin(2 * PI * fcut * m) / (PI * m)
            b.h[k] = sinc * (0.54 - 0.46 * cos(2 * PI * k / (l - 1)))
            sum += b.h[k]
        }
        for (k in 0 until l) b.h[k] /= sum
        b.t0 = (l - 1) / 2.0 / sr
        // короткое окно (~2 мс) для оценки шума по частотам
        var ns = 8; while (ns * 2 <= b.fsb * 0.0025) ns *= 2
        b.ns = ns; b.hs = ns / 2; b.win = DoubleArray(ns); b.winPow = 0.0
        for (k in 0 until ns) { b.win[k] = 0.5 - 0.5 * cos(2 * PI * (k + 0.5) / ns); b.winPow += b.win[k] * b.win[k] }
        return b
    }

    // x — nch каналов вперемешку, берётся канал ch. Возвращает (re, im).
    private fun demod(x: FloatArray, nch: Int, ch: Int, b: Band): Pair<FloatArray, FloatArray> {
        val n = x.size / nch
        val m = (n - b.l) / b.d
        if (m < 1) return Pair(FloatArray(0), FloatArray(0))
        val zr = FloatArray(m); val zi = FloatArray(m)
        val w = 2 * PI * b.fc / b.sr
        val chunk = 1 shl 16
        val mr = DoubleArray(chunk + b.l); val mi = DoubleArray(chunk + b.l)
        var o = 0
        while (o < m) {
            check()
            val cnt = min(chunk / b.d, m - o)
            val s0 = o * b.d; val len = (cnt - 1) * b.d + b.l
            for (k in 0 until len) {
                val idx = s0 + k; val v = x[idx * nch + ch].toDouble(); val ph = w * idx
                mr[k] = v * cos(ph); mi[k] = -v * sin(ph)
            }
            for (q in 0 until cnt) {
                var ar = 0.0; var ai = 0.0; val bs = q * b.d
                for (k in 0 until b.l) { val h = b.h[k]; ar += h * mr[bs + k]; ai += h * mi[bs + k] }
                zr[o + q] = (2 * ar).toFloat(); zi[o + q] = (2 * ai).toFloat()
            }
            o += cnt
        }
        return Pair(zr, zi)
    }

    // ================================================================ шум по частотам
    // Фон по бинам короткого окна: 20-й перцентиль мощности за ±0.5 с на сетке ~64 мс.
    // Чирп проходит каждый бин за доли его периода, поэтому нижний перцентиль — шум.
    private class Noise { var bins = 0; var gridStep = 0; var gridN = 0; var floor = DoubleArray(0) }

    private fun measureNoise(zr: FloatArray, zi: FloatArray, b: Band): Noise {
        val nz = Noise()
        val ns = b.ns; val hs = b.hs
        val frames = max(0, (zr.size - ns) / hs + 1)
        nz.bins = ns
        val p = FloatArray(frames * ns)
        val re = DoubleArray(ns); val im = DoubleArray(ns)
        for (f in 0 until frames) {
            val o = f * hs
            for (k in 0 until ns) { re[k] = zr[o + k] * b.win[k]; im[k] = zi[o + k] * b.win[k] }
            fft(re, im, ns, false)
            for (k in 0 until ns) p[f * ns + k] = (re[k] * re[k] + im[k] * im[k]).toFloat()
        }
        val frameSec = hs / b.fsb
        val win = Math.rint(0.5 / frameSec).toInt()
        nz.gridStep = max(1, Math.rint(0.064 / frameSec).toInt())
        nz.gridN = frames / nz.gridStep + 1
        nz.floor = DoubleArray(nz.gridN * ns)
        val tmp = FloatArray(2 * win + 1)
        for (g in 0 until nz.gridN) {
            if (g % 64 == 0) check()
            val c = g * nz.gridStep; val a = max(0, c - win); val e = min(frames - 1, c + win)
            for (k in 0 until ns) {
                var cnt = 0
                for (f in a..e) tmp[cnt++] = p[f * ns + k]
                var v = 0.0
                if (cnt > 0) { java.util.Arrays.sort(tmp, 0, cnt); v = tmp[(0.2 * (cnt - 1)).toInt()].toDouble() }
                nz.floor[g * ns + k] = v / 0.223 / b.winPow   // σ² на отсчёт (20-й перцентиль экспоненты — 0.223 среднего)
            }
        }
        return nz
    }

    // ================================================================ шаблон
    private fun envelope(t: Double): Double {
        if (t <= 0 || t >= dur) return 0.0
        if (t < fade) return 0.5 - 0.5 * cos(PI * t / fade)
        if (t > dur - fade) return 0.5 - 0.5 * cos(PI * (dur - t) / fade)
        return 1.0
    }

    // Комплексный шаблон в базовой полосе; фаза — как в прошивке: φ(t) = f0·t + c·t²/2.
    private fun template(b: Band, up: Boolean): Pair<DoubleArray, DoubleArray> {
        val lt = ceil(dur * b.fsb).toInt() + 1
        val tr = DoubleArray(lt); val ti = DoubleArray(lt)
        val f0 = if (up) fLo else fHi
        val c = (if (up) 1 else -1) * (fHi - fLo) / dur
        for (n in 0 until lt) {
            val t = n / b.fsb
            val ph = 2 * PI * ((f0 - b.fc) * t + 0.5 * c * t * t)
            val a = if ((f0 + c * t) > b.hiEff) 0.0 else envelope(t)
            tr[n] = a * cos(ph); ti[n] = a * sin(ph)
        }
        return Pair(tr, ti)
    }

    // Полоса j (снизу вверх) — абсолютные частоты (fa, fb).
    private fun bandOf(j: Int): Pair<Double, Double> {
        val w = (fHi - fLo) / SEG; val fa = fLo + j * w
        return Pair(fa, fa + w)
    }

    // ================================================================ согласованный фильтр по кускам
    // Шаблон режется по времени на SEG кусков (у линейного чирпа это полосы), каждый — свой
    // согласованный фильтр на отбелённом сигнале; складываются ЭНЕРГИИ кусков: у пьезо два
    // резонанса (~17.5 и ~19.5 кГц), фаза между ними зависит от отражений, и когерентный
    // фильтр по всему чирпу расщеплял пик на лепестки через 3–7 мс.
    // acc[dir·SEG + j] (dir 0 — вверх, j — полоса снизу вверх): энергия куска, нормированная к
    // шуму (чистый шум — в среднем 1); null — полоса выше записи.
    private fun matchSeg(zr: FloatArray, zi: FloatArray, b: Band, nz: Noise, acc: Array<FloatArray?>) {
        val n = zr.size
        val (t0r, t0i) = template(b, true)
        val (t1r, t1i) = template(b, false)
        val lt = t0r.size
        var nn = 1024; while (nn < 4 * lt) nn *= 2
        val bb = nn - lt + 1
        val tR = arrayOfNulls<DoubleArray>(2 * SEG); val tI = arrayOfNulls<DoubleArray>(2 * SEG)
        for (dir in 0 until 2) {
            val sr = if (dir == 0) t0r else t1r; val si = if (dir == 0) t0i else t1i
            for (m in 0 until SEG) {
                val j = if (dir == 0) m else SEG - 1 - m     // кусок m по времени — полоса j по частоте
                val q = dir * SEG + j
                val (_, fb) = bandOf(j)
                if (fb > b.hiEff + 1) continue
                val r = DoubleArray(nn); val im = DoubleArray(nn)
                val a = Math.rint(m * lt.toDouble() / SEG).toInt(); val e = Math.rint((m + 1) * lt.toDouble() / SEG).toInt()
                for (k in a until e) { r[k] = sr[k]; im[k] = si[k] }
                fft(r, im, nn, false)
                tR[q] = r; tI[q] = im
                if (acc[q] == null) acc[q] = FloatArray(n)
            }
        }
        val ns = nz.bins
        val u0 = IntArray(nn); val uf = DoubleArray(nn)
        for (k in 0 until nn) {
            val f = (if (k < nn / 2) k else k - nn) * b.fsb / nn
            var u = f / b.fsb * ns; if (u < 0) u += ns
            val a = floor(u).toInt(); uf[k] = u - a; u0[k] = a % ns
        }
        val zR = DoubleArray(nn); val zI = DoubleArray(nn); val yR = DoubleArray(nn); val yI = DoubleArray(nn)
        val g = DoubleArray(nn); val sig = DoubleArray(ns); val med = DoubleArray(ns)
        val frameSec = b.hs / b.fsb
        var s = 0
        while (s < n) {
            check()
            val cnt = min(bb, n - s)
            java.util.Arrays.fill(zR, 0.0); java.util.Arrays.fill(zI, 0.0)
            var k0 = 0
            while (k0 < nn && s + k0 < n) { zR[k0] = zr[s + k0].toDouble(); zI[k0] = zi[s + k0].toDouble(); k0++ }
            fft(zR, zI, nn, false)
            // шум в середине блока, с полом (цифровая тишина там, где кодек срезал полосу)
            var gi = Math.rint((s + cnt / 2.0) / b.fsb / frameSec / nz.gridStep).toInt()
            gi = max(0, min(nz.gridN - 1, gi))
            for (k in 0 until ns) { sig[k] = nz.floor[gi * ns + k]; med[k] = sig[k] }
            med.sort()
            val floorMin = max(1e-30, med[ns / 2] * 1e-3)
            for (k in 0 until ns) if (sig[k] < floorMin) sig[k] = floorMin
            for (k in 0 until nn) {
                val a = u0[k]; val c = (a + 1) % ns
                g[k] = 1.0 / (sig[a] * (1 - uf[k]) + sig[c] * uf[k])
            }
            for (q in 0 until 2 * SEG) {
                val tr = tR[q] ?: continue
                val ti = tI[q]!!
                var norm = 0.0
                for (k in 0 until nn) {
                    // Z · conj(T) · G; шум на выходе — Σ|T|²·G / N
                    yR[k] = (zR[k] * tr[k] + zI[k] * ti[k]) * g[k]
                    yI[k] = (zI[k] * tr[k] - zR[k] * ti[k]) * g[k]
                    norm += (tr[k] * tr[k] + ti[k] * ti[k]) * g[k]
                }
                norm /= nn
                if (norm <= 0) continue
                fft(yR, yI, nn, true)
                val dst = acc[q]!!
                val inv = 1.0 / norm
                for (k in 0 until cnt) dst[s + k] += ((yR[k] * yR[k] + yI[k] * yI[k]) * inv).toFloat()
            }
            s += bb
        }
    }

    // ================================================================ щелчки
    // Удар, хлопок, стук стойки, трещотка втулки — широкополосные: дают скачок энергии и НИЖЕ
    // полосы чирпа, где пьезо не звучит вовсе. Отношение энергии CLICK_LO–CLICK_HI к её фону,
    // максимум по всей длине возможного чирпа — на сетке отсчётов базовой полосы.
    private fun clickSpan(x: FloatArray, nch: Int, sr: Int, b: Band, nOut: Int): DoubleArray? {
        if (CLICK_HI > 0.45 * sr) return null
        val frame = max(1, Math.rint(0.001 * sr).toInt())       // кадр 1 мс
        val nf = x.size / nch / frame
        if (nf < 10) return null
        val en = DoubleArray(nf)
        val a1 = exp(-2 * PI * CLICK_HI / sr); val a0 = exp(-2 * PI * CLICK_LO / sr)
        for (ch in 0 until nch) {
            check()
            var h1 = 0.0; var h2 = 0.0; var l1 = 0.0; var l2 = 0.0
            for (f in 0 until nf) {
                var s = 0.0
                for (k in 0 until frame) {
                    val v = x[(f * frame + k) * nch + ch].toDouble()
                    h1 = a1 * h1 + (1 - a1) * v; h2 = a1 * h2 + (1 - a1) * h1   // ФНЧ CLICK_HI (2 порядок)
                    l1 = a0 * l1 + (1 - a0) * v; l2 = a0 * l2 + (1 - a0) * l1   // ФНЧ CLICK_LO
                    val bp = h2 - l2
                    s += bp * bp
                }
                en[f] += s
            }
        }
        val win = 500; val step = 64; val gn = nf / step + 1
        val floorA = DoubleArray(gn); val tmp = DoubleArray(win + 2)
        for (g in 0 until gn) {
            val c = g * step; var cnt = 0
            var f = max(0, c - win)
            while (f <= min(nf - 1, c + win)) { tmp[cnt++] = en[f]; f += 2 }
            java.util.Arrays.sort(tmp, 0, cnt)
            floorA[g] = max(1e-30, tmp[(0.2 * (cnt - 1)).toInt()] / 0.223)
        }
        val ratio = FloatArray(nf)
        for (f in 0 until nf) ratio[f] = (en[f] / floorA[min(gn - 1, f / step)]).toFloat()
        val span = ceil(dur * 1000).toInt() + 4
        val mx = slidingMax(ratio, span / 2 + 1)
        val res = DoubleArray(nOut)
        for (i in 0 until nOut) {
            val t = i / b.fsb + b.t0
            val f = Math.rint(t * 1000 + span / 2.0 - 2).toInt()
            res[i] = mx[max(0, min(nf - 1, f))]
        }
        return res
    }

    // Скользящий максимум в окне ±w.
    private fun slidingMax(a: FloatArray, w: Int): DoubleArray {
        val n = a.size; val res = DoubleArray(n)
        val dq = IntArray(n); var h = 0; var tl = 0; var next = 0
        for (i in 0 until n) {
            val hi = min(n - 1, i + w)
            while (next <= hi) { while (tl > h && a[dq[tl - 1]] <= a[next]) tl--; dq[tl++] = next; next++ }
            while (dq[h] < i - w) h++
            res[i] = a[dq[h]].toDouble()
        }
        return res
    }

    // ================================================================ статистика направления
    // Взвешенная сумма энергий полос, пересчитанная к шуму равных весов по всем полосам
    // (M_eff = (Σw)²/Σw²): один порог EV_REF значит одно и то же при любых весах.
    private fun combine(seg: Array<FloatArray?>, w: DoubleArray, dir: Int): FloatArray? {
        var res: FloatArray? = null; var ws = 0.0
        val off = if (dir > 0) 0 else SEG
        for (j in 0 until SEG) {
            val a = seg[off + j]
            if (a == null || w[j] <= 0) continue
            if (res == null) res = FloatArray(a.size)
            val wj = w[j].toFloat()
            for (i in a.indices) res[i] += wj * a[i]
            ws += w[j]
        }
        if (res == null) return null
        var w2 = 0.0; var nAll = 0
        for (j in 0 until SEG) { if (seg[off + j] == null) continue; nAll++; if (w[j] > 0) w2 += w[j] * w[j] }
        val meff = ws * ws / w2
        val k = sqrt(meff / max(1, nAll)).toFloat()
        val inv = (1 / ws).toFloat()
        for (i in res.indices) res[i] = 1 + (res[i] * inv - 1) * k
        return res
    }

    // Статистика для трекера: прямой отклик минус доля обратного (обратный шаблон отвечает и на
    // настоящий чирп — «тень» 30–40 %), ослабленная на щелчках и сглаженная окном
    // SMOOTH_STAT_SEC — прямой звук и отражения (4–7 мс позже) сливаются в один пик.
    private fun statistic(z: FloatArray, opp: FloatArray, click: DoubleArray?, b: Band): DoubleArray {
        val n = z.size
        val ow = ceil(dur * b.fsb).toInt()
        val om = slidingMax(opp, ow)
        val e = DoubleArray(n)
        for (i in 0 until n) {
            e[i] = z[i] - OPP_K * max(0.0, om[i] - OPP_THR)
            if (click != null) { val c = click[i]; if (c > CLICK_THR) e[i] *= CLICK_THR / c }
        }
        val hw = max(0, Math.rint(0.5 * SMOOTH_STAT_SEC * b.fsb).toInt())
        val es = DoubleArray(n)
        var acc = 0.0; var cnt = 0
        for (i in 0 until min(n, hw)) { acc += e[i]; cnt++ }
        for (i in 0 until n) {
            if (i + hw < n) { acc += e[i + hw]; cnt++ }
            if (i - hw - 1 >= 0) { acc -= e[i - hw - 1]; cnt-- }
            es[i] = acc / cnt
        }
        return es
    }

    // ================================================================ кандидаты
    private class Cand(val t: Double, val e: Double, val v: Double, val idx: Int)

    private fun ev(e: Double, evRef: Double): Double = max(-1.5, min(4.0, ln(max(e, 1e-9) / evRef)))

    private fun candidates(es: DoubleArray, b: Band, evRef: Double): ArrayList<Cand> {
        val n = es.size
        val r = max(1, Math.rint(NMS_SEC * b.fsb).toInt())
        val thr = 0.8 * evRef
        val res = ArrayList<Cand>()
        for (i in 1 until n - 1) {
            val v = es[i]
            if (v < thr || v < es[i - 1] || v < es[i + 1]) continue
            var mx = true
            for (j in max(0, i - r)..min(n - 1, i + r)) {
                if (es[j] > v || (es[j] == v && j < i)) { mx = false; break }
            }
            if (!mx) continue
            val den = es[i - 1] - 2 * v + es[i + 1]
            var d = if (den < 0) 0.5 * (es[i - 1] - es[i + 1]) / den else 0.0
            if (d < -0.5) d = -0.5; if (d > 0.5) d = 0.5
            res.add(Cand((i + d) / b.fsb + b.t0, v / evRef, ev(v, evRef), i))
        }
        return res
    }

    // ================================================================ трекер
    // Динамическое программирование по кандидатам: сумма свидетельств тиков плюс априорная
    // гладкость интервалов, пропуски — явные переходы со штрафом, начало трека — со штрафом
    // START_PEN. Лучший трек вынимается, его время занимается, и всё повторяется.
    private class St(val cand: Int, val prev: Int, val skip: Int, val ticks: Int, val i: Double, val s: Double)

    private fun sigma(iv: Double, m: Int): Double {
        // разброс отношения соседних интервалов: время тиков + ускорение колеса + датчики
        val sj = 1.41 * JITTER_SEC / (iv * (m + 1))
        val sa = ACC_TYP * iv * iv * (m + 1) / (PI / 3)
        return sqrt(sj * sj + sa * sa + HALL_SPREAD * HALL_SPREAD)
    }

    private fun evAt(es: DoubleArray, b: Band, evRef: Double, t: Double): Double {
        val c = Math.rint((t - b.t0) * b.fsb).toInt(); val r = Math.rint(IGNORE_TOL_SEC * b.fsb).toInt()
        var mx = 0.0
        for (i in max(0, c - r)..min(es.size - 1, c + r)) if (es[i] > mx) mx = es[i]
        return ev(mx, evRef)
    }

    // Энергия, которую переход t0 → t0 + (m+1)·I оставил без внимания: тик-подобные пики на
    // 1/2, 1/3 и 2/3 каждого подынтервала — иначе ряд «каждый третий тик» (два из шести чирпов
    // за оборот почти не слышны) выигрывал у полного.
    private fun ignored(es: DoubleArray, b: Band, evRef: Double, t0: Double, iv: Double, m: Int): Double {
        var s = 0.0
        for (k in 0..m) {
            val bs = t0 + k * iv
            s += max(0.0, evAt(es, b, evRef, bs + iv / 2)) + max(0.0, evAt(es, b, evRef, bs + iv / 3)) +
                max(0.0, evAt(es, b, evRef, bs + 2 * iv / 3))
        }
        return IGNORE_K * s
    }

    private val stOrder = Comparator<St> { p1, p2 ->
        if (p2.s != p1.s) p2.s.compareTo(p1.s) else if (p1.prev != p2.prev) p1.prev.compareTo(p2.prev) else p1.skip.compareTo(p2.skip)
    }

    private fun trackAll(
        cs: List<Cand>, es: DoubleArray, b: Band, evRef: Double, minP: Double, maxP: Double, minTicks: Int,
        scores: MutableList<Double>
    ): List<IntArray> {
        val paths = ArrayList<IntArray>()
        val kk = cs.size
        val alive = BooleanArray(kk) { true }
        for (iter in 0 until 64) {
            val states = ArrayList<St>()
            val beam = arrayOfNulls<IntArray>(kk)
            var best = -1; var bestS = Double.NEGATIVE_INFINITY
            val cand = ArrayList<St>()
            for (j in 0 until kk) {
                if (j % 256 == 0) check()
                beam[j] = IntArray(0)
                if (!alive[j]) continue
                cand.clear()
                cand.add(St(j, -1, 0, 1, 0.0, cs[j].v - START_PEN))
                for (i in j - 1 downTo 0) {
                    val dt = cs[j].t - cs[i].t
                    if (dt > (MAX_SKIP + 1) * maxP) break
                    if (!alive[i] || dt < 0.8 * minP) continue
                    for (si in beam[i]!!) {
                        val p = states[si]
                        if (p.i == 0.0) {
                            if (dt < minP || dt > maxP) continue
                            cand.add(St(j, si, 0, p.ticks + 1, dt, p.s + cs[j].v - 1.0 - ignored(es, b, evRef, cs[i].t, dt, 0)))
                            continue
                        }
                        val m = Math.rint(dt / p.i).toInt() - 1
                        if (m < 0 || m > MAX_SKIP) continue
                        val iv = dt / (m + 1)
                        if (iv < minP || iv > maxP * 1.25) continue
                        val q = ln(iv / p.i) / sigma(p.i, m)
                        if (abs(q) > 8) continue
                        val sc = p.s + cs[j].v + TRANS_BONUS - m * MISS_PEN - 1.5 * ln(1 + q * q / 3) -
                            ignored(es, b, evRef, cs[i].t, iv, m)
                        cand.add(St(j, si, m, p.ticks + m + 1, iv, sc))
                    }
                }
                cand.sortWith(stOrder)
                // Луч — лучшее состояние на каждый период (интервалы ближе BEAM_DIV — один), а не
                // просто BEAM лучших: иначе продолжения уже набравшего очки (пусть мусорного) ряда
                // занимали все места, и новый ряд с настоящим периодом выбрасывался на старте.
                val ids = ArrayList<Int>()
                for (q in cand.indices) {
                    if (ids.size >= BEAM) break
                    val s = cand[q]
                    var dup = false
                    if (s.i > 0) for (si in ids) { val qi = states[si].i; if (qi > 0 && abs(ln(s.i / qi)) < BEAM_DIV) { dup = true; break } }
                    if (dup) continue
                    states.add(s)
                    ids.add(states.size - 1)
                    if (s.s > bestS) { bestS = s.s; best = states.size - 1 }
                }
                beam[j] = ids.toIntArray()
            }
            if (best < 0 || bestS < MIN_SCORE) break
            val path = ArrayList<Int>()
            var si = best
            while (si >= 0) { path.add(si); si = states[si].prev }
            path.reverse()
            val tickIdx = IntArray(path.size * 2)
            for ((q, s) in path.withIndex()) { tickIdx[2 * q] = states[s].cand; tickIdx[2 * q + 1] = states[s].skip }
            val first = states[path[0]].cand; val last = states[path[path.size - 1]].cand
            val ta = cs[first].t - 0.5 * minP; val tb = cs[last].t + 0.5 * minP
            for (i in 0 until kk) if (cs[i].t >= ta && cs[i].t <= tb) alive[i] = false
            if (states[best].ticks - 1 < minTicks) continue
            paths.add(tickIdx)
            scores.add(bestS)
        }
        return paths
    }

    // ================================================================ сглаживание
    // Путь трекера → равномерная по углу сетка тиков: локальная взвешенная квадратичная
    // регрессия времени по номеру тика (±SMOOTH_HALF) с отсевом выбросов.
    private class RawTrack(
        val n: IntArray, val idx: IntArray, val t: DoubleArray, val w: DoubleArray, val e: DoubleArray,
        val dir: Int, val score: Double
    )

    private fun fromPath(path: IntArray, cs: List<Cand>, dir: Int, score: Double): RawTrack {
        val k = path.size / 2
        val nArr = IntArray(k); val t = DoubleArray(k); val w = DoubleArray(k); val e = DoubleArray(k); val idx = IntArray(k)
        var n = 0
        for (i in 0 until k) {
            if (i > 0) n += path[2 * i + 1] + 1
            val c = cs[path[2 * i]]
            nArr[i] = n; t[i] = c.t; e[i] = c.e; w[i] = min(c.e, 40.0); idx[i] = c.idx
        }
        return RawTrack(nArr, idx, t, w, e, dir, score)
    }

    private fun fit(rt: RawTrack, rw: DoubleArray, n: Int): Double? {
        val hh = SMOOTH_HALF
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var y0 = 0.0; var y1 = 0.0; var y2 = 0.0; var cnt = 0
        for (k in rt.n.indices) {
            val d = rt.n[k] - n
            if (d < -hh || d > hh) continue
            val u = abs(d) / (hh + 1.0); val tc = 1 - u * u * u; val w = tc * tc * tc * rt.w[k] * rw[k]
            if (w <= 0) continue
            val y = rt.t[k]
            s0 += w; s1 += w * d; s2 += w * d * d; s3 += w * d * d * d; s4 += w * d * d * d * d
            y0 += w * y; y1 += w * d * y; y2 += w * d * d * y; cnt++
        }
        if (cnt >= 4) {
            val det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2)
            if (abs(det) > 1e-12 * max(1.0, s0 * s2 * s4)) {
                return (y0 * (s2 * s4 - s3 * s3) - s1 * (y1 * s4 - s3 * y2) + s2 * (y1 * s3 - s2 * y2)) / det
            }
        }
        if (cnt >= 2) {
            val det = s0 * s2 - s1 * s1
            if (det > 1e-12) return (y0 * s2 - s1 * y1) / det
        }
        return null
    }

    private fun smooth(rt: RawTrack): Track {
        val k = rt.n.size
        val rw = DoubleArray(k) { 1.0 }
        for (it in 0 until 3) {
            val ab = DoubleArray(k)
            for (i in 0 until k) { val f = fit(rt, rw, rt.n[i]); ab[i] = if (f == null) 0.0 else abs(rt.t[i] - f) }
            val srt = ab.copyOf(); srt.sort()
            val s = max(1.4826 * srt[k / 2], JITTER_SEC)
            for (i in 0 until k) { val u = ab[i] / (6 * s); rw[i] = if (u >= 1) 0.0 else (1 - u * u) * (1 - u * u) }
        }
        val n0 = rt.n[0]; val n1 = rt.n[k - 1]
        val tr = Track()
        tr.times = DoubleArray(n1 - n0 + 1); tr.real = BooleanArray(n1 - n0 + 1)
        var ki = 0
        for (n in n0..n1) {
            val f = fit(rt, rw, n)
            val t: Double
            if (f != null) t = f
            else {
                while (ki + 1 < k && rt.n[ki + 1] <= n) ki++
                val kb = min(k - 1, ki + 1)
                t = if (rt.n[kb] == rt.n[ki]) rt.t[ki] else rt.t[ki] + (rt.t[kb] - rt.t[ki]) * (n - rt.n[ki]) / (rt.n[kb] - rt.n[ki]).toDouble()
            }
            tr.times[n - n0] = t
        }
        for (i in 1 until tr.times.size) if (tr.times[i] <= tr.times[i - 1]) tr.times[i] = tr.times[i - 1] + 1e-4
        var esum = 0.0; var rs = 0.0; var det = 0
        for (i in 0 until k) {
            if (rw[i] <= 0) continue
            tr.real[rt.n[i] - n0] = true; esum += rt.e[i]; det++
            val d = rt.t[i] - tr.times[rt.n[i] - n0]; rs += d * d
        }
        tr.dir = rt.dir; tr.score = rt.score; tr.detected = det
        tr.snrDb = if (det > 0) 10 * log10(esum / det) else 0.0
        tr.residMs = if (det > 0) sqrt(rs / det) * 1000 else 0.0
        return tr
    }

    // ================================================================ проверка периодичностью
    // Трекер находит ряд и в чистом шуме (кандидатов там десятки в секунду, слабые шумовые пики
    // дают свой небольшой плюс к счёту): на 4_sweep.mp4 — заднее колесо, цепь и шина заглушили
    // чирпы на ~25 с из 35 — он тянул трек через 10 с шума, и период уплывал от 40 до 130 мс.
    // Ни сила тиков, ни их плотность настоящий слабый ряд (быстрое вращение) от такого не
    // отличают; отличает периодичность самого сигнала — автокорреляция статистики (окно
    // ±PER_WIN_SEC) на местном интервале трека: у настоящего ряда 0.3–0.7, у шума −0.3…0.16.
    // Участок ниже PER_MIN дольше PER_CUT_SEC вырезается, такие края длиннее PER_EDGE_SEC
    // обрезаются; края подрезаются ещё и по плотности найденных тиков.
    private fun excess(es: DoubleArray, b: Band): DoubleArray {
        val step = max(1, Math.rint(b.fsb / 1000).toInt())
        val m = es.size / step
        val v = DoubleArray(m)
        for (i in 0 until m) { var mx = 0.0; for (k in 0 until step) mx = max(mx, es[i * step + k]); v[i] = max(0.0, mx - 1) }
        return v
    }

    private fun countReal(r: BooleanArray, a: Int, b: Int): Int { var c = 0; for (i in a..b) if (r[i]) c++; return c }

    private fun validate(t: Track, v: DoubleArray, b: Band, minTicks: Int): List<Track> {
        val res = ArrayList<Track>()
        val nt = t.times.size; val m = v.size; val w = Math.rint(PER_WIN_SEC * 1000).toInt()
        val ta = t.times[0]; val tb = t.times[nt - 1]
        // шаг корреляции — местный интервал трека в каждой точке (колесо разгоняется и тормозит)
        val lag = IntArray(m)
        var kk = 0
        for (i in 0 until m) {
            val ti = i / 1000.0 + b.t0
            while (kk + 2 < nt && t.times[kk + 1] <= ti) kk++
            lag[i] = Math.rint((t.times[kk + 1] - t.times[kk]) * 1000).toInt()
        }
        val ng = floor((tb - ta) / PER_STEP_SEC).toInt() + 1
        val good = BooleanArray(ng)
        for (g in 0 until ng) {
            val tc = ta + g * PER_STEP_SEC
            val c = Math.rint((tc - b.t0) * 1000).toInt()
            val a0 = max(0, c - w); var a1 = min(m, c + w)
            while (a1 > a0 && a1 - 1 + lag[a1 - 1] + 2 >= m) a1--
            if (a1 - a0 < 4 * lag[max(0, min(m - 1, c))]) { good[g] = true; continue }
            var mean = 0.0; for (i in a0 until a1) mean += v[i]; mean /= (a1 - a0)
            var den = 0.0; for (i in a0 until a1) den += (v[i] - mean) * (v[i] - mean)
            var best = -1.0
            for (dl in -1..1) {
                var s = 0.0; for (i in a0 until a1) s += (v[i] - mean) * (v[i + lag[i] + dl] - mean)
                best = max(best, s / max(1e-12, den))
            }
            good[g] = best >= PER_MIN
        }
        // плохие отрезки: внутри — от PER_CUT_SEC, по краям — от PER_EDGE_SEC
        val keep = BooleanArray(ng) { true }
        var g = 0
        while (g < ng) {
            if (good[g]) { g++; continue }
            val a = g; while (g < ng && !good[g]) g++
            val len = (g - a) * PER_STEP_SEC
            val edge = a == 0 || g == ng
            if (len >= (if (edge) PER_EDGE_SEC else PER_CUT_SEC)) for (q in a until g) keep[q] = false
        }
        fun node(k: Int) = min(ng - 1, Math.rint((t.times[k] - ta) / PER_STEP_SEC).toInt())
        var p = 0
        while (p < nt) {
            if (!keep[node(p)]) { p++; continue }
            var q = p
            while (q + 1 < nt && keep[node(q + 1)]) q++
            val pn = q + 1
            // края — до первых (последних) EDGE_WIN позиций, где найдено хотя бы EDGE_MIN тиков
            while (p + EDGE_WIN - 1 <= q && countReal(t.real, p, p + EDGE_WIN - 1) < EDGE_MIN) p++
            while (p <= q && !t.real[p]) p++
            while (q - EDGE_WIN + 1 >= p && countReal(t.real, q - EDGE_WIN + 1, q) < EDGE_MIN) q--
            while (q >= p && !t.real[q]) q--
            if (q - p >= minTicks) {
                val nt2 = Track(); nt2.dir = t.dir; nt2.snrDb = t.snrDb; nt2.residMs = t.residMs
                nt2.times = t.times.copyOfRange(p, q + 1); nt2.real = t.real.copyOfRange(p, q + 1)
                nt2.detected = nt2.real.count { it }
                nt2.score = t.score * (q - p + 1) / nt
                res.add(nt2)
            }
            p = pn
        }
        return res
    }

    // ================================================================ сборка треков
    // Сильные треки первыми, пересечения с принятыми отрезаются. Обратное основному направление
    // допускается только отчётливым (счёт ≥ OPP_DIR_K·MIN_SCORE).
    private fun resolve(allIn: List<Track>, minTicks: Int): List<Track> {
        val all = ArrayList(allIn)
        all.sortWith(Comparator { p1, p2 -> if (p2.score != p1.score) p2.score.compareTo(p1.score) else p1.times[0].compareTo(p2.times[0]) })
        var su = 0.0; var sdn = 0.0
        for (t in all) { if (t.dir > 0) su += t.score else sdn += t.score }
        val mainDir = if (su >= sdn) 1 else -1
        all.removeAll { it.dir != mainDir && it.score < OPP_DIR_K * MIN_SCORE }
        val acc = ArrayList<Track>()
        for (t in all) {
            var bestA = -1; var bestB = -1; var a = -1
            for (i in 0..t.times.size) {
                var free = i < t.times.size
                if (free) for (q in acc) if (t.times[i] >= q.times[0] - 0.005 && t.times[i] <= q.times[q.times.size - 1] + 0.005) { free = false; break }
                if (free) { if (a < 0) a = i }
                else if (a >= 0) { if (i - a > bestB - bestA) { bestA = a; bestB = i }; a = -1 }
            }
            if (bestA < 0 || bestB - bestA - 1 < minTicks) continue
            if (bestA > 0 || bestB < t.times.size) {
                val nt = Track(); nt.dir = t.dir; nt.score = t.score; nt.snrDb = t.snrDb; nt.residMs = t.residMs
                nt.times = t.times.copyOfRange(bestA, bestB); nt.real = t.real.copyOfRange(bestA, bestB)
                nt.detected = nt.real.count { it }
                acc.add(nt)
            } else acc.add(t)
        }
        acc.sortWith(Comparator { p1, p2 -> p1.times[0].compareTo(p2.times[0]) })
        return acc
    }

    // Сращивание соседних треков одного направления через паузу ≤ BRIDGE_SEC с тем же периодом.
    private fun bridge(ts: List<Track>, maxP: Double): List<Track> {
        val res = ArrayList<Track>()
        for (t in ts) {
            if (res.isNotEmpty()) {
                val p = res[res.size - 1]
                val np = p.times.size
                val g = t.times[0] - p.times[np - 1]
                val ia = p.times[np - 1] - p.times[np - 2]; val ib = t.times[1] - t.times[0]
                if (p.dir == t.dir && g > 0 && g <= BRIDGE_SEC && abs(ia / ib - 1) <= 0.15) {
                    val n = max(1, Math.rint(g / (0.5 * (ia + ib))).toInt())
                    if (g / n <= 1.25 * maxP) {
                        val tt = ArrayList<Double>(); for (v in p.times) tt.add(v)
                        val rr = ArrayList<Boolean>(); for (v in p.real) rr.add(v)
                        var sumI = 0.0; val iv = DoubleArray(n)
                        for (i in 0 until n) { iv[i] = ia + (ib - ia) * (i + 0.5) / n; sumI += iv[i] }
                        var acc = p.times[np - 1]
                        for (i in 0 until n - 1) { acc += iv[i] * g / sumI; tt.add(acc); rr.add(false) }
                        for (v in t.times) tt.add(v)
                        for (v in t.real) rr.add(v)
                        val j = Track(); j.dir = p.dir; j.score = p.score + t.score; j.detected = p.detected + t.detected
                        j.snrDb = max(p.snrDb, t.snrDb); j.residMs = max(p.residMs, t.residMs)
                        j.times = tt.toDoubleArray(); j.real = rr.toBooleanArray()
                        res[res.size - 1] = j
                        continue
                    }
                }
            }
            res.add(t)
        }
        return res
    }

    // ================================================================ веса полос
    // Где полоса реально звучит — без тиков: частота всплесков энергии полосы сверх шумовой.
    // Срезанная микрофоном или кодеком полоса получает вес 0.
    private fun presenceWeights(seg: Array<FloatArray?>, click: DoubleArray?, nch: Int): DoubleArray? {
        val w = DoubleArray(SEG); var mx = 0.0
        // доля отсчётов выше 5 у шума: Γ(nch, 1/nch) — для 1 канала e^-5, для 2 — 11·e^-10
        val noise = if (nch >= 2) 11 * exp(-10.0) else exp(-5.0)
        for (j in 0 until SEG) {
            val a = seg[j] ?: continue
            val c = seg[SEG + j]
            var hit = 0; var tot = 0
            for (i in a.indices) {
                if (click != null && click[i] > CLICK_THR) continue
                tot++
                if (max(a[i], c?.get(i) ?: 0f) > 5) hit++
            }
            val rate = if (tot > 0) hit.toDouble() / tot else 0.0
            w[j] = max(0.0, rate - 6 * noise)
            mx = max(mx, w[j])
        }
        if (mx <= 0) return null
        for (j in 0 until SEG) w[j] /= mx
        return w
    }

    // По уверенным тикам: отношение сигнал/шум каждой полосы; вес полосы ∝ ему.
    private fun learnWeights(seg: Array<FloatArray?>, raws: List<RawTrack>): DoubleArray? {
        val sum = DoubleArray(SEG); val cnt = IntArray(SEG)
        for (rt in raws) {
            val off = if (rt.dir > 0) 0 else SEG
            for (i in rt.n.indices) {
                if (rt.e[i] < 2) continue
                for (j in 0 until SEG) {
                    val a = seg[off + j] ?: continue
                    val c = rt.idx[i]; val r = 16
                    var s = 0.0; var n = 0
                    for (k in max(0, c - r)..min(a.size - 1, c + r)) { s += a[k]; n++ }
                    if (n > 0) { sum[j] += s / n - 1; cnt[j]++ }
                }
            }
        }
        val w = DoubleArray(SEG); var mx = 0.0
        for (j in 0 until SEG) { w[j] = if (cnt[j] > 0) max(0.0, sum[j] / cnt[j]) else 0.0; mx = max(mx, w[j]) }
        if (mx <= 0) return null
        for (j in 0 until SEG) w[j] /= mx
        return w
    }

    // ================================================================ один прогон с весами
    private fun detect(
        seg: Array<FloatArray?>, w: DoubleArray, click: DoubleArray?, b: Band, minP: Double, maxP: Double, minTicks: Int,
        rawsOut: MutableList<RawTrack>?
    ): Result {
        val r = Result(); r.weights = w
        val all = ArrayList<Track>()
        val zu = combine(seg, w, 1); val zd = combine(seg, w, -1)
        if (zu == null || zd == null) return r
        var dir = 1
        while (dir >= -1) {
            val evRef = EV_REF
            val es = if (dir > 0) statistic(zu, zd, click, b) else statistic(zd, zu, click, b)
            val cs = candidates(es, b, evRef)
            r.candidates += cs.size
            val sc = ArrayList<Double>()
            val paths = trackAll(cs, es, b, evRef, minP, maxP, minTicks, sc)
            val v = excess(es, b)
            for (i in paths.indices) {
                val rt = fromPath(paths[i], cs, dir, sc[i])
                val ok = validate(smooth(rt), v, b, minTicks)
                if (ok.isNotEmpty()) rawsOut?.add(rt)
                all.addAll(ok)
            }
            dir -= 2
        }
        r.tracks = bridge(resolve(all, minTicks), maxP)
        for (t in r.tracks) r.detected += t.detected
        return r
    }

    private fun totalScore(r: Result): Double { var s = 0.0; for (t in r.tracks) s += t.score; return s }

    private fun better(a: Result, b: Result?): Boolean {
        if (b == null) return true
        if (a.detected != b.detected) return a.detected > b.detected
        return totalScore(a) > totalScore(b)
    }

    // ================================================================ вход
    /**
     * [x] — звук, [nch] каналов вперемешку (стерео обрабатывается раздельно и складывается по
     * энергиям); [minP]/[maxP] — допустимый интервал между тиками, с; [minTicks] — минимум
     * интервалов в треке.
     */
    fun run(x: FloatArray, nch: Int, sr: Int, minP: Double, maxP: Double, minTicks: Int, cancelled: () -> Boolean = { false }): Result {
        this.cancelled = cancelled
        val b = makeBand(sr) ?: return Result().also { it.diag = "audio band too narrow for the chirp" }
        val seg = arrayOfNulls<FloatArray>(2 * SEG)
        var n = 0
        for (ch in 0 until nch) {
            val (zr, zi) = demod(x, nch, ch, b)
            n = zr.size
            val nz = measureNoise(zr, zi, b)
            matchSeg(zr, zi, b, nz, seg)
        }
        for (q in 0 until 2 * SEG) { val a = seg[q] ?: continue; val inv = 1f / nch; for (i in a.indices) a[i] *= inv }
        val click = clickSpan(x, nch, sr, b, n)

        // Первый проход — две гипотезы весов: все полосы поровну и «где полоса звучит».
        val eq = DoubleArray(SEG) { if (seg[it] != null) 1.0 else 0.0 }
        var best: Result? = null; var bestRaws: List<RawTrack>? = null
        val hyps = ArrayList<DoubleArray>(); val names = ArrayList<String>()
        hyps.add(eq); names.add("equal")
        val pw = presenceWeights(seg, click, nch)
        if (pw != null) { hyps.add(pw); names.add("presence") }
        for (h in hyps.indices) {
            val raws = ArrayList<RawTrack>()
            val r = detect(seg, hyps[h], click, b, minP, maxP, minTicks, raws)
            r.hyp = names[h]
            if (better(r, best)) { best = r; bestRaws = raws }
        }
        // Второй проход — веса из найденных тиков.
        val br = bestRaws
        if (br != null && br.isNotEmpty()) {
            val lw = learnWeights(seg, br)
            if (lw != null) {
                val r = detect(seg, lw, click, b, minP, maxP, minTicks, null)
                r.hyp = "learned"
                if (better(r, best)) best = r
            }
        }
        val res = best!!
        val w = res.weights
        if (w != null) {
            for (j in 0 until SEG) {
                if (w[j] < 0.1) continue
                val (fa, fb) = bandOf(j)
                if (res.bandLo == 0.0) res.bandLo = fa
                res.bandHi = fb
            }
        }
        return res
    }
}
