package com.povwheel.app.convert

import android.graphics.Bitmap
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Обратное полярное преобразование: круглая миниатюра из RGB888 в порядке
 * сектор → диод. Та же геометрия (`Geom.R_INNER_FRAC` / `R_OUTER_FRAC`, дырка
 * под ступицу), что у миниатюр библиотеки в `ui/WheelThumb` — так превью при
 * загрузке показывает ровно то, что окажется на ободе, а не просто вписанный
 * квадрат: Crop/Fit, поля, центральное отверстие видны как есть.
 */
object DiscRender {

    /**
     * Прогоняет квадратный кадр [square] через полярную выборку и обратно.
     * [sampler] можно передать свой — при отрисовке клипа из десятков кадров это
     * снимает пересоздание внутренних буферов на каждом кадре.
     */
    fun fromSquare(square: Bitmap, size: Int, sampler: PolarSampler = PolarSampler()): Bitmap {
        sampler.sample(square, 3)
        return fromRgb(sampler.rgbBuffer, Geom.SECTORS, Geom.LEDS_PER_SIDE, size)
    }

    /** [rgb] — RGB888, `sectors × radii` в порядке сектор → диод. */
    fun fromRgb(rgb: ByteArray, sectors: Int, radii: Int, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        val cx = size / 2.0
        val cy = size / 2.0
        val minR = size * Geom.R_INNER_FRAC
        val maxR = size * Geom.R_OUTER_FRAC
        val dR = (maxR - minR) / (radii - 1)
        val twoPi = Math.PI * 2

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cy
                val rad = hypot(dx, dy)
                if (rad < minR - dR * 0.5 || rad > maxR + dR * 0.5) continue
                var ang = atan2(dy, dx)
                if (ang < 0) ang += twoPi
                var sec = (ang / twoPi * sectors).toInt()
                if (sec < 0) sec = 0
                if (sec >= sectors) sec = sectors - 1
                var led = ((rad - minR) / dR).roundToInt()
                if (led < 0) led = 0
                if (led >= radii) led = radii - 1
                val o = (sec * radii + led) * 3
                if (o + 2 >= rgb.size) continue
                val r = rgb[o].toInt() and 0xFF
                val gg = rgb[o + 1].toInt() and 0xFF
                val b = rgb[o + 2].toInt() and 0xFF
                px[y * size + x] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
            }
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }
}
