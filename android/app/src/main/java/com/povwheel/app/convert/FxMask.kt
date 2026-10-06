package com.povwheel.app.convert

import android.graphics.Bitmap
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Миниатюры эффектов, которые колесо рисует само (Speed, Clock, Rainbow), — тем
 * же алгоритмом, что прошивка (src/effects.cpp): тот же шрифт 5×7, та же
 * раскладка строк, та же полярная выборка с усреднением по ячейке, та же
 * таблица радуги. Тогда плитка показывает ровно то, что на ободе, а не
 * «похожую» картинку.
 *
 * Маски — 360 × 44 (сектор → диод), как у «Текста»: в картинку их переводит
 * [TextMask.disc], красит экран.
 */
object FxMask {
    // ---- Шрифт и раскладка: копия effects.cpp ----

    // Единицы, в которых крайний диод на радиусе 47.5, центр в нуле, ось y вниз.
    private const val SS = 4
    private const val R_OUT = 47.5
    private const val LED_R_INNER_MM = 49.0
    private const val LED_R_OUTER_MM = 273.0

    private const val G_K = 10
    private const val G_M = 11
    private const val G_SLASH = 12
    private const val G_H = 13
    private const val G_DOT = 14
    private const val G_DASH = 15

    private val FONT57 = arrayOf(
        intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E), // 0
        intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E), // 1
        intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F), // 2
        intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E), // 3
        intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02), // 4
        intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E), // 5
        intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E), // 6
        intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08), // 7
        intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E), // 8
        intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C), // 9
        intArrayOf(0x10, 0x10, 0x12, 0x14, 0x18, 0x14, 0x12), // k
        intArrayOf(0x00, 0x00, 0x1A, 0x15, 0x15, 0x15, 0x15), // m
        intArrayOf(0x01, 0x02, 0x02, 0x04, 0x08, 0x08, 0x10), // /
        intArrayOf(0x10, 0x10, 0x16, 0x19, 0x11, 0x11, 0x11), // h
        intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10), // .  (ширина 1)
        intArrayOf(0x00, 0x00, 0x00, 0x0E, 0x00, 0x00, 0x00)  // -
    )
    private fun glyphW(g: Int) = if (g == G_DOT) 1 else 5

    private const val CLK_SC_TIME = 2.0
    private const val CLK_SC_DATE = 1.6

    /** Строка, разложенная по столбцам: в каждом — 7 бит горящих строк шрифта. */
    private class Run(val x0: Double, val y0: Double, val sc: Double, val col: IntArray)

    /** По центру по горизонтали; [y0] — верх строки. */
    private fun run(g: IntArray, y0: Double, sc: Double): Run {
        val cols = ArrayList<Int>()
        for (k in g.indices) {
            if (k > 0) cols.add(0)                              // столбец-зазор
            for (x in 0 until glyphW(g[k])) {
                val bit = if (g[k] == G_DOT) 0x10 else (0x10 shr x)
                var bits = 0
                for (row in 0 until 7) if (FONT57[g[k]][row] and bit != 0) bits = bits or (1 shl row)
                cols.add(bits)
            }
        }
        return Run(-cols.size * sc * 0.5, y0, sc, cols.toIntArray())
    }

    // Точки выборки внутри ячеек — один раз на процесс.
    private val jit = DoubleArray(SS) { (it + 0.5) / SS - 0.5 }
    private val ca = DoubleArray(Geom.SECTORS * SS)
    private val sa = DoubleArray(Geom.SECTORS * SS)
    private val ledR = DoubleArray(Geom.LEDS_PER_SIDE)
    private val pitch = (LED_R_OUTER_MM - LED_R_INNER_MM) / (Geom.LEDS_PER_SIDE - 1) / LED_R_OUTER_MM * R_OUT
    init {
        for (s in 0 until Geom.SECTORS) for (k in 0 until SS) {
            val a = (s + jit[k]) * PI / 180.0
            ca[s * SS + k] = cos(a); sa[s * SS + k] = sin(a)
        }
        val step = (LED_R_OUTER_MM - LED_R_INNER_MM) / (Geom.LEDS_PER_SIDE - 1)
        for (i in 0 until Geom.LEDS_PER_SIDE) ledR[i] = (LED_R_INNER_MM + i * step) / LED_R_OUTER_MM * R_OUT
    }

    private fun render(runs: Array<Run>): ByteArray {
        val out = ByteArray(Geom.IDX_BYTES)
        val full = SS * SS
        for (s in 0 until Geom.SECTORS) {
            for (i in 0 until Geom.LEDS_PER_SIDE) {
                var hits = 0
                for (ka in 0 until SS) {
                    val c = ca[s * SS + ka]
                    val sn = sa[s * SS + ka]
                    for (kr in 0 until SS) {
                        val rr = ledR[i] + jit[kr] * pitch
                        val x = rr * c
                        val y = rr * sn
                        for (r in runs) {
                            val u = (x - r.x0) / r.sc
                            val v = (y - r.y0) / r.sc
                            if (u < 0 || v < 0 || v >= 7.0 || u >= r.col.size) continue
                            if ((r.col[u.toInt()] shr v.toInt()) and 1 != 0) { hits++; break }
                        }
                    }
                }
                out[s * Geom.LEDS_PER_SIDE + i] = ((hits * 255 + full / 2) / full).toByte()
            }
        }
        return out
    }

    /** Маска эффекта Speed для [kmh] (как на колесе: число над центром, «km/h» под ним). */
    fun speed(kmh: Int): ByteArray {
        val v = kmh.coerceIn(0, 999)
        val dig = when {
            v >= 100 -> intArrayOf(v / 100, v / 10 % 10, v % 10)
            v >= 10 -> intArrayOf(v / 10, v % 10)
            else -> intArrayOf(v)
        }
        val sc = if (dig.size >= 3) 3.0 else 4.0
        return render(arrayOf(
            run(dig, -10.0 - 7.0 * sc, sc),
            run(intArrayOf(G_K, G_M, G_SLASH, G_H), 10.0, 2.0)
        ))
    }

    /** Маска часов: «hh.mm.ss» над центром, «yyyy.mm.dd» под ним; null — прочерки. */
    fun clock(t: LocalDateTime?): ByteArray {
        val tm: IntArray
        val dt: IntArray
        if (t != null) {
            val y = t.year
            tm = intArrayOf(t.hour / 10, t.hour % 10, G_DOT, t.minute / 10, t.minute % 10, G_DOT,
                t.second / 10, t.second % 10)
            dt = intArrayOf(y / 1000 % 10, y / 100 % 10, y / 10 % 10, y % 10, G_DOT,
                t.monthValue / 10, t.monthValue % 10, G_DOT, t.dayOfMonth / 10, t.dayOfMonth % 10)
        } else {
            tm = IntArray(8) { if (it == 2 || it == 5) G_DOT else G_DASH }
            dt = IntArray(10) { if (it == 4 || it == 7) G_DOT else G_DASH }
        }
        return render(arrayOf(
            run(tm, -10.0 - 7.0 * CLK_SC_TIME, CLK_SC_TIME),
            run(dt, 10.0, CLK_SC_DATE)
        ))
    }

    // ---- Цвет ----

    /** hsv2rgb из effects.cpp, 8-битный тон: 0 — красный, 85 — зелёный, 170 — синий. → 0xRRGGBB */
    fun hsv8(h: Int, s: Int, v: Int): Int {
        val hh = (h and 0xFF) * 6
        val sec = hh shr 8
        val f = hh and 0xFF
        val p = (v * (255 - s)) shr 8
        val q = (v * (255 - ((s * f) shr 8))) shr 8
        val t = (v * (255 - ((s * (255 - f)) shr 8))) shr 8
        val (r, g, b) = when (sec) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return (r shl 16) or (g shl 8) or b
    }

    /** Цвет цифр Speed: от зелёного к красному на [red] км/ч, через жёлтый. */
    fun speedColor(kmh: Int, red: Int): Int {
        val k = (kmh.toFloat() / red.coerceAtLeast(1)).coerceAtMost(1f)
        return hsv8((85f * (1f - k) + 0.5f).toInt(), 255, 255)
    }

    // ---- Радуга ----

    /** Семь цветов радуги, тон в градусах; последний замыкает круг (effects.cpp, RB_ANCHORS). */
    private val ANCHORS = floatArrayOf(0f, 30f, 60f, 120f, 190f, 240f, 280f, 360f)
    const val RB_LUT = 1024

    private fun rainbowHue(f: Float, p: Float): Float {
        val x = f * 7f
        val k = x.toInt().coerceAtMost(6)
        val t = x - k
        val g = if (p >= 0.999f) (if (t < 0.5f) 0f else 1f)
                else ((t - p * 0.5f) / (1f - p)).coerceIn(0f, 1f)
        val band = ANCHORS[k] + g * (ANCHORS[k + 1] - ANCHORS[k])
        return (1f - p) * f * 360f + p * band
    }

    /** Таблица радуги для резкости [sharp] (0…100): положение на круге (10 бит) → ARGB. */
    fun rainbowLut(sharp: Int): IntArray {
        val p = sharp.coerceIn(0, 100) / 100f
        return IntArray(RB_LUT) { u ->
            val h = rainbowHue(u.toFloat() / RB_LUT, p) % 360f
            val hh = h / 60f
            val sec = hh.toInt()
            val up = (255f * (hh - sec)).roundToInt()
            val dn = 255 - up
            val (r, g, b) = when (sec % 6) {
                0 -> Triple(255, up, 0)
                1 -> Triple(dn, 255, 0)
                2 -> Triple(0, 255, up)
                3 -> Triple(0, dn, 255)
                4 -> Triple(up, 0, 255)
                else -> Triple(255, 0, dn)
            }
            // Те же 5/6/5 бит, что уйдут в RGB565 на колесе.
            (0xFF shl 24) or ((r and 0xF8) shl 16) or ((g and 0xFC) shl 8) or (b and 0xF8)
        }
    }

    /**
     * Круглая картинка радуги [size] × [size] — как на ободе: положение на круге —
     * сектор плюс сдвиг во времени плюс 12 на диод (спираль). Геометрия — та же,
     * что у [TextMask.disc]; таблица точек считается один раз, кадр — одно
     * чтение таблицы цвета на пиксель.
     */
    class RainbowDisc(val size: Int) {
        private val base = IntArray(size * size) { -1 }
        private val px = IntArray(size * size)
        val bitmap: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

        init {
            val cx = size / 2.0
            val minR = size * Geom.R_INNER_FRAC
            val maxR = size * Geom.R_OUTER_FRAC
            val dR = (maxR - minR) / (Geom.LEDS_PER_SIDE - 1)
            for (y in 0 until size) for (x in 0 until size) {
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cx
                val rad = hypot(dx, dy)
                if (rad < minR - dR * 0.5 || rad > maxR + dR * 0.5) continue
                var ang = atan2(dy, dx)
                if (ang < 0) ang += 2 * PI
                val sec = (ang / (2 * PI) * Geom.SECTORS).toInt().coerceIn(0, Geom.SECTORS - 1)
                val led = ((rad - minR) / dR).roundToInt().coerceIn(0, Geom.LEDS_PER_SIDE - 1)
                base[y * size + x] = sec * RB_LUT / Geom.SECTORS + led * 12
            }
        }

        /** [phase] — сдвиг во времени, 0…1023 на полный круг. */
        fun draw(lut: IntArray, phase: Int): Bitmap {
            for (i in base.indices) {
                val b = base[i]
                px[i] = if (b < 0) 0 else lut[(b + phase) and (RB_LUT - 1)]
            }
            bitmap.setPixels(px, 0, size, 0, 0, size, size)
            return bitmap
        }
    }
}
