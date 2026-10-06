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
 * длины луча), длинная ужимается, пока не замкнёт круг: конец строки сходится с
 * началом через зазор ровно в пробел. [MAX_CHARS] выбран так, чтобы даже из
 * широких букв строка оставалась читаемой — около 9 диодов по высоте.
 */
object TextMask {
    const val MAX_CHARS = 40

    private const val S = 800                 // сторона рабочего холста, px
    private const val SS = 4                  // подвыборок на ячейку по каждой оси
    private const val MAX_CAP_FRAC = 0.60     // предел высоты букв — доля длины луча

    private val canvasBmp: Bitmap by lazy { Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888) }
    private val sampler by lazy { PolarSampler() }
    // LINEAR_TEXT + SUBPIXEL — ширина строки строго пропорциональна кеглю: кегль
    // считается по замеру, и хинтованные (округлённые до пикселя) ширины
    // разошлись бы с ним — стык конца и начала строки уехал бы на букву.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
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

        // Меры на единицу кегля: ширина строки, ширина пробела (зазор на стыке
        // конца и начала) и высота над базовой линией — не меньше заглавной «H»,
        // чтобы строка не прыгала, когда в неё добавляется первая заглавная.
        val b = Rect()
        var size = 100.0
        var w1 = 0.0
        var asc1 = 0.0
        // Второй проход — уточнение на найденном кегле: высота букв по
        // getTextBounds целочисленная и на кегле 100 меряется грубо.
        repeat(2) {
            paint.textSize = size.toFloat()
            w1 = paint.measureText(text) / size
            val sp1 = paint.measureText(" ") / size
            paint.getTextBounds("H", 0, 1, b)
            val capH = -b.top.toDouble()
            paint.getTextBounds(text, 0, text.length, b)
            asc1 = maxOf(capH, -b.top.toDouble()) / size
            if (w1 <= 0 || asc1 <= 0) return mask
            // Строка плюс пробел — не длиннее окружности по базовой линии:
            // s·(w1 + sp1) ≤ 2π·(top − s·asc1), где top − s·asc1 — её радиус.
            val sW = 2 * PI * top / (w1 + sp1 + 2 * PI * asc1)
            val sH = MAX_CAP_FRAC * (rOut - rIn) / asc1
            size = minOf(sW, sH)
        }
        paint.textSize = size.toFloat()

        val rb = top - size * asc1
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
        val idx = discIndex(size)
        val px = IntArray(size * size)
        for (i in px.indices) {
            val k = idx[i]
            if (k < 0) continue
            val a = mask[k].toInt() and 0xFF
            if (a != 0) px[i] = (a shl 24) or 0xFFFFFF
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    // Пиксель картинки → ячейка маски (−1 — вне кольца диодов), по размеру. Часы
    // перерисовывают миниатюру каждую секунду, и считать atan2 на каждый пиксель
    // заново незачем: геометрия от маски не зависит.
    private val discIdx = HashMap<Int, IntArray>()

    @Synchronized
    private fun discIndex(size: Int): IntArray = discIdx.getOrPut(size) {
        val idx = IntArray(size * size) { -1 }
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
                idx[y * size + x] = sec * Geom.LEDS_PER_SIDE + led
            }
        }
        idx
    }
}
