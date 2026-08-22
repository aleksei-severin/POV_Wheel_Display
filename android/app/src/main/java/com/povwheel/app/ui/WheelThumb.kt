package com.povwheel.app.ui

import android.graphics.Bitmap
import com.povwheel.app.ble.PreviewFrame
import com.povwheel.app.convert.Geom
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Рисует кадр устройства обратно круглой миниатюрой — обратное полярное
 * преобразование. Заодно это независимая проверка порядка индексов: перепутай
 * секторы с диодами, и картинка размажется.
 */
object WheelThumb {

    fun render(p: PreviewFrame, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        val cx = size / 2.0
        val cy = size / 2.0
        val minR = size * Geom.R_INNER_FRAC
        val maxR = size * Geom.R_OUTER_FRAC
        val dR = (maxR - minR) / (p.radii - 1)
        val twoPi = Math.PI * 2

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cy
                val rad = hypot(dx, dy)
                if (rad < minR - dR * 0.5 || rad > maxR + dR * 0.5) continue
                var ang = atan2(dy, dx)
                if (ang < 0) ang += twoPi
                var sec = (ang / twoPi * p.sectors).toInt()
                if (sec < 0) sec = 0
                if (sec >= p.sectors) sec = p.sectors - 1
                var led = ((rad - minR) / dR).roundToInt()
                if (led < 0) led = 0
                if (led >= p.radii) led = p.radii - 1
                val o = (sec * p.radii + led) * 2
                if (o + 1 >= p.rgb565.size) continue
                val v = (p.rgb565[o].toInt() and 0xFF) or ((p.rgb565[o + 1].toInt() and 0xFF) shl 8)
                val r5 = (v shr 11) and 0x1F
                val g6 = (v shr 5) and 0x3F
                val b5 = v and 0x1F
                // Повторяем старшие биты, чтобы полный код доходил до 255.
                val r = (r5 shl 3) or (r5 shr 2)
                val g = (g6 shl 2) or (g6 shr 4)
                val b = (b5 shl 3) or (b5 shr 2)
                px[y * size + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }
}
