package com.povwheel.app.povvideo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Участок отрисовки: моменты тиков (с) и признак «тик подтверждён кандидатом» (иначе достроен). */
class TickTrack(val times: DoubleArray, val real: BooleanArray)

/**
 * Детектор тиков синхро-датчика — перенос `PovTicks` из tools/pov_fps_blend/POV-BlendFPS.ps1
 * (C#) один в один; подробности алгоритма — в комментариях там и в README.txt рядом.
 *
 * Прошивка пищит пьезо (PIEZO_FREQ_HZ, 5 мс) на каждом событии датчика Холла, пока лента
 * светится, — 6 тиков за оборот. Тик на записи тихий, гуляет по громкости и звенит
 * отражениями, поэтому детектор не пороговый: согласованный фильтр на тон, кандидаты над
 * локальным фоном, период по автокорреляции огибающей и сетка тиков по фазе, которая сама
 * достраивает пропущенные тики и не замечает случайных.
 *
 * Объект с состоянием: [grid] берёт огибающую и период последнего [detect].
 * `Math.Round` из C# (банковское округление) здесь — [rint], чтобы совпадать до тика.
 */
internal class PovTicks {
    private var aE0 = DoubleArray(0)
    private var aPer = DoubleArray(0)
    private var aPStep = 0.1
    private var aHs = 0.0
    private var aWin = 0
    private var aSr = 0

    companion object {
        // Фон — 25-й процентиль огибающей за ±250 мс: на быстром вращении тики идут
        // через 23 мс и отражения не дают огибающей опуститься до шума.
        private const val FLOOR_PCT = 0.25
        // Самый длинный провал согласия, который сращивается в одну отрисовку, с.
        private const val BRIDGE_SEC = 2.0

        fun envelope(x: FloatArray, sr: Int, f: Double, win: Int, hop: Int): DoubleArray {
            val n = x.size
            val m = n / hop
            val env = DoubleArray(m)
            val w = 2.0 * PI * f / sr
            var cr = 1.0
            var ci = 0.0
            val dr = cos(w)
            val di = -sin(w)
            val re = DoubleArray(win)
            val im = DoubleArray(win)
            var sR = 0.0
            var sI = 0.0
            val half = win / 2
            for (i in 0 until n) {
                val xv = x[i].toDouble()
                val vr = xv * cr
                val vi = xv * ci
                val k = i % win
                sR += vr - re[k]; sI += vi - im[k]
                re[k] = vr; im[k] = vi
                val t = cr * dr - ci * di; ci = cr * di + ci * dr; cr = t
                if ((i and 1023) == 0) { val nn = sqrt(cr * cr + ci * ci); cr /= nn; ci /= nn }
                val c = i - half
                if (c >= 0 && c % hop == 0) {
                    val j = c / hop
                    if (j < m) env[j] = 2.0 * sqrt(sR * sR + sI * sI) / win
                }
            }
            return env
        }

        private fun db(v: Double) = 20.0 * log10(max(v, 1e-12))
        private fun rint(v: Double) = Math.rint(v)

        private fun median(v: ArrayList<Double>): Double {
            if (v.isEmpty()) return 0.0
            v.sort()
            return v[v.size / 2]
        }

        /** Как C# Array.BinarySearch: индекс или ~(место вставки). */
        private fun bsearch(a: DoubleArray, v: Double): Int = java.util.Arrays.binarySearch(a, v)
    }

    /** Начало тика по пику огибающей i (полувысота на подъёме, не дальше половины тона + 1.5 мс). */
    private fun startOf(i: Int): Double {
        val e0 = aE0
        val back = max(1, rint((aWin / 2.0 / aSr + 0.0015) / aHs).toInt())
        val halfAmp = e0[i] * 0.5
        var k = i
        while (k > max(0, i - back)) {
            if (e0[k - 1] < halfAmp) {
                val prev = e0[k - 1]
                var frac = if (e0[k] - prev > 0) (halfAmp - prev) / (e0[k] - prev) else 0.0
                if (frac < 0) frac = 0.0
                if (frac > 1) frac = 1.0
                return (k - 1 + frac) * aHs
            }
            k--
        }
        return i * aHs - aWin / 2.0 / aSr
    }

    /**
     * Кандидаты — тройки [t, snr, T]: t — начало тика, с от первого сэмпла; snr — пик над
     * локальным фоном, дБ; T — локальный период по автокорреляции, с (0 — не виден).
     */
    fun detect(
        x: FloatArray, sr: Int, f0: Double, sideHz: Double, tickMs: Double,
        snrDb: Double, tonalDb: Double, minPeriod: Double, maxPeriod: Double,
        cancelled: () -> Boolean = { false }
    ): DoubleArray {
        val hop = max(1, sr / 4000)
        val hs = hop.toDouble() / sr
        val win = max(8, rint(sr * tickMs / 1000.0).toInt())
        // Три канала через 50 Гц, берём наибольший: тон стоит не ровно на номинале.
        val e0 = envelope(x, sr, f0, win, hop)
        run {
            val eA = envelope(x, sr, f0 - 50, win, hop)
            val eB = envelope(x, sr, f0 + 50, win, hop)
            for (i in e0.indices) e0[i] = max(e0[i], max(eA[i], eB[i]))
        }
        if (cancelled()) return DoubleArray(0)
        val eL = envelope(x, sr, f0 - sideHz, win, hop)
        val eH = envelope(x, sr, f0 + sideHz, win, hop)
        val m = e0.size
        if (m < 16) return DoubleArray(0)
        val d0 = DoubleArray(m)
        val ds = DoubleArray(m)
        for (i in 0 until m) { d0[i] = db(e0[i]); ds[i] = db(max(eL[i], eH[i])) }

        val step = max(1, rint(0.010 / hs).toInt())
        val halfW = max(1, (0.25 / hs).toInt())
        val sub = max(1, rint(0.002 / hs).toInt())
        val g = m / step + 1
        val floorC = DoubleArray(g)
        val tmp = DoubleArray(2 * halfW / sub + 4)
        for (q in 0 until g) {
            val c = q * step
            val a = max(0, c - halfW)
            val b = min(m - 1, c + halfW)
            var cnt = 0
            var j = a
            while (j <= b) { tmp[cnt++] = d0[j]; j += sub }
            java.util.Arrays.sort(tmp, 0, cnt)
            floorC[q] = if (cnt > 0) tmp[(cnt * FLOOR_PCT).toInt()] else -240.0
        }

        aE0 = e0; aWin = win; aSr = sr; aHs = hs

        val per1 = max(1, rint(0.001 / hs).toInt())
        val m1 = m / per1
        val ex = DoubleArray(m1)
        for (k in 0 until m1) {
            var mx = 0.0
            for (j in k * per1 until (k + 1) * per1) {
                val v = d0[j] - floorC[min(g - 1, j / step)] - 3.0
                if (v > mx) mx = v
            }
            ex[k] = mx
        }
        if (cancelled()) return DoubleArray(0)

        // Период — с запасом 25 % сверх прорисовки на минимальных оборотах.
        val minLag = max(2, floor(minPeriod * 1000).toInt())
        val maxLag = max(minLag + 2, ceil(maxPeriod * 1250).toInt())
        val pStep = 100
        val pHalf = 300
        val pg = m1 / pStep + 1
        val perC = DoubleArray(pg)
        val r = DoubleArray(maxLag + 2)
        for (q in 0 until pg) {
            val c = q * pStep
            val a = max(0, c - pHalf)
            val b = min(m1 - 1, c + pHalf)
            var e2 = 0.0
            for (i in a..b) e2 += ex[i] * ex[i]
            perC[q] = 0.0
            if (e2 < 1e-9 || b - a < 2 * maxLag) continue
            var rmax = 0.0
            for (lag in minLag - 1..maxLag + 1) {
                var s = 0.0
                var i = a
                while (i + lag <= b) { s += ex[i] * ex[i + lag]; i++ }
                r[lag] = s / e2
                if (lag in minLag..maxLag && r[lag] > rmax) rmax = r[lag]
            }
            for (lag in minLag..maxLag) {
                if (r[lag] >= 0.65 * rmax && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
                    // Парабола по трём точкам — период точнее миллисекундного шага.
                    val den = r[lag - 1] - 2 * r[lag] + r[lag + 1]
                    var dl = if (den < 0) 0.5 * (r[lag - 1] - r[lag + 1]) / den else 0.0
                    if (dl < -0.5) dl = -0.5
                    if (dl > 0.5) dl = 0.5
                    perC[q] = (lag + dl) / 1000.0
                    break
                }
            }
        }
        aPer = perC; aPStep = pStep / 1000.0

        val rad0 = max(1, (minPeriod / 2 / hs).toInt())
        val ct = ArrayList<Double>()
        val cs = ArrayList<Double>()
        val cp = ArrayList<Double>()
        for (i in 1 until m - 1) {
            val v = d0[i]
            val fl = floorC[min(g - 1, i / step)]
            if (v - fl < snrDb) continue
            if (v - ds[i] < tonalDb) continue
            var isMax = true
            val lo = max(0, i - rad0)
            val hi = min(m - 1, i + rad0)
            for (j in lo..hi) { if (d0[j] > v || (d0[j] == v && j < i)) { isMax = false; break } }
            if (!isMax) continue
            ct.add(startOf(i)); cs.add(v - fl)
            cp.add(perC[min(pg - 1, rint(i * hs * 1000 / pStep).toInt())])
        }

        // В пределах ±0.55 периода остаётся сильнейший кандидат — так уходят отражения.
        val n = ct.size
        val ord = (0 until n).sortedByDescending { cs[it] }
        val acc = ArrayList<Double>()
        val keep = BooleanArray(n)
        for (i in ord) {
            val tPer = if (cp[i] > 0) cp[i] else maxPeriod
            val rad = 0.55 * tPer
            var pos = java.util.Collections.binarySearch(acc, ct[i])
            if (pos < 0) pos = -pos - 1
            var ok = true
            if (pos < acc.size && acc[pos] - ct[i] < rad) ok = false
            if (pos > 0 && ct[i] - acc[pos - 1] < rad) ok = false
            if (ok) { acc.add(pos, ct[i]); keep[i] = true }
        }
        val kt = ArrayList<Double>()
        val ks = ArrayList<Double>()
        val kp = ArrayList<Double>()
        for (i in 0 until n) if (keep[i]) { kt.add(ct[i]); ks.add(cs[i]); kp.add(cp[i]) }

        var changed = true
        while (changed) {
            changed = false
            for (j in 1 until kt.size) {
                val tPer = 0.5 * (kp[j] + kp[j - 1])
                if (tPer <= 0) continue
                if (kt[j] - kt[j - 1] >= 0.8 * tPer) continue
                var eDropJ = 9.0
                var eDropPrev = 9.0
                if (j + 1 < kt.size) {
                    val q = (kt[j + 1] - kt[j - 1]) / tPer
                    eDropJ = abs(q - rint(q)); if (rint(q) < 1) eDropJ = 9.0
                }
                if (j - 2 >= 0) {
                    val q = (kt[j] - kt[j - 2]) / tPer
                    eDropPrev = abs(q - rint(q)); if (rint(q) < 1) eDropPrev = 9.0
                }
                if (min(eDropJ, eDropPrev) > 0.25) continue
                val del = if (eDropJ <= eDropPrev) j else j - 1
                kt.removeAt(del); ks.removeAt(del); kp.removeAt(del)
                changed = true
                break
            }
        }

        val out = DoubleArray(kt.size * 3)
        for (i in kt.indices) { out[3 * i] = kt[i]; out[3 * i + 1] = ks[i]; out[3 * i + 2] = kp[i] }
        return out
    }

    /**
     * Сетка тиков по фазе по кандидатам последнего [detect]. Возвращает участки отрисовки
     * (времена — в той же шкале, что кандидаты).
     */
    fun grid(
        trip: DoubleArray, maxPeriod: Double,
        winSec: Double, minR: Double, minVotes: Int, minTrackSec: Double, minTicks: Int
    ): List<TickTrack> {
        val res = ArrayList<TickTrack>()
        if (aPer.size < 2) return res
        val nc = trip.size / 3
        val ct = DoubleArray(nc)
        val cw = DoubleArray(nc)
        for (i in 0 until nc) { ct[i] = trip[3 * i]; cw[i] = min(20.0, max(1.0, trip[3 * i + 1] - 4.0)) }

        // Период: медиана по пяти соседним (одиночный выброс автокорреляции не сбивает фазу).
        val pg = aPer.size
        val per = DoubleArray(pg)
        val tmp = ArrayList<Double>()
        for (q in 0 until pg) {
            tmp.clear()
            for (j in max(0, q - 2)..min(pg - 1, q + 2)) if (aPer[j] > 0) tmp.add(aPer[j])
            per[q] = if (tmp.size >= 3) median(tmp) else 0.0
        }

        val dt = 0.001
        val tEnd = pg * aPStep
        val nf = (tEnd / dt).toInt() + 1
        val phi = DoubleArray(nf)
        val valid = BooleanArray(nf)
        fun phiAt(t: Double): Double {
            val kf = t / dt
            val k0 = floor(kf).toInt()
            if (k0 < 0) return phi[0]
            if (k0 >= nf - 1) return phi[nf - 1]
            return phi[k0] + (phi[k0 + 1] - phi[k0]) * (kf - k0)
        }
        val vc = DoubleArray(nc)
        val vs = DoubleArray(nc)
        val gStep = 0.05
        val ng = (tEnd / gStep).toInt() + 1
        val th = DoubleArray(ng)
        val rg = DoubleArray(ng)
        val cnt = IntArray(ng)
        val good = BooleanArray(ng)
        val on = BooleanArray(ng)
        fun perAt(q: Int): Double = per[min(pg - 1, rint(q * gStep / aPStep).toInt())]
        val holeMax = rint(BRIDGE_SEC / gStep).toInt()

        // Два прохода: во втором — с периодом, исправленным в провалах.
        for (pass in 0 until 2) {
            for (k in 1 until nf) {
                val t = k * dt
                val qf = t / aPStep
                val q0 = min(pg - 1, floor(qf).toInt())
                val q1 = min(pg - 1, q0 + 1)
                val fr = qf - q0
                var tPer = if (per[q0] > 0 && per[q1] > 0) per[q0] + (per[q1] - per[q0]) * fr
                           else if (per[q0] > 0) per[q0] else per[q1]
                valid[k] = tPer > 0
                if (tPer <= 0) tPer = maxPeriod
                phi[k] = phi[k - 1] + dt / tPer
            }
            for (i in 0 until nc) {
                val a = 2 * PI * phiAt(ct[i])
                vc[i] = cw[i] * cos(a); vs[i] = cw[i] * sin(a)
            }
            for (q in 0 until ng) {
                val tc = q * gStep
                val pq = min(pg - 1, rint(tc / aPStep).toInt())
                val w = max(winSec, 3.5 * per[pq])
                var lo = bsearch(ct, tc - w); if (lo < 0) lo = lo.inv()
                var hi = bsearch(ct, tc + w); if (hi < 0) hi = hi.inv() else hi++
                var sc = 0.0; var ss = 0.0; var sw = 0.0
                for (i in lo until hi) { sc += vc[i]; ss += vs[i]; sw += cw[i] }
                cnt[q] = hi - lo
                rg[q] = if (sw > 0) sqrt(sc * sc + ss * ss) / sw else 0.0
                th[q] = atan2(ss, sc)
            }
            for (q in 0 until ng) {
                val k = min(nf - 1, rint(q * gStep / dt).toInt())
                good[q] = rg[q] >= minR && cnt[q] >= minVotes && valid[k]
                on[q] = good[q]
            }
            if (pass == 1) break

            // Провал, где тики утонули в шуме, но обороты на краях совпадают (±15 %):
            // период внутри — прямой между краями (по уверенным узлам), и всё заново.
            fun edgePer(from: Int, to: Int): Double {
                val pv = ArrayList<Double>()
                for (j in max(0, from)..min(ng - 1, to))
                    if (good[j] && rg[j] >= 0.7 && perAt(j) > 0) pv.add(perAt(j))
                return if (pv.size >= 3) median(pv) else 0.0
            }
            val edgeN = rint(1.0 / gStep).toInt()
            val extN = rint(0.5 / gStep).toInt()
            var patched = false
            var q = 0
            while (q < ng) {
                if (on[q]) { q++; continue }
                val a = q
                while (q < ng && !on[q]) q++
                if (a == 0 || q >= ng || q - a > holeMax) continue
                var pa = edgePer(a - edgeN, a - 1)
                var pb = edgePer(q, q + edgeN - 1)
                if (pa <= 0) pa = perAt(a - 1)
                if (pb <= 0) pb = perAt(q)
                if (pa <= 0 || pb <= 0 || abs(pa / pb - 1) > 0.15) continue
                var a2 = a
                var q2 = q
                while (a2 > 1 && a - a2 < extN && abs(perAt(a2 - 1) / pa - 1) > 0.15) a2--
                while (q2 < ng - 1 && q2 - q < extN && abs(perAt(q2) / pb - 1) > 0.15) q2++
                if (q2 - a2 > holeMax) continue
                val ia = min(pg - 1, rint((a2 - 1) * gStep / aPStep).toInt())
                val ib = min(pg - 1, rint(q2 * gStep / aPStep).toInt())
                for (j in ia + 1 until ib) { per[j] = pa + (pb - pa) * (j - ia) / (ib - ia).toDouble(); patched = true }
            }
            if (!patched) break
        }
        // Сращивание провалов с непрерывным периодом не длиннее BRIDGE_SEC.
        run {
            var q = 0
            while (q < ng) {
                if (on[q]) { q++; continue }
                val a = q
                while (q < ng && !on[q]) q++
                if (a == 0 || q >= ng || q - a > holeMax) continue
                val pa = perAt(a - 1)
                val pb = perAt(q)
                if (pa <= 0 || pb <= 0 || abs(pa / pb - 1) > 0.15) continue
                val pm = 0.5 * (pa + pb)
                var cont = true
                for (j in a until q) { val pj = perAt(j); if (pj <= 0 || abs(pj / pm - 1) > 0.15) { cont = false; break } }
                if (cont) for (j in a until q) on[j] = true
            }
        }
        var q = 0
        while (q < ng) {
            if (!on[q]) { q++; continue }
            val a = q
            while (q < ng && on[q]) q++
            val b = q - 1
            val t0 = a * gStep
            val t1 = b * gStep
            if (t1 - t0 < minTrackSec) continue
            // Сдвиг сетки θ — развёрнутый по надёжным узлам, в провалах — по прямой.
            val thu = DoubleArray(b - a + 1)
            var lastGood = -1
            for (j in thu.indices) {
                if (!good[a + j]) continue
                if (lastGood < 0) thu[j] = th[a + j]
                else {
                    var d = th[a + j] - th[a + lastGood]
                    while (d > PI) d -= 2 * PI
                    while (d < -PI) d += 2 * PI
                    thu[j] = thu[lastGood] + d
                    for (u in lastGood + 1 until j) thu[u] = thu[lastGood] + d * (u - lastGood) / (j - lastGood)
                }
                lastGood = j
            }
            if (lastGood < 0) continue
            for (j in lastGood + 1 until thu.size) thu[j] = thu[lastGood]
            fun theta(t: Double): Double {
                val qf = (t - t0) / gStep
                if (qf <= 0) return thu[0]
                if (qf >= thu.size - 1) return thu[thu.size - 1]
                val q0 = floor(qf).toInt()
                return thu[q0] + (thu[q0 + 1] - thu[q0]) * (qf - q0)
            }
            // Тики — пересечения целых ψ(t) = φ(t) − θ(t)/2π.
            val tk = ArrayList<Double>()
            val ts = max(0.0, t0 - winSec)
            val te = min(tEnd, t1 + winSec)
            var prevPsi = phiAt(ts) - theta(ts) / (2 * PI)
            var t = ts + dt
            while (t <= te) {
                val psi = phiAt(t) - theta(t) / (2 * PI)
                if (floor(psi) > floor(prevPsi)) {
                    val target = floor(psi)
                    val f = (target - prevPsi) / (psi - prevPsi)
                    tk.add(t - dt + f * dt)
                }
                prevPsi = psi
                t += dt
            }
            if (tk.size < 2) continue
            val hit = BooleanArray(tk.size)
            for (j in tk.indices) {
                val tj = if (j + 1 < tk.size) tk[j + 1] - tk[j] else tk[j] - tk[j - 1]
                var pos = bsearch(ct, tk[j])
                if (pos < 0) pos = pos.inv()
                for (c in max(0, pos - 1)..min(nc - 1, pos)) if (abs(ct[c] - tk[j]) <= 0.15 * tj) hit[j] = true
            }
            // Края: из шести тиков подряд хотя бы пять подтверждены, первые три — подряд.
            var s0 = -1
            var s1 = -1
            for (j in 0..tk.size - 6) {
                var h = 0
                for (u in j until j + 6) if (hit[u]) h++
                if (h < 5) continue
                for (u in j until tk.size - 2) if (hit[u] && hit[u + 1] && hit[u + 2]) { s0 = u; break }
                break
            }
            for (j in tk.size - 6 downTo 0) {
                var h = 0
                for (u in j until j + 6) if (hit[u]) h++
                if (h < 5) continue
                for (u in j + 5 downTo 2) if (hit[u] && hit[u - 1] && hit[u - 2]) { s1 = u; break }
                break
            }
            if (s0 < 0 || s1 - s0 < minTicks) continue
            val n = s1 - s0 + 1
            val times = DoubleArray(n) { tk[s0 + it] }
            val real = BooleanArray(n) { hit[s0 + it] }
            res.add(TickTrack(times, real))
        }
        return res
    }
}
