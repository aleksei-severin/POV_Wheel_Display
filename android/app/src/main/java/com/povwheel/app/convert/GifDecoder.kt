package com.povwheel.app.convert

/**
 * Минимальный разбор GIF, перенос MinimalGIF из data/index.html.
 *
 * Написан руками, а не взят библиотекой, по двум причинам: формат небольшой и
 * полностью описан, а браузерная версия — эталон, по которому уже сделаны файлы
 * на устройствах; другой декодер тихо изменил бы обработку утилизации кадров и
 * прозрачности.
 *
 * Битый файл деградирует, а не бросает исключение, — как и в браузере: за
 * концом буфера JavaScript отдаёт undefined, а не ошибку, и обрезанный GIF
 * всё равно давал те кадры, что успели прочитаться.
 */
class GifDecoder(private val bytes: ByteArray) {

    class Frame(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val pixels: ByteArray,
        val lct: ByteArray?,
        val delay: Int,
        val disposal: Int,
        val transIndex: Int
    )

    var width = 0; private set
    var height = 0; private set
    val frames = ArrayList<Frame>()
    private var pos = 0
    private var globalColorTable: ByteArray? = null

    private fun readByte(): Int {
        if (pos >= bytes.size) { pos++; return 0 }
        return bytes[pos++].toInt() and 0xFF
    }

    private fun readWord(): Int = readByte() or (readByte() shl 8)

    private fun readBytes(n: Int): ByteArray {
        val end = minOf(pos + n, bytes.size)
        val from = minOf(pos, bytes.size)
        val out = ByteArray(n)
        if (end > from) System.arraycopy(bytes, from, out, 0, end - from)
        pos += n
        return out
    }

    fun parse() {
        val sig = String(readBytes(6), Charsets.US_ASCII)
        if (sig != "GIF87a" && sig != "GIF89a") throw IllegalArgumentException("Not a GIF")
        width = readWord()
        height = readWord()
        val packed = readByte()
        readByte(); readByte()
        if (packed and 0x80 != 0) globalColorTable = readBytes((2 shl (packed and 7)) * 3)

        // Значения «липкие»: блок управления графикой действует до следующего
        // такого же блока.
        var delay = 100
        var disposal = 0
        var transIndex = -1

        while (pos < bytes.size) {
            val bt = readByte()
            if (bt == 0x3B) break                     // конец файла
            if (bt == 0x21) {                         // расширение
                val et = readByte()
                if (et == 0xF9) {                     // управление графикой
                    readByte()
                    val gce = readByte()
                    disposal = (gce shr 2) and 7
                    val d = readWord() * 10
                    delay = if (d == 0) 100 else d
                    val tc = readByte()
                    transIndex = if (gce and 1 != 0) tc else -1
                    readByte()
                } else {
                    var l = readByte()
                    while (l != 0) { pos += l; if (pos > bytes.size) break; l = readByte() }
                }
            } else if (bt == 0x2C) {                  // описатель изображения
                val x = readWord(); val y = readWord()
                val w = readWord(); val h = readWord()
                val ip = readByte()
                val lctF = (ip and 0x80) != 0
                val lct = if (lctF) readBytes((2 shl (ip and 7)) * 3) else globalColorTable
                val mcs = readByte()
                val id = java.io.ByteArrayOutputStream()
                var l = readByte()
                while (l != 0) {
                    val end = minOf(pos + l, bytes.size)
                    if (end > pos) id.write(bytes, pos, end - pos)
                    pos += l
                    if (pos > bytes.size) break
                    l = readByte()
                }
                if (w <= 0 || h <= 0) continue        // кадр нулевого размера рисовать нечем
                var pix = lzwDecode(mcs, id.toByteArray(), w * h)
                if (ip and 0x40 != 0) pix = deinterlace(pix, w, h)   // бит 6 — чересстрочность
                frames.add(Frame(x, y, w, h, pix, lct, delay, disposal, transIndex))
            }
        }
    }

    /**
     * Чересстрочный кадр хранится четырьмя проходами по строкам:
     * 0, 8, 16… затем 4, 12, 20… затем 2, 6, 10… затем 1, 3, 5…
     * Без обратной перестановки каждый проход ложится сплошным блоком, и кадр
     * выглядит как несколько сплющенных по вертикали копий картинки.
     */
    private fun deinterlace(src: ByteArray, w: Int, h: Int): ByteArray {
        val dst = ByteArray(w * h)
        val starts = intArrayOf(0, 4, 2, 1)
        val steps = intArrayOf(8, 8, 4, 2)
        var row = 0
        for (p in 0 until 4) {
            var yy = starts[p]
            while (yy < h) {
                val from = row * w
                if (from + w <= src.size) System.arraycopy(src, from, dst, yy * w, w)
                row++
                yy += steps[p]
            }
        }
        return dst
    }

    private fun lzwDecode(mcs: Int, data: ByteArray, pc: Int): ByteArray {
        val cc = 1 shl mcs
        val eoi = cc + 1
        var cs = mcs + 1
        var ds = cc + 2
        val dict = arrayOfNulls<ByteArray>(4096)
        for (i in 0 until cc) dict[i] = byteArrayOf(i.toByte())

        val out = ByteArray(pc)
        var op = 0
        var bp = 0

        fun rc(): Int {
            val by = bp shr 3
            val bo = bp and 7
            if (by >= data.size) return -1
            var v = data[by].toInt() and 0xFF
            if (by + 1 < data.size) v = v or ((data[by + 1].toInt() and 0xFF) shl 8)
            if (by + 2 < data.size) v = v or ((data[by + 2].toInt() and 0xFF) shl 16)
            val c = (v shr bo) and ((1 shl cs) - 1)
            bp += cs
            return c
        }

        var oc = -1
        while (op < pc) {
            val c = rc()
            if (c == -1 || c == eoi) break
            if (c == cc) {                            // код сброса словаря
                cs = mcs + 1
                ds = cc + 2
                for (i in ds until 4096) dict[i] = null
                oc = -1
                continue
            }
            val seq: ByteArray
            if (c < ds && dict[c] != null) {
                seq = dict[c]!!
            } else if (c == ds && oc != -1 && dict[oc] != null) {
                val prev = dict[oc]!!
                seq = ByteArray(prev.size + 1)
                prev.copyInto(seq)
                seq[prev.size] = prev[0]
            } else break

            // Битый поток может выдать больше пикселей, чем w×h: без обрезки
            // копирование бросило бы исключение и уронило разбор всего файла.
            var sl = seq.size
            if (op + sl > pc) sl = pc - op
            if (sl > 0) System.arraycopy(seq, 0, out, op, sl)
            op += sl

            if (oc != -1 && ds < 4096 && dict[oc] != null) {
                val prev = dict[oc]!!
                val ne = ByteArray(prev.size + 1)
                prev.copyInto(ne)
                ne[prev.size] = seq[0]
                dict[ds++] = ne
                if (ds == (1 shl cs) && cs < 12) cs++
            }
            oc = c
        }
        return out
    }
}
