package com.povwheel.app.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlin.math.roundToInt

/**
 * Превращает выбранный пользователем файл в готовый ANI6.
 *
 * Вся тяжёлая работа идёт здесь, на телефоне, ровно как раньше в браузере:
 * устройство так и не узнаёт, что бывают GIF, WebP и видео, — оно видит только
 * палитровые кадры.
 */
class Converter(private val context: Context) {

    data class Result(
        val fileName: String,
        val data: ByteArray,
        val frameCount: Int,
        val warning: String?
    )

    enum class Kind { IMAGE, GIF, WEBP_ANIM, VIDEO }

    data class VideoOpts(val fps: Int = 10, val lengthSec: Double = 10.0)

    interface Progress {
        fun stage(text: String)
        fun frames(done: Int, total: Int)
    }

    // ------------------------------------------------------- определение типа

    fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i)
            }
        } catch (_: Exception) {}
        return name ?: (uri.lastPathSegment ?: "file")
    }

    fun readBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("cannot read the file")

    fun mimeOf(uri: Uri): String = context.contentResolver.getType(uri) ?: ""

    fun kindOf(uri: Uri, bytesForSniff: ByteArray?): Kind {
        val name = displayName(uri).lowercase()
        val mime = mimeOf(uri).lowercase()
        if (mime.startsWith("video/") || VIDEO_EXT.any { name.endsWith(it) }) return Kind.VIDEO
        // По MIME, а не только по имени: у ссылок из системной галереи
        // отображаемое имя может прийти без расширения, и тогда гифка ушла бы
        // по ветке обычной картинки — то есть на обод попал бы один первый
        // кадр вместо анимации, и ошибкой это бы не выглядело.
        if (mime == "image/gif" || name.endsWith(".gif")) return Kind.GIF
        val isWebp = mime == "image/webp" || name.endsWith(".webp")
        if (isWebp && bytesForSniff != null && WebP.parse(bytesForSniff) != null) return Kind.WEBP_ANIM
        return Kind.IMAGE
    }

    private val VIDEO_EXT = listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".ogv", ".avi", ".3gp")

    // ------------------------------------------------------------- конвертация

    fun convert(
        uri: Uri,
        fitMode: Int,
        maxFrames: Int,
        vid: VideoOpts,
        prog: Progress
    ): Result {
        val name = displayName(uri)
        val rawBase = name.substringBeforeLast('.', name)

        // Анимированный WebP определяется только по содержимому, поэтому читаем
        // файл до выбора имени: от этого зависит префикс.
        var bytes: ByteArray? = null
        val looksWebp = mimeOf(uri).lowercase() == "image/webp" || name.lowercase().endsWith(".webp")
        if (looksWebp) bytes = readBytes(uri)
        val kind = kindOf(uri, bytes)

        val prefix = when (kind) {
            Kind.VIDEO -> "vid_"
            Kind.GIF -> "gif_"
            Kind.WEBP_ANIM -> "anm_"
            Kind.IMAGE -> "img_"
        }
        val nr = Ani6.buildFileName(prefix, rawBase, (System.currentTimeMillis() % 100000).toInt())

        return when (kind) {
            Kind.IMAGE -> convertStill(bytes ?: readBytes(uri), fitMode, nr, prog)
            Kind.GIF -> convertGif(readBytes(uri), fitMode, maxFrames, nr, prog)
            Kind.WEBP_ANIM -> convertWebP(bytes!!, fitMode, maxFrames, nr, prog)
            Kind.VIDEO -> convertVideo(uri, fitMode, maxFrames, vid, nr, prog)
        }
    }

    private fun convertStill(
        raw: ByteArray, fitMode: Int, nr: Ani6.NameResult, prog: Progress
    ): Result {
        prog.stage("converting…")
        var src = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: throw IllegalStateException("this image cannot be decoded")
        src = Bitmaps.applyExif(src, exifOrientation(raw))
        val work = Bitmaps.square(Geom.SRC_SIZE_IMG)
        Bitmaps.drawSquare(src, work, fitMode)
        src.recycle()
        // Одиночная картинка — тот же ANI6 из одного кадра: отдельного
        // безголового формата больше нет.
        val out = Ani6.allocate(1, 100)
        // ss = 3 для статичной: кадр один, лишние отсчёты ничего не стоят.
        PolarSampler().frameInto(work, 3, Quantizer(), out, Ani6.frameOffset(0))
        work.recycle()
        prog.frames(1, 1)
        return Result(nr.name, out, 1, nr.warning)
    }

    private fun convertGif(
        raw: ByteArray, fitMode: Int, maxFrames: Int, nr: Ani6.NameResult, prog: Progress
    ): Result {
        val gif = GifDecoder(raw)
        gif.parse()
        if (gif.frames.isEmpty()) throw IllegalStateException("this GIF has no frames")
        val total = minOf(gif.frames.size, maxFrames)
        var warning = nr.warning
        if (gif.frames.size > total) {
            warning = gif.frames.size.toString() + " frames trimmed to " + total + " (device memory)"
        }
        val delay = gif.frames[0].delay.coerceAtLeast(1)
        val out = Ani6.allocate(total, delay)

        val canvasW = maxOf(gif.width, 1)
        val canvasH = maxOf(gif.height, 1)
        val canvasPx = IntArray(canvasW * canvasH)          // живёт между кадрами, изначально прозрачный
        val canvasBmp = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val work = Bitmaps.square(Geom.SRC_SIZE_GIF)
        val sampler = PolarSampler()
        val quant = Quantizer()

        for (i in 0 until total) {
            val f = gif.frames[i]
            // Метод утилизации относится к тому кадру, вместе с которым объявлен:
            // очищать нужно область ПРЕДЫДУЩЕГО кадра, если очистку просил он.
            // Проверка текущего кадра давала на GIF со смешанными методами то
            // лишнюю очистку, то шлейф от старого кадра.
            if (i > 0 && gif.frames[i - 1].disposal == 2) {
                val pf = gif.frames[i - 1]
                clearRect(canvasPx, canvasW, canvasH, pf.x, pf.y, pf.w, pf.h)
            }
            blitFrame(canvasPx, canvasW, canvasH, f)
            canvasBmp.setPixels(canvasPx, 0, canvasW, 0, 0, canvasW, canvasH)
            Bitmaps.drawSquare(canvasBmp, work, fitMode)
            // ss = 2, как в браузере: кадров много, а 3×3 утроило бы время
            // конвертации без видимого выигрыша.
            sampler.frameInto(work, 2, quant, out, Ani6.frameOffset(i))
            prog.frames(i + 1, total)
        }
        canvasBmp.recycle()
        work.recycle()
        return Result(nr.name, out, total, warning)
    }

    /** Наложение кадра GIF. Альфа там двоичная, так что это копирование непрозрачных точек. */
    private fun blitFrame(dst: IntArray, dw: Int, dh: Int, f: GifDecoder.Frame) {
        val lct = f.lct
        for (row in 0 until f.h) {
            val dy = f.y + row
            if (dy < 0 || dy >= dh) continue
            var si = row * f.w
            for (col in 0 until f.w) {
                val dx = f.x + col
                if (dx in 0 until dw) {
                    val c = f.pixels[si].toInt() and 0xFF
                    // Условие в браузере — «c !== transIndex И таблица есть»:
                    // у кадра вообще без таблицы каждый пиксель уходил в
                    // прозрачные, и под ним оставалось прежнее содержимое холста.
                    if (c != f.transIndex && lct != null) {
                        // Индекс за пределами ИМЕЮЩЕЙСЯ таблицы в браузере читается
                        // как непрозрачный чёрный (undefined приводится к 0, альфа
                        // 255). Строгий перенос не должен падать там, где тот
                        // деградировал.
                        var argb = 0xFF000000.toInt()
                        if (c * 3 + 2 < lct.size) {
                            val r = lct[c * 3].toInt() and 0xFF
                            val g = lct[c * 3 + 1].toInt() and 0xFF
                            val b = lct[c * 3 + 2].toInt() and 0xFF
                            argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        dst[dy * dw + dx] = argb
                    }
                }
                si++
            }
        }
    }

    private fun clearRect(px: IntArray, w: Int, h: Int, x: Int, y: Int, rw: Int, rh: Int) {
        for (row in y until minOf(y + rh, h)) {
            if (row < 0) continue
            for (col in maxOf(x, 0) until minOf(x + rw, w)) px[row * w + col] = 0
        }
    }

    private fun convertWebP(
        raw: ByteArray, fitMode: Int, maxFrames: Int, nr: Ani6.NameResult, prog: Progress
    ): Result {
        val anim = WebP.parse(raw) ?: throw IllegalStateException("not an animated WebP")
        val total = minOf(anim.frames.size, maxFrames)
        var warning = nr.warning
        if (anim.frames.size > total) {
            warning = anim.frames.size.toString() + " frames trimmed to " + total + " (device memory)"
        }
        // ANI6 хранит одну задержку на всю анимацию — берём длительность первого
        // кадра. Ноль означает «как можно быстрее» и в браузере подменялся на
        // 100 мс; без этой подмены анимация уехала бы на 1 мс/кадр.
        val d0 = anim.frames[0].dur
        val delay = (if (d0 == 0) 100 else d0).coerceAtLeast(1)
        val out = Ani6.allocate(total, delay)

        val canvas = Bitmap.createBitmap(anim.w, anim.h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(canvas)
        val work = Bitmaps.square(Geom.SRC_SIZE_GIF)
        val sampler = PolarSampler()
        val quant = Quantizer()

        for (i in 0 until total) {
            val fr = anim.frames[i]
            val still = WebP.stillFromFrame(raw, fr)
                ?: throw IllegalStateException("frame " + (i + 1) + " has no image data")
            val bmp = BitmapFactory.decodeByteArray(still, 0, still.size)
                ?: throw IllegalStateException("frame " + (i + 1) + " cannot be decoded")
            // «Не смешивать» означает, что пиксели кадра замещают то, что под ними.
            if (!fr.blend) {
                cv.save()
                cv.clipRect(fr.x, fr.y, fr.x + fr.w, fr.y + fr.h)
                cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
                cv.restore()
            }
            cv.drawBitmap(bmp, fr.x.toFloat(), fr.y.toFloat(), null)
            bmp.recycle()
            Bitmaps.drawSquare(canvas, work, fitMode)
            sampler.frameInto(work, 2, quant, out, Ani6.frameOffset(i))
            // Утилизация относится к УЖЕ показанному кадру — чистим после съёмки.
            if (fr.dispose) {
                cv.save()
                cv.clipRect(fr.x, fr.y, fr.x + fr.w, fr.y + fr.h)
                cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
                cv.restore()
            }
            prog.frames(i + 1, total)
        }
        canvas.recycle()
        work.recycle()
        return Result(nr.name, out, total, warning)
    }

    private fun convertVideo(
        uri: Uri, fitMode: Int, maxFrames: Int, vid: VideoOpts,
        nr: Ani6.NameResult, prog: Progress
    ): Result {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
        } catch (e: Exception) {
            mmr.release()
            throw IllegalStateException("this phone cannot decode this video")
        }
        try {
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val dur = if (durMs > 0) durMs / 1000.0 else vid.lengthSec
            val len = vid.lengthSec.coerceIn(0.1, maxOf(0.1, dur))
            val n = maxOf(1, minOf((len * vid.fps).roundToInt(), maxFrames))
            // Задержка кадра в заголовке — целые миллисекунды, uint16.
            val delay = maxOf(1, (1000.0 / vid.fps).roundToInt())
            val out = Ani6.allocate(n, delay)

            val work = Bitmaps.square(Geom.SRC_SIZE_VID)
            val sampler = PolarSampler()
            val quant = Quantizer()
            var got = 0
            for (i in 0 until n) {
                val tUs = ((i.toDouble() / vid.fps) * 1_000_000.0).toLong()
                val bmp = try {
                    mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (e: Exception) { null }
                if (bmp == null) {
                    if (got == 0) throw IllegalStateException("no frames could be read from this video")
                    // За концом ролика или нечитаемый кадр: повторяем предыдущий,
                    // а не бросаем весь клип.
                    System.arraycopy(out, Ani6.frameOffset(got - 1), out, Ani6.frameOffset(i), Geom.FRAME_STRIDE)
                } else {
                    Bitmaps.drawSquare(bmp, work, fitMode)
                    bmp.recycle()
                    sampler.frameInto(work, 2, quant, out, Ani6.frameOffset(i))
                }
                got++
                prog.frames(i + 1, n)
            }
            work.recycle()
            return Result(nr.name, out, n, nr.warning)
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /** Метка поворота из EXIF. 1 (нормально) — если её нет или файл её не несёт. */
    private fun exifOrientation(raw: ByteArray): Int = try {
        ExifInterface(java.io.ByteArrayInputStream(raw))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    } catch (e: Exception) {
        1
    }

    fun videoDurationSec(uri: Uri): Double {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (ms > 0) ms / 1000.0 else 0.0
        } catch (e: Exception) {
            0.0
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /** Первый кадр любого источника — для миниатюры в выборе файла. */
    fun posterOf(uri: Uri, fitMode: Int, size: Int): Bitmap? {
        return try {
            val bytes = if (mimeOf(uri).startsWith("video/")) null else readBytes(uri)
            when (kindOf(uri, bytes)) {
                Kind.VIDEO -> {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(context, uri)
                        val b = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        b?.let { src ->
                            val out = Bitmaps.square(size)
                            Bitmaps.drawSquare(src, out, fitMode)
                            src.recycle()
                            out
                        }
                    } finally { try { mmr.release() } catch (_: Exception) {} }
                }
                Kind.WEBP_ANIM -> {
                    val anim = WebP.parse(bytes!!)!!
                    val still = WebP.stillFromFrame(bytes, anim.frames[0])
                    val src = still?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    src?.let {
                        val out = Bitmaps.square(size)
                        Bitmaps.drawSquare(it, out, fitMode)
                        it.recycle()
                        out
                    }
                }
                Kind.GIF -> {
                    val gif = GifDecoder(bytes!!)
                    gif.parse()
                    val f = gif.frames.firstOrNull() ?: return null
                    val px = IntArray(maxOf(gif.width, 1) * maxOf(gif.height, 1))
                    blitFrame(px, gif.width, gif.height, f)
                    val bmp = Bitmap.createBitmap(gif.width, gif.height, Bitmap.Config.ARGB_8888)
                    bmp.setPixels(px, 0, gif.width, 0, 0, gif.width, gif.height)
                    val out = Bitmaps.square(size)
                    Bitmaps.drawSquare(bmp, out, fitMode)
                    bmp.recycle()
                    out
                }
                Kind.IMAGE -> {
                    var src = BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size) ?: return null
                    src = Bitmaps.applyExif(src, exifOrientation(bytes))
                    val out = Bitmaps.square(size)
                    Bitmaps.drawSquare(src, out, fitMode)
                    src.recycle()
                    out
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Ровно столько разбора контейнера RIFF, сколько нужно, чтобы разложить
 * анимированный WebP.
 *
 * Пиксели по-прежнему декодирует система: каждый чанк ANMF содержит внутри
 * обычную статичную картинку VP8/VP8L, и достаточно завернуть её в минимальный
 * заголовок WebP, чтобы отдать декодеру, который есть в любой версии Android.
 */
object WebP {
    class Chunk(val tag: String, val start: Int, val size: Int)
    class Frame(
        val x: Int, val y: Int, val w: Int, val h: Int, val dur: Int,
        val blend: Boolean, val dispose: Boolean, val sub: List<Chunk>
    )
    class Anim(val w: Int, val h: Int, val frames: List<Frame>)

    private fun tag(u: ByteArray, p: Int): String {
        if (p + 4 > u.size) return ""
        return String(u, p, 4, Charsets.US_ASCII)
    }

    private fun rd32(u: ByteArray, p: Int): Int {
        if (p + 4 > u.size) return 0
        return (u[p].toInt() and 0xFF) or ((u[p + 1].toInt() and 0xFF) shl 8) or
               ((u[p + 2].toInt() and 0xFF) shl 16) or ((u[p + 3].toInt() and 0xFF) shl 24)
    }

    private fun rd24(u: ByteArray, p: Int): Int {
        if (p + 3 > u.size) return 0
        return (u[p].toInt() and 0xFF) or ((u[p + 1].toInt() and 0xFF) shl 8) or
               ((u[p + 2].toInt() and 0xFF) shl 16)
    }

    /** Обход чанков RIFF в диапазоне [start, end). */
    private fun chunks(u: ByteArray, start: Int, end: Int): List<Chunk> {
        val out = ArrayList<Chunk>()
        var p = start
        while (p + 8 <= end) {
            val t = tag(u, p)
            val size = rd32(u, p + 4)
            if (size < 0) break
            val body = p + 8
            if (body + size > end) break          // обрезанный файл — дальше не идём
            out.add(Chunk(t, body, size))
            p = body + size + (size and 1)        // чанки выровнены по чётной границе
        }
        return out
    }

    /** null — не анимация, в том числе статичный WebP и просто не-WebP. */
    fun parse(u: ByteArray): Anim? {
        if (u.size < 21) return null
        if (tag(u, 0) != "RIFF" || tag(u, 8) != "WEBP") return null
        val riffSize = rd32(u, 4)
        val end = minOf(u.size, 8 + riffSize)
        val cs = chunks(u, 12, end)
        val vp8x = cs.firstOrNull { it.tag == "VP8X" } ?: return null
        if (vp8x.size < 10) return null
        val w = rd24(u, vp8x.start + 4) + 1
        val h = rd24(u, vp8x.start + 7) + 1

        // Ищем именно чанки ANMF, а не флаг ANIM в VP8X: флаг без кадров
        // встречается у кривых конвертеров.
        val frames = ArrayList<Frame>()
        for (c in cs) {
            if (c.tag != "ANMF" || c.size < 16) continue
            val s = c.start
            val fl = u[s + 15].toInt() and 0xFF
            frames.add(
                Frame(
                    rd24(u, s) * 2, rd24(u, s + 3) * 2,      // смещение хранится в парах пикселей
                    rd24(u, s + 6) + 1, rd24(u, s + 9) + 1,
                    rd24(u, s + 12),
                    (fl and 0x02) == 0,
                    (fl and 0x01) != 0,
                    chunks(u, s + 16, c.start + c.size)
                )
            )
        }
        return if (frames.isEmpty()) null else Anim(w, h, frames)
    }

    private fun u32le(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
    )

    private fun u24le(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte()
    )

    /**
     * Заворачивает один кадр в самостоятельный статичный WebP. Если у кадра есть
     * отдельная альфа (ALPH), нужен расширенный заголовок VP8X — без него
     * декодер прочитает картинку без прозрачности.
     */
    fun stillFromFrame(u: ByteArray, fr: Frame): ByteArray? {
        val parts = ArrayList<ByteArray>()
        if (fr.sub.any { it.tag == "ALPH" }) {
            val vp8x = ByteArray(18)
            "VP8X".toByteArray(Charsets.US_ASCII).copyInto(vp8x, 0)
            u32le(10).copyInto(vp8x, 4)
            vp8x[8] = 0x10                        // флаг ALPHA
            u24le(fr.w - 1).copyInto(vp8x, 12)
            u24le(fr.h - 1).copyInto(vp8x, 15)
            parts.add(vp8x)
        }
        for (c in fr.sub) {
            if (c.tag != "ALPH" && c.tag != "VP8 " && c.tag != "VP8L") continue
            val pad = c.size and 1
            val out = ByteArray(8 + c.size + pad)
            c.tag.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
            u32le(c.size).copyInto(out, 4)
            if (c.start + c.size <= u.size) System.arraycopy(u, c.start, out, 8, c.size)
            parts.add(out)
        }
        if (parts.isEmpty()) return null
        var body = 0
        for (p in parts) body += p.size
        val file = ByteArray(12 + body)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(file, 0)
        u32le(4 + body).copyInto(file, 4)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(file, 8)
        var o = 12
        for (p in parts) { p.copyInto(file, o); o += p.size }
        return file
    }
}
