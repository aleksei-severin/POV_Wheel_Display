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
 * Эмодзи — исключение: они сохраняют свой цвет при любом выбранном (жёлтый
 * смайлик, красное сердечко). Отличить их от букв по коду символа ненадёжно
 * (вариационные селекторы, ZWJ-последовательности, флаги), поэтому строка
 * рисуется дважды — белой кистью и чёрной. Цветной глиф кисти не слушается и
 * в обоих проходах одинаков, буква — нет: разность проходов и есть маска букв,
 * а второй проход — слой цвета эмодзи ([TextImage.rgb]), который колесо
 * кладёт поверх как есть.
 *
 * Размер шрифта подбирается сам: короткая строка — крупно (до [MAX_CAP_FRAC]
 * длины луча), длинная ужимается, пока не замкнёт круг: конец строки сходится с
 * началом через зазор ровно в пробел. [MAX_CHARS] выбран так, чтобы даже из
 * широких букв строка оставалась читаемой — около 9 диодов по высоте.
 */
object TextMask {
    const val MAX_CHARS = 40

    /** Предел сжатого блоба — TEXT_COMP_MAX в include/effects.h. */
    const val MAX_BLOB = 40 * 1024

    private const val S = 800                 // сторона рабочего холста, px
    private const val SS = 4                  // подвыборок на ячейку по каждой оси
    private const val MAX_CAP_FRAC = 0.60     // предел высоты букв — доля длины луча
    private const val RGB_BYTES = Geom.IDX_BYTES * 3

    /**
     * Отрисованный текст, 360 × 44 (сектор → диод): [mask] — покрытие букв, их
     * красит колесо; [rgb] — RGB888 цветных глифов, уже умноженный на их
     * покрытие (нарисован на чёрном); null — эмодзи в строке нет.
     */
    class TextImage(val mask: ByteArray, val rgb: ByteArray?) {
        /**
         * Для прошивки без слоя цвета (нет FEAT_TEXT_RGB): эмодзи уходят в маску
         * яркостью и красятся, как буквы, — как было до слоя. Яркость — по
         * старшему каналу: по одному зелёному красное сердечко пропало бы.
         */
        fun flat(): TextImage {
            val c = rgb ?: return this
            val m = ByteArray(mask.size)
            for (i in m.indices) {
                val e = maxOf(c[i * 3].toInt() and 0xFF, c[i * 3 + 1].toInt() and 0xFF, c[i * 3 + 2].toInt() and 0xFF)
                m[i] = minOf(255, (mask[i].toInt() and 0xFF) + e).toByte()
            }
            return TextImage(m, null)
        }
    }

    /** Обрезать до [MAX_CHARS], не разрывая суррогатную пару (эмодзи — две UTF-16 единицы). */
    fun clip(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val n = if (Character.isHighSurrogate(text[MAX_CHARS - 1])) MAX_CHARS - 1 else MAX_CHARS
        return text.substring(0, n)
    }

    /**
     * Отрисовать и упаковать для колеса. [colour] — колесо умеет слой цвета
     * эмодзи (FEAT_TEXT_RGB); без него или если блоб со слоем не влез в
     * [MAX_BLOB] — эмодзи уходят в маску, как раньше. Возвращает ровно то, что
     * покажет колесо, и блоб.
     */
    fun build(text: String, colour: Boolean): Pair<TextImage, ByteArray> {
        val img = render(text)
        if (colour && img.rgb != null) {
            val blob = pack(text, img)
            if (blob.size <= MAX_BLOB) return img to blob
        }
        val f = img.flat()
        return f to pack(text, f)
    }

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

    /** Маска букв и слой эмодзи 360 × 44 (сектор → диод). Пустая строка — пустая маска. */
    @Synchronized
    fun render(text: String): TextImage {
        val mask = ByteArray(Geom.IDX_BYTES)
        if (text.isBlank()) return TextImage(mask, null)

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
            if (w1 <= 0 || asc1 <= 0) return TextImage(mask, null)
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

        // Проход 1 — белой кистью: буквы белые, эмодзи своим цветом.
        c.drawTextOnPath(text, path, 0f, 0f, paint)
        sampler.sample(canvasBmp, SS)
        val lit = sampler.rgbBuffer.copyOf()
        // Проход 2 — чёрной: буквы исчезают, эмодзи те же. Геометрия та же,
        // так что разность — ровно покрытие букв, и выборка у обоих одна.
        c.drawColor(Color.BLACK)
        paint.color = Color.BLACK
        try {
            c.drawTextOnPath(text, path, 0f, 0f, paint)
        } finally {
            paint.color = Color.WHITE
        }
        sampler.sample(canvasBmp, SS)
        val fix = sampler.rgbBuffer
        var any = false
        for (i in 0 until Geom.IDX_BYTES) {
            // Белые буквы: яркость — любой канал, берём зелёный.
            val t = (lit[i * 3 + 1].toInt() and 0xFF) - (fix[i * 3 + 1].toInt() and 0xFF)
            mask[i] = t.coerceIn(0, 255).toByte()
            if (!any && (fix[i * 3].toInt() or fix[i * 3 + 1].toInt() or fix[i * 3 + 2].toInt()) != 0) any = true
        }
        return TextImage(mask, if (any) fix.copyOf() else null)
    }

    /**
     * Блоб для колеса: raw deflate от [u8 len][строка UTF-8][маска] и, если в
     * строке есть эмодзи, следом [RGB888 360 × 44].
     */
    fun pack(text: String, img: TextImage): ByteArray {
        val t = text.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        val mask = img.mask
        val rgb = img.rgb
        val raw = ByteArray(1 + t.size + mask.size + (rgb?.size ?: 0))
        raw[0] = t.size.toByte()
        t.copyInto(raw, 1)
        mask.copyInto(raw, 1 + t.size)
        rgb?.copyInto(raw, 1 + t.size + mask.size)
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

    /** Обратное к [pack]: строка и картинка, null — блоб пустой или битый. */
    fun unpack(blob: ByteArray): Pair<String, TextImage>? {
        if (blob.isEmpty()) return null
        val inf = Inflater(true)
        try {
            // Inflater с nowrap просит лишний байт в конце входа.
            inf.setInput(blob + byteArrayOf(0))
            val raw = ByteArray(1 + 255 + Geom.IDX_BYTES + RGB_BYTES)
            var n = 0
            while (n < raw.size && !inf.finished()) {
                val got = inf.inflate(raw, n, raw.size - n)
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += got
            }
            val len = raw[0].toInt() and 0xFF
            val base = 1 + len + Geom.IDX_BYTES
            // Без слоя эмодзи (прежние блобы и текст без них) или со слоем.
            val rgb = when (n) {
                base -> null
                base + RGB_BYTES -> raw.copyOfRange(base, n)
                else -> return null
            }
            val text = String(raw, 1, len, Charsets.UTF_8)
            return text to TextImage(raw.copyOfRange(1 + len, base), rgb)
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

    /**
     * Слой эмодзи ([TextImage.rgb]) в круглую картинку — поверх [disc], уже
     * окрашенного. Слой умножен на покрытие (нарисован на чёрном), а диод
     * светит, а не закрашивает: прозрачность — по старшему каналу, цвет —
     * поделённый на неё. Тёмное (контуры, глаза) становится прозрачным, как
     * погасший диод на ободе.
     */
    fun discRgb(rgb: ByteArray, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val idx = discIndex(size)
        val px = IntArray(size * size)
        for (i in px.indices) {
            val k = idx[i]
            if (k < 0) continue
            val r = rgb[k * 3].toInt() and 0xFF
            val g = rgb[k * 3 + 1].toInt() and 0xFF
            val b = rgb[k * 3 + 2].toInt() and 0xFF
            val a = maxOf(r, g, b)
            if (a != 0) px[i] = (a shl 24) or ((r * 255 / a) shl 16) or ((g * 255 / a) shl 8) or (b * 255 / a)
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
