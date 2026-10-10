package com.povwheel.app.povvideo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.povwheel.app.convert.VideoFrames
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Рендер POV-видео на телефоне — то же, что делает tools/pov_fps_blend/POV-BlendFPS.ps1
 * в режиме -AudioSync (склейка blend по умолчанию), только конвейер свой:
 *
 *   MediaCodec (декодер) → OES-текстура → слой кольца (GL_TEXTURE_2D_ARRAY)
 *   → шейдер склейки окна → поверхность H.264-кодировщика → MediaMuxer (MP4).
 *
 * Вне отрисовки кадры исходника идут как есть, а каждая прорисовка становится одним кадром —
 * склейкой (6 окон длиной в прорисовку со сдвигом 0..5/6 и дробным началом, см. [windows]),
 * который стоит на экране до следующего: частота кадров результата переменная. Метки
 * времени — настоящие, делённые на замедление, так что slow motion выходит в реальном
 * времени.
 *
 * Разрешение результата — как у исходника. Кольцо держит в памяти видеокарты десятки
 * кадров, поэтому хранит их в YUV 4:2:0 (1.5 байта на пиксель вместо 4 у RGBA): кадр 4K —
 * 12 МБ, а не 33. Результат кодируется в тот же 4:2:0, так что это ничего не стоит.
 * Меньше исходника — только если кодировщик телефона такого размера не умеет или кольцо
 * не помещается в память; тогда [Result] это показывает.
 */
internal class PovRenderer(private val ctx: Context, private val a: PovAnalysis) {

    class Result(val frames: Int, val width: Int, val height: Int)

    private companion object {
        const val RING_BUDGET = 512L shl 20   // байт на кольцо кадров в памяти видеокарты
        const val MAX_BPS = 120e6             // потолок битрейта (и не выше, чем умеет кодировщик)
        const val BLACK_LEVEL = 16            // отсечка шума, как -BlackLevel по умолчанию
        const val SPREAD = 1.0                // разнос шести окон (CheckerSpread)
        const val SHORT_FRAC = 0.15           // см. Windows в скрипте
    }

    /** Окна одного элемента плана: 12 отрезков кадров (абсолютные номера) и веса. */
    private class Win(val kind: Int, val a: Int, val b: Int, val lo: IntArray, val hi: IntArray, val wt: DoubleArray)

    /**
     * Окна склейки элемента плана, начинающегося с кадра [a0]. Прорисовка (kind 2) — шесть
     * окон одной длины, сдвинутых на 0..5/6 прорисовки: у каждого стык начала и конца
     * прорисовки (анимация, неровное число кадров) под своим углом, и среднее делает из
     * шести чётких стыков мягкий переход. Длина окна — целое число кадров не меньше
     * прорисовки (не короче [PovSync.MIN_WIN_FRAMES]): каждое окно закрывает весь круг, и
     * склейки не мигают нахлёстом и щелями. Начало дробное: окно — смесь размещений с кадра k
     * и с кадра k+1 с весами по дробной части, иначе стыки прыгали бы от склейки к склейке.
     * Любое такое окно содержит всю картинку, с какой бы фазы ротора оно ни начиналось, —
     * поэтому склейке не нужна фаза, только длина прорисовки.
     */
    private fun windows(si: Int, a0: Int): Win {
        val p = a.plan
        val n = p.counts[si]
        val b0 = a0 + n - 1
        val lo = IntArray(12) { a0 }
        val hi = IntArray(12) { b0 }
        val wt = DoubleArray(12)
        when (p.kinds[si]) {
            2 -> {
                val tLen = p.segT[si]
                val fl = floor(tLen + 1e-9).toInt()
                var len = if (tLen - fl < SHORT_FRAC) fl else fl + 1
                if (len < PovSync.MIN_WIN_FRAMES) len = PovSync.MIN_WIN_FRAMES
                for (k in 0 until 6) {
                    val c = p.segT0[si] + tLen / 2 + (k - 2.5) / 6.0 * SPREAD * tLen
                    val s = c - len / 2.0
                    val f0 = floor(s + 1e-9).toInt()
                    val beta = (s - f0).coerceIn(0.0, 1.0)
                    lo[2 * k] = f0; hi[2 * k] = f0 + len - 1; wt[2 * k] = 1 - beta
                    lo[2 * k + 1] = f0 + 1; hi[2 * k + 1] = f0 + len; wt[2 * k + 1] = beta
                }
            }
            else -> wt[0] = 1.0
        }
        return Win(p.kinds[si], a0, b0, lo, hi, wt)
    }

    /** Сколько кадров кольцо должно держать одновременно — проход по плану всухую. */
    private fun ringCap(total: Int): Int {
        var pos = 0
        var loadedMax = 0
        var cap = 2
        for (si in a.plan.kinds.indices) {
            val w = windows(si, pos)
            pos += a.plan.counts[si]
            if (w.kind == 0) {
                // Кадры как есть идут по одному: на кадре i в кольце нужен лишь он сам
                // (плюс то, что подгрузили наперёд для прошлого окна).
                loadedMax = max(loadedMax, w.a + 1)
                cap = max(cap, loadedMax - w.a)
                loadedMax = max(loadedMax, w.b + 1)
                continue
            }
            var need = w.b
            var minLo = w.a
            for (k in 0 until 12) if (w.wt[k] > 0) {
                need = max(need, min(total - 1, w.hi[k]))
                minLo = min(minLo, max(0, w.lo[k]))
            }
            loadedMax = max(loadedMax, need + 1)
            cap = max(cap, loadedMax - minLo)
        }
        return cap + 2
    }

    fun render(fd: FileDescriptor, cancelled: () -> Boolean, progress: (done: Int, total: Int) -> Unit): Result {
        val total = a.plan.totalFrames
        val cap = ringCap(total)
        val slow = a.slow

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var gl: PovGl? = null
        var muxer: MediaMuxer? = null
        var drain: Drain? = null
        try {
            extractor.setDataSource(ctx, a.uri, null)
            val (vTrack, vFmt) = VideoFrames.videoTrack(extractor) ?: throw IllegalStateException("no video track")
            extractor.selectTrack(vTrack)
            vFmt.setInteger(MediaFormat.KEY_ROTATION, 0)   // крутит контейнер, не декодер

            // ---- GL и кольцо: размер исходника, если его умеют кодировщик и видеокарта ----
            val g = PovGl()
            gl = g
            if (cap > g.maxLayers()) throw IllegalStateException("rotation too slow for this phone's GPU (needs $cap frames in memory)")
            val cw = max(2, a.codedW)
            val ch = max(2, a.codedH)
            var scale = 1.0
            val caps = encoderCaps()
            if (caps != null) {
                // кодировщик не умеет такой размер (8K в H.264) — наибольший, что умеет, с той же пропорцией
                while (scale > 0.2 && !caps.isSizeSupported(even(cw * scale), even(ch * scale))) scale *= 0.95
            }
            scale = min(scale, g.maxTextureSize().toDouble() / max(cw, ch))
            // кольцо: Y на каждый пиксель + Cb/Cr на четверть — 1.5 байта; плюс результат RGBA
            val bytes = cw * scale * ch * scale * (1.5 * cap + 4.0)
            if (bytes > RING_BUDGET) scale *= sqrt(RING_BUDGET / bytes)
            var ok = false
            for (attempt in 0 until 6) {
                val w = even(cw * scale)
                val h = even(ch * scale)
                if (g.allocate(w, h, cap)) { ok = true; break }
                scale *= 0.8
            }
            if (!ok) throw IllegalStateException("not enough GPU memory")

            val dec = MediaCodec.createDecoderByType(vFmt.getString(MediaFormat.KEY_MIME)!!)
            decoder = dec
            dec.configure(vFmt, g.decoderSurface, null, 0)
            dec.start()

            // ---- ориентация: кадр из отрисовки против эталона MediaMetadataRetriever ----
            val orient = probeOrientation(extractor, dec, g)
            runCatching { dec.flush() }
            g.resetFrame()
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (cancelled()) throw InterruptedException()

            // ---- звук результата ----
            val audio = prepareAudio(cancelled)
            if (cancelled()) throw InterruptedException()

            // ---- кодировщик и контейнер ----
            val outFps = a.fileFps * slow
            val (enc, encW, encH) = createEncoder(g.w, g.h, outFps, targetBitrate(g.w, g.h, outFps))
            encoder = enc
            val encSurface = enc.createInputSurface()
            enc.start()
            g.attachEncoder(encSurface)
            val mux = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            mux.setOrientationHint(orient)
            val dr = Drain(enc, mux, audio)
            drain = dr
            dr.start()

            // ---- основной проход ----
            val decPts = LongArray(total + 64)
            var loaded = 0
            var inEos = false
            var outEos = false
            val info = MediaCodec.BufferInfo()
            var checks = 0
            fun decodeOne(): Boolean {
                while (!outEos) {
                    if (++checks >= 32) { checks = 0; if (cancelled()) throw InterruptedException(); dr.error?.let { throw it } }
                    if (!inEos) {
                        val ii = dec.dequeueInputBuffer(5_000)
                        if (ii >= 0) {
                            val buf = dec.getInputBuffer(ii)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                            else { dec.queueInputBuffer(ii, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val oi = dec.dequeueOutputBuffer(info, 5_000)
                    if (oi < 0) continue
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val take = info.size > 0 && loaded < decPts.size
                    dec.releaseOutputBuffer(oi, take)
                    if (take) {
                        g.awaitFrame()
                        g.storeFrame(loaded % cap)
                        decPts[loaded] = info.presentationTimeUs
                        loaded++
                        if (eos) outEos = true
                        return true
                    }
                    if (eos) outEos = true
                }
                return false
            }
            fun ensure(need: Int): Boolean {
                while (loaded <= need) if (!decodeOne()) return false
                return true
            }

            var written = 0
            var done = 0
            var lastNs = -1L
            // Кадр результата с меткой исходного кадра i; covers — сколько исходных кадров
            // он собой заменяет (для прогресса).
            fun emit(i: Int, covers: Int) {
                var ns = ((decPts[i] - decPts[0]) * 1000.0 / slow).roundToLong()
                if (ns <= lastNs) ns = lastNs + 1000
                lastNs = ns
                g.present(encW, encH, ns)
                written++
                done += covers
                progress(done, total)
            }

            val loI = IntArray(12)
            val hiI = IntArray(12)
            val wtF = FloatArray(12)
            val black = (BLACK_LEVEL + 0.5f) / 255f
            var pos = 0
            planLoop@ for (si in a.plan.kinds.indices) {
                if (cancelled()) throw InterruptedException()
                val w = windows(si, pos)
                pos += a.plan.counts[si]
                if (w.kind == 0) {
                    // Кадры как есть — по одному, кольцо не переполняется даже на долгой паузе.
                    for (i in w.a..w.b) {
                        if (!ensure(i)) break@planLoop
                        loI.fill(0); hiI.fill(0); wtF.fill(0f); wtF[0] = 1f
                        g.blend(i % cap, 1, loI, hiI, wtF, -1f)
                        emit(i, 1)
                    }
                    continue
                }
                var need = w.b
                for (k in 0 until 12) if (w.wt[k] > 0) need = max(need, w.hi[k])
                ensure(min(need, total - 1))
                if (w.a >= loaded) break
                val b = min(w.b, loaded - 1)
                // Окна — в пределах того, что есть в кольце (у краёв ролика окно короче).
                val first = max(0, loaded - cap)
                var jMin = Int.MAX_VALUE
                var jMax = Int.MIN_VALUE
                val lo = IntArray(12)
                val hi = IntArray(12)
                for (k in 0 until 12) {
                    lo[k] = max(first, w.lo[k])
                    hi[k] = min(loaded - 1, w.hi[k])
                    if (hi[k] < lo[k]) { lo[k] = w.a; hi[k] = b }
                    if (w.wt[k] > 0) { jMin = min(jMin, lo[k]); jMax = max(jMax, hi[k]) }
                }
                val n = jMax - jMin + 1
                for (k in 0 until 12) {
                    loI[k] = lo[k] - jMin; hiI[k] = hi[k] - jMin; wtF[k] = w.wt[k].toFloat()
                }
                // отсечка шума — только когда кадров больше одного: одиночный кадр идёт как есть
                g.blend(jMin % cap, n, loI, hiI, wtF, if (n > 1) black else -1f)
                // Склейка уходит одним кадром, который стоит до метки следующего (переменная
                // частота кадров). Прежде она повторялась на каждом исходном кадре прорисовки —
                // при 240 к/с это ~6 одинаковых кадров, и кодировщик тратил на них битрейт
                // наравне с живыми. Последний элемент плана — ещё и кадр на своём конце, иначе
                // ролик обрывался бы раньше на длину прорисовки.
                emit(w.a, b - w.a + 1)
                if (si == a.plan.kinds.size - 1 && b > w.a) emit(b, 0)
                if (b < w.b) break
            }

            enc.signalEndOfInputStream()
            dr.join()
            dr.error?.let { throw it }
            if (!dr.started) throw IllegalStateException("the encoder produced no video")
            runCatching { mux.stop() }.onFailure { throw IllegalStateException("could not finish the MP4 file", it) }
            return Result(written, encW, encH)
        } finally {
            drain?.let { d -> d.abort(); runCatching { d.join(3000) } }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { muxer?.release() }
            gl?.release()
            runCatching { extractor.release() }
        }
    }

    // ------------------------------------------------------------------ ориентация

    /**
     * Угол (по часовой), на который плеер должен повернуть кадр, чтобы он встал как в
     * галерее. Обычно это угол из метаданных, но часть декодеров крутит кадр сама, поэтому
     * решаем по факту, как и при заливке видео на колесо: кадр из середины отрисовки
     * сверяется с эталоном MediaMetadataRetriever (он уже развёрнут правильно).
     */
    private fun probeOrientation(ex: MediaExtractor, dec: MediaCodec, g: PovGl): Int {
        val hint = a.rotation
        val tr = a.tracks.maxByOrNull { it.times.last() - it.times.first() }
        val probeSec = if (tr != null) 0.5 * (tr.times.first() + tr.times.last()) else a.durationSec / 3
        val probeUs = a.videoStartUs + (probeSec * 1e6).toLong()
        var thumb: Bitmap? = null
        var probePts = 0L
        try {
            ex.seekTo(probeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var inEos = false
            val deadline = System.currentTimeMillis() + 15_000
            while (thumb == null && System.currentTimeMillis() < deadline) {
                if (!inEos) {
                    val ii = dec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = dec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                        else { dec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 5_000)
                if (oi < 0) continue
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val use = info.size > 0 && (info.presentationTimeUs >= probeUs || eos)
                dec.releaseOutputBuffer(oi, use)
                if (use) {
                    g.awaitFrame()
                    probePts = info.presentationTimeUs
                    val big = max(g.w, g.h)
                    thumb = g.frameThumb(max(1, g.w * 160 / big), max(1, g.h * 160 / big))
                }
                if (eos) break
            }
        } catch (_: Exception) { }
        val t = thumb ?: return hint
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, a.uri)
                val ref = mmr.getFrameAtTime(probePts, MediaMetadataRetriever.OPTION_CLOSEST) ?: return hint
                val rt = VideoFrames.thumbOf(ref)
                ref.recycle()
                VideoFrames.matchRotation(rt, VideoFrames.thumbOf(t), hint)
            } finally { runCatching { mmr.release() } }
        } catch (_: Exception) { hint } finally { t.recycle() }
    }

    // ------------------------------------------------------------------ кодировщик

    private fun even(v: Double): Int = max(2, (v / 2).roundToInt() * 2)

    private data class Enc(val codec: MediaCodec, val w: Int, val h: Int)

    /** Возможности H.264-кодировщика по умолчанию (размеры, битрейт); null — не узнать. */
    private fun encoderCaps(): MediaCodecInfo.VideoCapabilities? = runCatching {
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try { c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }
        finally { runCatching { c.release() } }
    }.getOrNull()

    /**
     * Битрейт результата — от исходника, а не от размера кадра. Прежняя формула
     * w·h·fps·0.1 на ролике 1080p 240 к/с давала 47 Мбит/с против 18 у самого исходника,
     * и файл выходил в 2.7 раза больше. Теперь — те же биты на пиксель кадра, что у
     * исходника: при уменьшении кадра чуть больше на пиксель (степень 0.75 — мелкий кадр
     * плотнее по деталям), при замедленной съёмке в slow раз больше в секунду (кадров в
     * секунду результата во столько же раз больше). HEVC/AV1/VP9 на тех же битах лучше
     * H.264 — исходнику в них даётся фора в полтора раза. Потолок — MAX_BPS и то, что
     * кодировщик принимает (4K-исходник с телефона — 70+ Мбит/с).
     */
    private fun targetBitrate(w: Int, h: Int, outFps: Double): Int {
        val outPix = w.toDouble() * h
        val srcPix = a.codedW.toDouble() * a.codedH
        var bps = if (a.videoBps > 0 && srcPix > 0) a.videoBps * (outPix / srcPix).pow(0.75) * a.slow
                  else outPix * min(outFps, 60.0) * 0.15   // битрейт исходника неизвестен
        if (a.videoMime in setOf("video/hevc", "video/av01", "video/x-vnd.on2.vp9")) bps *= 1.5
        val top = encoderCaps()?.bitrateRange?.upper?.toDouble()?.let { min(it, MAX_BPS) } ?: MAX_BPS
        return bps.coerceIn(min(4e6, top), top).toInt()
    }

    /** H.264 с входом-поверхностью; если кодировщик капризничает — проще параметры. */
    private fun createEncoder(w: Int, h: Int, fps: Double, bps: Int): Enc {
        val tries = listOf(
            Triple(w, h, fps.roundToInt().coerceIn(1, 240)),
            Triple(w, h, 30),
            Triple((w + 15) / 16 * 16, (h + 15) / 16 * 16, 30)
        )
        var last: Exception? = null
        for ((tw, th, tf) in tries) {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, tw, th)
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                f.setInteger(MediaFormat.KEY_BIT_RATE, bps)
                // Переменный битрейт: в склеенных участках кадров в несколько раз меньше, и
                // сэкономленное на них не должно добиваться до заданного потока.
                val vbr = runCatching {
                    c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).encoderCapabilities
                        .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }.getOrDefault(false)
                if (vbr) f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                f.setInteger(MediaFormat.KEY_FRAME_RATE, tf)
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return Enc(c, tw, th)
            } catch (e: Exception) {
                last = e
                runCatching { c.release() }
            }
        }
        throw IllegalStateException("H.264 encoder rejected ${w}x$h", last)
    }

    // ------------------------------------------------------------------ звук

    private class AudioOut(val format: MediaFormat, val data: List<ByteArray>, val pts: LongArray, val flags: IntArray)

    /**
     * Звук результата. Обычная съёмка — дорожка как есть (без перекодирования).
     * Замедленная — сэмплы объявляются идущими в slow раз чаще (тон и голоса снова на своей
     * высоте, как asetrate в скрипте) и перекодируются в AAC.
     */
    private fun prepareAudio(cancelled: () -> Boolean): AudioOut? =
        if (a.slow <= 1.0) copyAudio() else speedUpAudio(cancelled)

    private fun copyAudio(): AudioOut? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, a.uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            val cap = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) max(1 shl 14, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) else 1 shl 18
            val buf = ByteBuffer.allocateDirect(cap)
            val data = ArrayList<ByteArray>()
            val pts = ArrayList<Long>()
            val flags = ArrayList<Int>()
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = ex.sampleTime - a.videoStartUs
                if (t >= 0) {
                    val b = ByteArray(n)
                    buf.position(0); buf.get(b, 0, n)
                    data.add(b); pts.add(t); flags.add(MediaCodec.BUFFER_FLAG_KEY_FRAME)
                }
                if (!ex.advance()) break
            }
            if (data.isEmpty()) return null
            return AudioOut(fmt, data, pts.toLongArray(), flags.toIntArray())
        } catch (_: Exception) {
            return null
        } finally { runCatching { ex.release() } }
    }

    private fun speedUpAudio(cancelled: () -> Boolean): AudioOut? {
        val au = PovAnalyzer.decodeAudio(ctx, a.uri, wantDet = false, wantPcm = true, cancelled = cancelled) ?: return null
        var pcm = au.pcm ?: return null
        var ch = au.channels
        if (ch > 2) {   // AAC здесь — моно или стерео
            val frames = pcm.size / ch
            val st = ShortArray(frames * 2)
            for (f in 0 until frames) {
                var l = 0; var r = 0
                for (k in 0 until ch) { if (k % 2 == 0) l += pcm[f * ch + k] else r += pcm[f * ch + k] }
                st[2 * f] = (l / ((ch + 1) / 2)).toShort(); st[2 * f + 1] = (r / (ch / 2)).toShort()
            }
            pcm = st; ch = 2
        }
        var rate = (au.sampleRate * a.slow).roundToInt()
        val std = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
        if (rate !in std) { pcm = resample(pcm, ch, rate, 48000); rate = 48000 }
        val offUs = ((au.startUs - a.videoStartUs) / a.slow).roundToLong()

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, if (ch == 1) 128_000 else 192_000)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val info = MediaCodec.BufferInfo()
            val data = ArrayList<ByteArray>()
            val pts = ArrayList<Long>()
            val flags = ArrayList<Int>()
            var outFmt: MediaFormat? = null
            var pos = 0
            var inEos = false
            var outEos = false
            var guard = 0
            while (!outEos) {
                if (++guard % 64 == 0 && cancelled()) throw InterruptedException()
                if (!inEos) {
                    val ii = c.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!.order(ByteOrder.nativeOrder())
                        buf.clear()
                        val maxShorts = (buf.capacity() / 2) / ch * ch
                        val n = min(maxShorts, pcm.size - pos)
                        val t = offUs + (pos / ch).toLong() * 1_000_000L / rate
                        if (n <= 0) {
                            c.queueInputBuffer(ii, 0, 0, t, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else {
                            buf.asShortBuffer().put(pcm, pos, n)
                            c.queueInputBuffer(ii, 0, n * 2, t, 0)
                            pos += n
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFmt = c.outputFormat; continue }
                if (oi < 0) continue
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val ob = c.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val b = ByteArray(info.size)
                    ob.get(b)
                    if (info.presentationTimeUs >= 0) { data.add(b); pts.add(info.presentationTimeUs); flags.add(MediaCodec.BUFFER_FLAG_KEY_FRAME) }
                }
                c.releaseOutputBuffer(oi, false)
            }
            val of = outFmt ?: return null
            if (data.isEmpty()) return null
            return AudioOut(of, data, pts.toLongArray(), flags.toIntArray())
        } finally {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
    }

    /** Линейный пересчёт частоты дискретизации (чередующиеся каналы). */
    private fun resample(src: ShortArray, ch: Int, from: Int, to: Int): ShortArray {
        val inF = src.size / ch
        val outF = (inF.toLong() * to / from).toInt()
        val out = ShortArray(outF * ch)
        val step = from.toDouble() / to
        for (f in 0 until outF) {
            val x = f * step
            val i0 = min(inF - 1, x.toInt())
            val i1 = min(inF - 1, i0 + 1)
            val fr = x - i0
            for (k in 0 until ch) {
                val v = src[i0 * ch + k] * (1 - fr) + src[i1 * ch + k] * fr
                out[f * ch + k] = v.roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    // ------------------------------------------------------------------ выход кодировщика

    /**
     * Забирает кадры кодировщика в контейнер в своём потоке: иначе eglSwapBuffers ждёт
     * свободного входа кодировщика, а тот — пока заберут выход, и всё встаёт. Звук
     * вкладывается в контейнер вперемешку с видео по времени.
     */
    private inner class Drain(val enc: MediaCodec, val mux: MediaMuxer, val audio: AudioOut?) : Thread("pov-mux") {
        @Volatile var error: Throwable? = null
        @Volatile var started = false
        @Volatile private var aborted = false
        fun abort() { aborted = true }

        override fun run() {
            val info = MediaCodec.BufferInfo()
            var vTrack = -1
            var aTrack = -1
            var ai = 0
            val aInfo = MediaCodec.BufferInfo()
            fun writeAudioUpTo(t: Long) {
                val au = audio ?: return
                if (aTrack < 0) return
                while (ai < au.data.size && au.pts[ai] <= t) {
                    val d = au.data[ai]
                    aInfo.set(0, d.size, au.pts[ai], au.flags[ai])
                    mux.writeSampleData(aTrack, ByteBuffer.wrap(d), aInfo)
                    ai++
                }
            }
            try {
                while (!aborted) {
                    val oi = enc.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        vTrack = mux.addTrack(enc.outputFormat)
                        val au = audio
                        if (au != null) aTrack = runCatching { mux.addTrack(au.format) }.getOrDefault(-1)
                        mux.start()
                        started = true
                        continue
                    }
                    if (oi < 0) continue
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && started) {
                        writeAudioUpTo(info.presentationTimeUs)
                        val ob = enc.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        mux.writeSampleData(vTrack, ob, info)
                    }
                    enc.releaseOutputBuffer(oi, false)
                    if (eos) break
                }
                if (started && !aborted) writeAudioUpTo(Long.MAX_VALUE)
            } catch (e: Throwable) {
                error = e
            }
        }
    }
}
