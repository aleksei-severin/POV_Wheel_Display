package com.povwheel.app.convert

/**
 * Квантователь по median cut: 15840 пикселей RGB888 в палитру на 256 цветов и
 * по одному байту индекса на пиксель. Точный перенос quantizeFrame() из
 * data/index.html.
 *
 * Зачем вообще палитра. RGB565 тратит ошибку квантования ровно поровну на все
 * пиксели, сколько бы цветов в кадре ни было; палитра тратит её только там, где
 * цветов действительно много. На библиотеке GIF палитра дала RMSE 1.93 против
 * 2.80 у RGB565 — меньше ошибки при вдвое меньшем размере.
 *
 * Дизеринга нет, и он не нужен: устройство смешивает соседние кадры, и
 * покадровый шумовой узор читался бы как «шевеление».
 */
class Quantizer {
    private companion object {
        const val QBITS = 6
        const val QB = 1 shl QBITS          // 64
        const val QN = QB * QB * QB         // 262144 корзины гистограммы
        const val QSH = 8 - QBITS           // 2
        const val PAL = Geom.PAL_COLORS
        const val NPIX = Geom.IDX_BYTES
    }

    // Гистограмма переиспользуется между кадрами: после кадра обнуляются только
    // занятые корзины, поэтому эти 4 МБ выделяются один раз.
    private val cnt = IntArray(QN)
    private val sumR = IntArray(QN)
    private val sumG = IntArray(QN)
    private val sumB = IntArray(QN)
    private val near = ByteArray(QN)          // корзина → запись палитры
    private val key = IntArray(NPIX)          // пиксель → корзина
    private val live = IntArray(NPIX)         // занятые корзины, в порядке появления
    private val ord = IntArray(NPIX)
    private val tmp = IntArray(NPIX)
    private val bR = ByteArray(NPIX)
    private val bG = ByteArray(NPIX)
    private val bB = ByteArray(NPIX)

    private val blo = IntArray(PAL)
    private val bhi = IntArray(PAL)
    private val bcnt = DoubleArray(PAL)
    private val bext = IntArray(PAL)
    private val bax = IntArray(PAL)
    private val csC = IntArray(QB)

    /**
     * В [rgb] лежит NPIX × 3 байта в порядке сектор → диод.
     * Пишет 768 байт палитры и следом NPIX индексов в [out] по [outOff].
     */
    fun quantizeInto(rgb: ByteArray, out: ByteArray, outOff: Int) {
        var nLive = 0

        // ---- гистограмма ----
        var o = 0
        for (p in 0 until NPIX) {
            val r = rgb[o].toInt() and 0xFF
            val g = rgb[o + 1].toInt() and 0xFF
            val b = rgb[o + 2].toInt() and 0xFF
            o += 3
            val k = ((r shr QSH) shl (2 * QBITS)) or ((g shr QSH) shl QBITS) or (b shr QSH)
            key[p] = k
            if (cnt[k] == 0) { live[nLive++] = k }
            cnt[k]++
            sumR[k] += r; sumG[k] += g; sumB[k] += b
        }
        for (i in 0 until nLive) {
            val k = live[i]
            ord[i] = i
            bR[i] = (k ushr (2 * QBITS)).toByte()
            bG[i] = ((k ushr QBITS) and (QB - 1)).toByte()
            bB[i] = (k and (QB - 1)).toByte()
        }

        // ---- median cut ----
        // Боксы — полуинтервалы в ord. Счётчик, длиннейшая сторона и её ось
        // кэшируются: пересчитывать их для всех боксов на каждом шаге значило бы
        // 256 проходов по всей гистограмме, а так пересчёт трогает только две
        // половинки только что разрезанного бокса.
        var nb = 1
        blo[0] = 0; bhi[0] = nLive
        measure(0)

        while (nb < PAL) {
            // Режем бокс с максимумом «пикселей × длиннейшая сторона»: по одному
            // числу пикселей палитра уходит на большие ровные заливки, по одному
            // объёму — на редкий шум.
            var bi = -1
            var bs = -1.0
            for (i in 0 until nb) {
                if (bhi[i] - blo[i] < 2 || bext[i] == 0) continue
                val s = bcnt[i] * bext[i]
                if (s > bs) { bs = s; bi = i }
            }
            if (bi < 0) break

            val lo = blo[bi]; val hi = bhi[bi]; val ax = bax[bi]
            val co = when (ax) { 0 -> bR; 1 -> bG; else -> bB }

            // Сортировка подсчётом по координате оси: значений всего QB, а
            // полноценная сортировка была бы самой дорогой частью конвертации.
            java.util.Arrays.fill(csC, 0)
            for (j in lo until hi) csC[co[ord[j]].toInt() and 0xFF]++
            var s = 0
            for (v in 0 until QB) { val c = csC[v]; csC[v] = s; s += c }
            for (j in lo until hi) { val t = ord[j]; tmp[csC[co[t].toInt() and 0xFF]++] = t }
            for (j in lo until hi) ord[j] = tmp[j - lo]

            // Взвешенная медиана: делим по числу пикселей, а не корзин.
            val half = bcnt[bi] / 2
            var acc = 0.0
            var k = 1
            for (j in lo until hi) {
                acc += cnt[live[ord[j]]]
                if (acc >= half) { k = j - lo + 1; break }
            }
            if (k < 1) k = 1
            if (k > hi - lo - 1) k = hi - lo - 1

            bhi[bi] = lo + k
            blo[nb] = lo + k; bhi[nb] = hi
            measure(bi); measure(nb)
            nb++
        }

        // ---- палитра: среднее по боксу по ИСХОДНЫМ 8 битам ----
        java.util.Arrays.fill(out, outOff, outOff + Geom.PAL_BYTES, 0)
        for (i in 0 until nb) {
            var c = 0L; var ar = 0L; var ag = 0L; var ab = 0L
            for (j in blo[i] until bhi[i]) {
                val k = live[ord[j]]
                c += cnt[k]; ar += sumR[k]; ag += sumG[k]; ab += sumB[k]
            }
            if (c == 0L) continue
            out[outOff + i * 3]     = Math.round(ar.toDouble() / c).toInt().toByte()
            out[outOff + i * 3 + 1] = Math.round(ag.toDouble() / c).toInt().toByte()
            out[outOff + i * 3 + 2] = Math.round(ab.toDouble() / c).toInt().toByte()
        }

        // ---- индексы: БЛИЖАЙШАЯ запись палитры, а не запись своего бокса ----
        // Границы боксов не совпадают с границами Вороного, и у корзины с краю
        // соседний бокс нередко ближе. Поиск идёт по корзинам (их тысячи), а не
        // по пикселям (их 15840), поэтому обходится в единицы миллисекунд.
        for (i in 0 until nLive) {
            val k = live[i]
            val c = cnt[k]
            val mr = sumR[k].toDouble() / c
            val mg = sumG[k].toDouble() / c
            val mb = sumB[k].toDouble() / c
            var best = 0
            var bd = Double.MAX_VALUE
            var q = 0
            var po = outOff
            while (q < nb) {
                val dr = mr - (out[po].toInt() and 0xFF)
                val dg = mg - (out[po + 1].toInt() and 0xFF)
                val db = mb - (out[po + 2].toInt() and 0xFF)
                val d = dr * dr + dg * dg + db * db
                if (d < bd) { bd = d; best = q }
                q++; po += 3
            }
            near[k] = best.toByte()
        }
        val idxBase = outOff + Geom.PAL_BYTES
        for (p in 0 until NPIX) out[idxBase + p] = near[key[p]]

        // Чистим только занятые корзины: обнулять 262144 ячейки на каждый кадр
        // дороже, чем сам median cut.
        for (i in 0 until nLive) {
            val k = live[i]
            cnt[k] = 0; sumR[k] = 0; sumG[k] = 0; sumB[k] = 0
        }
    }

    private fun measure(i: Int) {
        val lo = blo[i]; val hi = bhi[i]
        var r0 = 255; var r1 = -1
        var g0 = 255; var g1 = -1
        var b0 = 255; var b1 = -1
        var c = 0L
        for (j in lo until hi) {
            val t = ord[j]
            val r = bR[t].toInt() and 0xFF
            val g = bG[t].toInt() and 0xFF
            val b = bB[t].toInt() and 0xFF
            if (r < r0) r0 = r
            if (r > r1) r1 = r
            if (g < g0) g0 = g
            if (g > g1) g1 = g
            if (b < b0) b0 = b
            if (b > b1) b1 = b
            c += cnt[live[t]]
        }
        val er = r1 - r0
        val eg = g1 - g0
        val eb = b1 - b0
        // Порядок при равенстве R > G > B, через строгое «больше», как в браузере.
        var e = er
        var ax = 0
        if (eg > e) { e = eg; ax = 1 }
        if (eb > e) { e = eb; ax = 2 }
        bcnt[i] = c.toDouble()
        bext[i] = e
        bax[i] = ax
    }
}
