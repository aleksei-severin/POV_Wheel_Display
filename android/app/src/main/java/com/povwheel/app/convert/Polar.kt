package com.povwheel.app.convert

import android.graphics.Bitmap

/**
 * Преобразование картинки в кадр устройства.
 *
 * Это намеренно построчный перенос браузерного кода из data/index.html
 * (toPolarBuffer + quantizeFrame). Точность обязательна: файлы, сделанные
 * телефоном, и файлы, сделанные старой веб-страницей, должны выглядеть
 * одинаково, и каждая константа здесь несущая.
 */
object Geom {
    const val LEDS_PER_SIDE = 44
    const val SECTORS = 360

    /** Крайний диод (273 мм) приходится на край изображения. */
    const val R_OUTER_FRAC = 0.495

    /**
     * Внутренний диод, 49 мм. Считается слева направо ровно так же, как в
     * JavaScript: (0.495*49)/273, а не 0.495*(49/273). Значения расходятся в
     * последнем бите, и этого хватает, чтобы сместить точки выборки.
     */
    const val R_INNER_FRAC = 0.495 * 49 / 273

    const val PAL_COLORS = 256
    const val PAL_BYTES = PAL_COLORS * 3          // 768
    const val IDX_BYTES = SECTORS * LEDS_PER_SIDE // 15840
    const val FRAME_STRIDE = PAL_BYTES + IDX_BYTES // 16608

    const val SRC_SIZE_IMG = 600   // рабочий холст для статичной картинки
    const val SRC_SIZE_GIF = 400   // кадров анимации много, экономим время
    const val SRC_SIZE_VID = 400
}

/**
 * Полярная выборка с усреднением по ячейке.
 *
 * На периферии один сектор (1°) занимает несколько пикселей исходника, поэтому
 * выборка «ближайшего соседа» рвала границы линий ещё до отправки на
 * устройство. Усредняем ss×ss точек внутри ячейки «сектор 1° × кольцо в шаг
 * диодов», каждую берём билинейно — граница попадает в промежуточный уровень,
 * а не прыгает на целый сектор.
 */
class PolarSampler {
    // Переиспользуется между кадрами: иначе длинная анимация выделила бы сотни
    // мегабайт временных буферов.
    private val rgb = ByteArray(Geom.IDX_BYTES * 3)
    private var pixels: IntArray = IntArray(0)

    /** Выбирает [src] и кладёт квантованный кадр в [out] по смещению [outOff]. */
    fun frameInto(src: Bitmap, ss: Int, quant: Quantizer, out: ByteArray, outOff: Int) {
        sample(src, ss)
        quant.quantizeInto(rgb, out, outOff)
    }

    /** Заполняет внутренний буфер RGB888 в порядке сектор → диод. */
    fun sample(src: Bitmap, ssIn: Int) {
        val ss = if (ssIn <= 0) 3 else ssIn
        val w = src.width
        val h = src.height
        if (pixels.size < w * h) pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val cx = w / 2.0
        val cy = h / 2.0
        val minR = w * Geom.R_INNER_FRAC
        val maxR = w * Geom.R_OUTER_FRAC
        val dR = (maxR - minR) / (Geom.LEDS_PER_SIDE - 1)
        val deg = Math.PI / 180.0

        // Смещения подвыборок внутри ячейки: −0.5 … +0.5 её размера
        val jit = DoubleArray(ss)
        for (k in 0 until ss) jit[k] = (k + 0.5) / ss - 0.5

        val ca = DoubleArray(360 * ss)
        val sa = DoubleArray(360 * ss)
        for (s in 0 until 360) {
            for (k in 0 until ss) {
                val a = (s + jit[k]) * deg
                ca[s * ss + k] = Math.cos(a)
                sa[s * ss + k] = Math.sin(a)
            }
        }

        val n = ss * ss
        var off = 0
        for (sector in 0 until 360) {
            for (r in 0 until Geom.LEDS_PER_SIDE) {
                val rad0 = minR + r * dR
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                for (ia in 0 until ss) {
                    val c = ca[sector * ss + ia]
                    val s2 = sa[sector * ss + ia]
                    for (ir in 0 until ss) {
                        val rad = rad0 + jit[ir] * dR
                        val px = cx + rad * c
                        val py = cy + rad * s2
                        var x0 = Math.floor(px).toInt()
                        var y0 = Math.floor(py).toInt()
                        // fx/fy берутся от НЕЗАЖАТОГО floor, ровно как в браузере:
                        // зажать раньше — значит сдвинуть веса.
                        val fx = px - x0
                        val fy = py - y0
                        var x1 = x0 + 1
                        var y1 = y0 + 1
                        if (x0 < 0) x0 = 0 else if (x0 > w - 1) x0 = w - 1
                        if (x1 < 0) x1 = 0 else if (x1 > w - 1) x1 = w - 1
                        if (y0 < 0) y0 = 0 else if (y0 > h - 1) y0 = h - 1
                        if (y1 < 0) y1 = 0 else if (y1 > h - 1) y1 = h - 1
                        val p00 = pixels[y0 * w + x0]
                        val p10 = pixels[y0 * w + x1]
                        val p01 = pixels[y1 * w + x0]
                        val p11 = pixels[y1 * w + x1]
                        val w00 = (1 - fx) * (1 - fy)
                        val w10 = fx * (1 - fy)
                        val w01 = (1 - fx) * fy
                        val w11 = fx * fy
                        // Альфа игнорируется, как и в браузере: очищенный холст
                        // читается как чёрный — именно этого и ждём от полей.
                        sumR += ((p00 shr 16) and 0xFF) * w00 + ((p10 shr 16) and 0xFF) * w10 +
                                ((p01 shr 16) and 0xFF) * w01 + ((p11 shr 16) and 0xFF) * w11
                        sumG += ((p00 shr 8) and 0xFF) * w00 + ((p10 shr 8) and 0xFF) * w10 +
                                ((p01 shr 8) and 0xFF) * w01 + ((p11 shr 8) and 0xFF) * w11
                        sumB += (p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 +
                                (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11
                    }
                }
                // Округление, а не отбрасывание дробной части: усечение
                // систематически затемняло бы кадр на пол-уровня.
                rgb[off++] = min255(sumR / n + 0.5)
                rgb[off++] = min255(sumG / n + 0.5)
                rgb[off++] = min255(sumB / n + 0.5)
            }
        }
    }

    private fun min255(v: Double): Byte {
        val i = v.toInt()
        return (if (i > 255) 255 else if (i < 0) 0 else i).toByte()
    }
}
