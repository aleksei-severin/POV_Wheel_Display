package com.povwheel.app.convert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Эффект «Текст»: строка вдоль обода, низ букв к центру.
 *
 * Рисует телефон, а не колесо: системные шрифты, кириллица и честное
 * сглаживание здесь бесплатны, а в прошивку влез бы разве что растровый шрифт
 * 5×7. Строка ложится на квадратный холст по дуге (drawTextOnPath) и проходит
 * ту же полярную выборку, что и картинки при заливке ([PolarSampler]), — так
 * у текста те же оси, тот же «верх» и то же сглаживание по ячейке, что у любого
 * залитого файла. На колесо уходит только яркость 360 × 44 — цвет (в том числе
 * радугу, которая живёт во времени) накладывает колесо.
 *
 * Размер шрифта подбирается сам: короткая строка — крупно (до [MAX_CAP_FRAC]
 * длины луча), длинная ужимается, пока не уложится в дугу. [MAX_CHARS] выбран
 * так, чтобы даже из широких букв строка оставалась читаемой — около 9 диодов
 * по высоте.
 */
object TextMask {
    const val MAX_CHARS = 40

    private const val S = 800                 // сторона рабочего холста, px
    private const val SS = 4                  // подвыборок на ячейку по каждой оси
    private const val ARC_FILL = 0.92         // доля окружности под строку: зазор между концами
    private const val MAX_CAP_FRAC = 0.40     // предел высоты букв — доля длины луча

    private val canvasBmp: Bitmap by lazy { Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888) }
    private val sampler by lazy { PolarSampler() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        // Межбуквенный зазор чуть шире обычного: угловое размытие на ободе
        // склеивает соседние буквы раньше, чем на экране.
        letterSpacing = 0.06f
    }

    /** Маска яркости 360 × 44 (сектор → диод). Пустая строка — пустая маска. */
    @Synchronized
    fun render(text: String): ByteArray {
        val mask = ByteArray(Geom.IDX_BYTES)
        if (text.isBlank()) return mask

        val c = Canvas(canvasBmp)
        c.drawColor(Color.BLACK)
        val rOut = S * Geom.R_OUTER_FRAC
        val rIn = S * Geom.R_INNER_FRAC
        val step = (rOut - rIn) / (Geom.LEDS_PER_SIDE - 1)
        // Верх букв — на полшага внутрь от крайнего диода, иначе крайний ряд
        // получал бы только половину яркости штриха.
        val top = rOut - step * 0.5

        // Меряем на кегле 100 и масштабируем линейно.
        paint.textSize = 100f
        val w100 = paint.measureText(text).toDouble()
        // Высота над базовой линией: не меньше заглавной «H», чтобы строка не
        // прыгала, когда в неё добавляется первая заглавная.
        val b = Rect()
        paint.getTextBounds("H", 0, 1, b)
        val capH = -b.top.toDouble()
        paint.getTextBounds(text, 0, text.length, b)
        val asc100 = maxOf(capH, -b.top.toDouble())
        if (w100 <= 0 || asc100 <= 0) return mask

        // Ширина строки по базовой линии не больше дуги ARC_FILL·2π·rb, где
        // rb = top − k·asc100: k·w100 ≤ A·(top − k·asc100).
        val a = ARC_FILL * 2 * PI
        val kW = a * top / (w100 + a * asc100)
        val kH = MAX_CAP_FRAC * (rOut - rIn) / asc100
        val k = minOf(kW, kH)
        paint.textSize = (100 * k).toFloat()

        val rb = top - k * asc100
        val width = paint.measureText(text).toDouble()
        val spanDeg = width / rb * 180 / PI
        // Дуга по часовой стрелке (экранные оси, y вниз) с серединой строки
        // наверху (270°): на такой дуге drawTextOnPath ставит буквы снаружи,
        // низом к центру, и строка читается слева направо.
        val oval = RectF((S / 2 - rb).toFloat(), (S / 2 - rb).toFloat(), (S / 2 + rb).toFloat(), (S / 2 + rb).toFloat())
        val path = Path().apply { addArc(oval, (270 - spanDeg / 2).toFloat(), 359.9f) }
        c.drawTextOnPath(text, path, 0f, 0f, paint)

        sampler.sample(canvasBmp, SS)
        val rgb = sampler.rgbBuffer
        // Белый текст на чёрном: яркость — любой канал, берём зелёный.
        for (i in 0 until Geom.IDX_BYTES) mask[i] = rgb[i * 3 + 1]
        return mask
    }

    /** Блоб для колеса: raw deflate от [u8 len][строка UTF-8][маска]. */
    fun pack(text: String, mask: ByteArray): ByteArray {
        val t = text.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        val raw = ByteArray(1 + t.size + mask.size)
        raw[0] = t.size.toByte()
        t.copyInto(raw, 1)
        mask.copyInto(raw, 1 + t.size)
        // nowrap = true — без zlib-заголовка: так ждёт tinfl на колесе.
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            d.setInput(raw)
            d.finish()
            val out = ByteArrayOutputStream(4096)
            val buf = ByteArray(4096)
            while (!d.finished()) {
                val n = d.deflate(buf)
                if (n == 0 && d.needsInput()) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    /** Обратное к [pack]: строка и маска, null — блоб пустой или битый. */
    fun unpack(blob: ByteArray): Pair<String, ByteArray>? {
        if (blob.isEmpty()) return null
        val inf = Inflater(true)
        try {
            // Inflater с nowrap просит лишний байт в конце входа.
            inf.setInput(blob + byteArrayOf(0))
            val raw = ByteArray(1 + 255 + Geom.IDX_BYTES)
            var n = 0
            while (n < raw.size && !inf.finished()) {
                val got = inf.inflate(raw, n, raw.size - n)
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += got
            }
            val len = raw[0].toInt() and 0xFF
            if (n != 1 + len + Geom.IDX_BYTES) return null
            val text = String(raw, 1, len, Charsets.UTF_8)
            return text to raw.copyOfRange(1 + len, n)
        } catch (e: Exception) {
            return null
        } finally {
            inf.end()
        }
    }

    /**
     * Маска обратно в круглую картинку — для превью и миниатюры. Белый с
     * прозрачностью = маске: цвет (или бегущую радугу) накладывает экран, ровно
     * как колесо красит ту же маску. Геометрия та же, что у `DiscRender`.
     */
    fun disc(mask: ByteArray, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        val cx = size / 2.0
        val minR = size * Geom.R_INNER_FRAC
        val maxR = size * Geom.R_OUTER_FRAC
        val dR = (maxR - minR) / (Geom.LEDS_PER_SIDE - 1)
        val twoPi = PI * 2
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x + 0.5 - cx
                val dy = y + 0.5 - cx
                val rad = hypot(dx, dy)
                if (rad < minR - dR * 0.5 || rad > maxR + dR * 0.5) continue
                var ang = atan2(dy, dx)
                if (ang < 0) ang += twoPi
                val sec = (ang / twoPi * Geom.SECTORS).toInt().coerceIn(0, Geom.SECTORS - 1)
                val led = ((rad - minR) / dR).roundToInt().coerceIn(0, Geom.LEDS_PER_SIDE - 1)
                val a = mask[sec * Geom.LEDS_PER_SIDE + led].toInt() and 0xFF
                if (a != 0) px[y * size + x] = (a shl 24) or 0xFFFFFF
            }
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }
}
