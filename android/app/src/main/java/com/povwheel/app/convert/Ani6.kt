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
    // Префикс (4) + основа + «-» + хэш (6) + «.bin» (4) — в 31 байт.
    private const val SLUG_MAX = NAME_MAX - 4 - 1 - 6 - 4

    private fun isSafe(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    /**
     * Имя файла на колесе. Не подходящее под правила имя (кириллица, пробелы,
     * слишком длинное) раньше заменялось на `invalid-NNNN` со случайным номером —
     * и повторная заливка того же файла давала второе имя, то есть вторую копию,
     * а приложение не могло узнать в ней дубль. Теперь имя — функция исходного:
     * читаемая основа (кириллица транслитом, прочее — «_», до [SLUG_MAX] символов)
     * плюс 6 hex-знаков FNV-1a от исходного имени в UTF-8, чтобы разные имена с
     * одинаковой основой не совпали. Тот же алгоритм — в data/index.html.
     */
    fun buildFileName(prefix: String, rawBase: String): NameResult {
        val candidate = prefix + rawBase + ".bin"
        val safe = rawBase.isNotEmpty() && rawBase.all(::isSafe)
        if (safe && candidate.length <= NAME_MAX) return NameResult(candidate, null)
        val reason = if (!safe) "contains non-latin or special characters"
                     else "too long (" + candidate.length + " > " + NAME_MAX + " chars)"
        val slug = slugOf(rawBase)
        val hash = fnv1aHex(rawBase)
        val name = prefix + (if (slug.isEmpty()) hash else slug + "-" + hash.substring(0, 6)) + ".bin"
        return NameResult(name, "\"" + rawBase + "\" — " + reason + ". Saving as: " + name)
    }

    private val TRANSLIT = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g"
    )

    /** Читаемая основа: латиница/цифры/«_»/«-» как есть, кириллица транслитом,
     *  остальное — «_» (подряд не повторяется), края без «_» и «-». */
    private fun slugOf(raw: String): String {
        val sb = StringBuilder()
        for (c in raw) {
            val low = c.lowercaseChar()
            val t = TRANSLIT[low]
            when {
                isSafe(c) -> sb.append(c)
                t != null -> sb.append(if (c != low && t.isNotEmpty()) t[0].uppercaseChar() + t.substring(1) else t)
                sb.isNotEmpty() && sb.last() != '_' -> sb.append('_')
            }
        }
        return sb.toString().trim('_', '-').take(SLUG_MAX).trimEnd('_', '-')
    }

    /** FNV-1a 32 бит от UTF-8 байтов, 8 hex-знаков. */
    private fun fnv1aHex(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        return String.format("%08x", h)
    }

    data class NameResult(val name: String, val warning: String?)
}
