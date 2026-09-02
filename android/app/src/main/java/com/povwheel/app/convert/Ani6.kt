package com.povwheel.app.convert

import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Контейнер ANI6 и его упаковка для передачи.
 *
 * Раскладка файла: «ANI6» + число кадров (uint16 LE) + задержка кадра в мс
 * (uint16 LE) + N кадров подряд, каждый — 768 байт палитры и следом 15840
 * индексов. Статичная картинка — это просто N = 1.
 *
 * Старший бит поля «число кадров» — флаг «зеркалить заднюю сторону луча».
 * Кадров физически не бывает больше ~820 (размер раздела LittleFS), так что
 * старшие биты этого поля всегда свободны; прошивка маскирует их обратно.
 */
object Ani6 {
    const val HEADER = 8

    fun sizeFor(frames: Int) = HEADER + frames * Geom.FRAME_STRIDE

    /** Выделяет весь файл и пишет заголовок. Кадры заполняются на месте. */
    fun allocate(frames: Int, delayMs: Int, mirrorBack: Boolean = false): ByteArray {
        val buf = ByteArray(sizeFor(frames))
        buf[0] = 'A'.code.toByte()
        buf[1] = 'N'.code.toByte()
        buf[2] = 'I'.code.toByte()
        buf[3] = '6'.code.toByte()
        val cnt = (frames and 0x7FFF) or (if (mirrorBack) 0x8000 else 0)
        buf[4] = (cnt and 0xFF).toByte()
        buf[5] = ((cnt shr 8) and 0xFF).toByte()
        val d = delayMs.coerceIn(1, 65535)
        buf[6] = (d and 0xFF).toByte()
        buf[7] = ((d shr 8) and 0xFF).toByte()
        return buf
    }

    fun frameOffset(i: Int) = HEADER + i * Geom.FRAME_STRIDE

    /** Та же CRC32, что прошивка считает по РАСПАКОВАННЫМ байтам. */
    fun crc32(data: ByteArray): Long {
        val c = CRC32()
        c.update(data)
        return c.value
    }

    /**
     * То, что реально уходит по радио.
     *
     * Плоскость индексов палитрового кадра сжимается хорошо, поэтому шлём поток
     * raw DEFLATE и распаковываем на устройстве: tinfl лежит в ПЗУ ESP32-S3,
     * то есть распаковка не стоит прошивке ни байта флеша и обычно поднимает
     * эффективную скорость заливки втрое-впятеро.
     *
     * Если сжатие не помогло (данные и так плотные), шлём как есть — платить за
     * распаковку, которая ничего не экономит, незачем.
     */
    fun encodeForWire(raw: ByteArray, allowDeflate: Boolean): Wire {
        if (!allowDeflate) return Wire(raw, false)
        val out = ByteArray(raw.size + 64)
        val d = Deflater(Deflater.BEST_SPEED, true)   // nowrap = raw DEFLATE, без заголовка zlib
        try {
            d.setInput(raw)
            d.finish()
            var n = 0
            while (!d.finished() && n < out.size) {
                val got = d.deflate(out, n, out.size - n)
                if (got == 0) break
                n += got
            }
            if (!d.finished() || n >= raw.size) return Wire(raw, false)
            return Wire(out.copyOf(n), true)
        } finally {
            d.end()
        }
    }

    data class Wire(val bytes: ByteArray, val compressed: Boolean)

    /**
     * Правила имени файла, те же что в браузере: LittleFS в arduino-esp32 держит
     * имя не длиннее 31 байта, и допускаются только латиница, цифры, подчёркивание
     * и дефис — так один символ всегда равен одному байту.
     */
    private const val NAME_MAX = 31

    fun buildFileName(prefix: String, rawBase: String, uniq: Int): NameResult {
        val candidate = prefix + rawBase + ".bin"
        val safe = rawBase.isNotEmpty() && rawBase.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
        }
        if (safe && candidate.length <= NAME_MAX) return NameResult(candidate, null)
        val reason = if (!safe) "contains non-latin or special characters"
                     else "too long (" + candidate.length + " > " + NAME_MAX + " chars)"
        val num = (1000 + (uniq % 9000)).toString()
        val fallback = prefix + "invalid-" + num + ".bin"
        return NameResult(fallback, "\"" + rawBase + "\" — " + reason + ". Saving as: " + fallback)
    }

    data class NameResult(val name: String, val warning: String?)
}
