package com.povwheel.app.convert

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Быстрый последовательный декод видео в кадры устройства.
 *
 * `MediaMetadataRetriever.getFrameAtTime()` делает на КАЖДЫЙ кадр отдельный
 * seek к ближайшему опорному кадру и полный декод оттуда — на 1080p это ~200 мс,
 * отсюда и печальные ~3 к/с. Здесь поток видео прогоняется декодером один раз
 * подряд: кадр стоит десяток-другой миллисекунд, а тяжёлая полярная выборка и
 * median cut раскиданы по пулу воркеров и считаются, пока GPU готовит следующий.
 *
 * Ориентация подбирается по факту, а не по метаданным (`KEY_ROTATION` в
 * `MediaExtractor` есть только с API 29, часть декодеров крутит кадр сама):
 * отдельным пре-пассом декодируется ОДИН кадр из середины ролика, и из четырёх
 * поворотов берётся тот, при котором его миниатюра совпадает с эталонной из MMR
 * (`Converter` отдаёт её тем же кадром). Метаданные — только тай-брейк на
 * плоских/симметричных кадрах. Кадр из середины, а не из начала: там реальная
 * картинка, а не затемнение/титр, — по ней 0° от 180° и 90° от 270° различимы.
 *
 * Не поднялся кодек/GL — бросаем исключение, [Converter] откатывается на MMR.
 */
internal object VideoFrames {

    class SetupError(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private class Task(val idx: Int, val bmp: Bitmap?)
    private val STOP = Task(-1, null)

    private const val READ_CAP = 1440   // длинная сторона обратного чтения; дальше drawSquare → 400
    private const val THUMB_LONG = 160  // длинная сторона миниатюры для подбора поворота:
                                        // эталон (MMR, софт) и кадр (MediaCodec + GL)
                                        // декодируются по-разному, структура должна
                                        // перебивать пиксельный шум их разницы

    /** Грейскейл-миниатюра С СОХРАНЕНИЕМ пропорций (длинная сторона THUMB_LONG). */
    class OThumb(val w: Int, val h: Int, val px: IntArray)

    fun thumbOf(bmp: Bitmap): OThumb {
        val big = maxOf(bmp.width, bmp.height, 1)
        val tw = maxOf(1, bmp.width * THUMB_LONG / big)
        val th = maxOf(1, bmp.height * THUMB_LONG / big)
        val s = Bitmap.createScaledBitmap(bmp, tw, th, true)
        val p = IntArray(tw * th)
        s.getPixels(p, 0, tw, 0, 0, tw, th)
        s.recycle()
        for (i in p.indices) {
            val c = p[i]
            p[i] = ((c ushr 16 and 0xFF) * 77 + (c ushr 8 and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
        }
        return OThumb(tw, th, p)
    }

    private fun rotated(t: OThumb, deg: Int): OThumb {
        if (deg == 0) return t
        val nw = if (deg == 180) t.w else t.h
        val nh = if (deg == 180) t.h else t.w
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            for (x in 0 until nw) {
                val sx: Int
                val sy: Int
                when (deg) {
                    90 -> { sx = y; sy = t.h - 1 - x }
                    180 -> { sx = t.w - 1 - x; sy = t.h - 1 - y }
                    else -> { sx = t.w - 1 - y; sy = x }   // 270
                }
                out[y * nw + x] = t.px[sy * t.w + sx]
            }
        }
        return OThumb(nw, nh, out)
    }

    private fun meanOf(t: OThumb): Long {
        var s = 0L
        for (v in t.px) s += v
        return s / maxOf(1, t.px.size)
    }

    /**
     * Ошибка на пиксель с выравниванием по яркости: у эталона (MMR, софт) и кадра
     * (MediaCodec + GL) разный тон, поэтому обе миниатюры центрируем по своему
     * среднему, иначе постоянный сдвиг тона маскирует структурное совпадение.
     * [cand] ресемплится на сетку [ref] ближайшим соседом.
     */
    private fun diff(ref: OThumb, refMean: Long, cand: OThumb): Long {
        val cm = meanOf(cand)
        var e = 0L
        for (y in 0 until ref.h) {
            val cy = (y * cand.h / ref.h).coerceIn(0, cand.h - 1)
            for (x in 0 until ref.w) {
                val cx = (x * cand.w / ref.w).coerceIn(0, cand.w - 1)
                val d = (ref.px[y * ref.w + x] - refMean) - (cand.px[cy * cand.w + cx] - cm)
                e += d * d
            }
        }
        return e / (ref.w.toLong() * ref.h)
    }

    /**
     * Поворот кадра (0/90/180/270, по часовой), чтобы он совпал с эталоном из MMR
     * (тот уже правильно развёрнут). Сравнение — С УЧЁТОМ пропорций: только два
     * из четырёх поворотов дают ту же ориентацию (ландшафт/портрет), что у
     * эталона, — по ним и выбираем, ресемплируя на его сетку без «сплющивания в
     * квадрат» (оно и путало 90° с 270°). [hint] из метаданных — лёгкий перевес
     * на ничьей симметричного кадра.
     *
     * **Квадратный кадр (1:1) — особый случай.** Форма не отсекает ничего, и
     * перебор всех четырёх поворотов вырождается в орлянку между 0°/180° и
     * 90°/270° на любом сюжете без явной «верх-низ» асимметрии — отсюда ролики,
     * которые заливались лежащими на боку или вверх ногами, хотя в превью (оно из
     * MMR, всегда верное) всё правильно. Физически вариантов только два: декодер
     * уже развернул кадр как надо — тогда 0°, или отдал сырым — тогда его нужно
     * довернуть на угол из контейнера [hintN] (ровно то, что делает MMR для
     * эталона). Поэтому сравниваем именно эти два варианта и берём тот, что ближе
     * к эталону; 0° выигрывает лишь честную ничью (± 2 %, чтобы не дребезжать на
     * точной симметрии). Прежний вариант с перекосом в пользу 0° (доворот только
     * при выигрыше ≥ 20 %) глотал реальный разворот на сюжете без явной верх-низ
     * асимметрии — и ролик заливался вверх ногами, хотя MMR-превью было верным.
     * Надёжность даёт крупная миниатюра ([THUMB_LONG]) и выравнивание по яркости
     * в [diff] — пути декода у эталона (MMR, софт) и кадра (MediaCodec + GL) разные.
     */
    private fun matchRotation(ref: OThumb, frame: OThumb, hint: Int): Int {
        val hintN = ((hint % 360) + 360) % 360
        val refMean = meanOf(ref)

        if (ref.w == ref.h) {
            if (hintN != 90 && hintN != 180 && hintN != 270) return 0
            val e0 = diff(ref, refMean, frame)              // rotated(frame, 0) == frame
            val eh = diff(ref, refMean, rotated(frame, hintN))
            return if (eh * 100 < e0 * 98) hintN else 0     // доворот при любом явном выигрыше
        }

        val refLand = ref.w >= ref.h
        var best = hintN
        var bestErr = Long.MAX_VALUE
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val r = rotated(frame, deg)
            if ((r.w >= r.h) != refLand) continue           // не та ориентация — мимо
            var e = diff(ref, refMean, r)
            if (deg == hintN) e = e * 9 / 10                 // перевес подсказке
            if (e < bestErr) { bestErr = e; best = deg }
        }
        return best
    }

    /**
     * Декодирует [n] кадров с шагом 1/[fps] c, вписывает каждый в квадрат
     * `Geom.SRC_SIZE_VID` и пишет квантованный результат прямо в [out].
     * [rotation] — угол из `METADATA_KEY_VIDEO_ROTATION`.
     */
    fun decode(
        context: Context,
        uri: Uri,
        out: ByteArray,
        fitMode: Int,
        fps: Int,
        n: Int,
        rotation: Int,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, HashMap<String, String>())
        } catch (e: Exception) {
            extractor.release()
            throw SetupError("cannot open video", e)
        }

        var trackIdx = -1
        var trackFmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                trackIdx = i; trackFmt = f; break
            }
        }
        val format = trackFmt
        if (trackIdx < 0 || format == null) {
            extractor.release()
            throw SetupError("no video track")
        }
        extractor.selectTrack(trackIdx)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codedW = format.getInteger(MediaFormat.KEY_WIDTH)
        val codedH = format.getInteger(MediaFormat.KEY_HEIGHT)
        // Не даём декодеру самому крутить кадр — крутим на CPU предсказуемым углом.
        format.setInteger(MediaFormat.KEY_ROTATION, 0)
        val normRotation = ((rotation % 360) + 360) % 360

        val guess = readSide(codedW, codedH)
        val scaler = try {
            GlVideoScaler(guess.first, guess.second)
        } catch (e: Throwable) {
            extractor.release()
            throw SetupError("GL init failed", e)
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, scaler.surface, null, 0)
                start()
            }
        } catch (e: Throwable) {
            scaler.release()
            extractor.release()
            throw SetupError("decoder init failed", e)
        }

        val nWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, 3)
        val queue = ArrayBlockingQueue<Task>(nWorkers * 2)
        val free = ArrayBlockingQueue<Bitmap>(nWorkers + 2)
        val done = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        var workers: List<Thread> = emptyList()

        var decW = codedW
        var decH = codedH
        var readW = guess.first
        var readH = guess.second
        var cpuRotation = normRotation

        try {
            // ===== пре-пасс: кадр из середины → размеры + ОТПЕЧАТОК ориентации =====
            // Берём кадр декодера с временем ~середины ролика, запоминаем его pts.
            var probeThumb: OThumb? = null
            var probePts = 0L
            run {
                val info = MediaCodec.BufferInfo()
                val durUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                val refTimeUs = if (durUs > 0) minOf(durUs / 3, 2_500_000L) else 500_000L
                runCatching { extractor.seekTo(refTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
                var inEos = false
                var got = false
                val deadline = System.currentTimeMillis() + 10_000
                while (!got && System.currentTimeMillis() < deadline) {
                    failure.get()?.let { throw it }
                    if (!inEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)
                            val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inEos = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000L)
                    if (oi < 0) continue
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val use = info.size > 0 && (info.presentationTimeUs >= refTimeUs || eos)
                    codec.releaseOutputBuffer(oi, use)
                    if (use) {
                        scaler.awaitFrame()
                        probePts = info.presentationTimeUs
                        runCatching { codec.outputFormat }.getOrNull()?.let { ofmt ->
                            val d = outputDims(ofmt, codedW, codedH)
                            decW = d.first; decH = d.second
                        }
                        val rs = readSide(decW, decH)
                        readW = rs.first; readH = rs.second
                        scaler.setOutputSize(readW, readH)
                        val probe = Bitmap.createBitmap(readW, readH, Bitmap.Config.ARGB_8888)
                        try {
                            scaler.renderInto(probe, decW, decH)
                            probeThumb = thumbOf(probe)
                        } finally {
                            probe.recycle()
                        }
                        got = true
                    }
                    if (eos) break
                }
                if (!got) scaler.setOutputSize(readW, readH)
            }

            // ===== эталон из MMR на ТОТ ЖЕ pts → сверка поворота =====
            // Один и тот же кадр: попиксельная разница у верного поворота почти
            // ноль, у неверного — велика, решение однозначно. Эвристик по знакам
            // матрицы SurfaceTexture больше нет — на разных телефонах они разные,
            // и именно они переворачивали кадр.
            cpuRotation = normRotation
            val pt = probeThumb
            if (pt != null) {
                val refThumb: OThumb? = try {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(context, uri)
                        mmr.getFrameAtTime(probePts, MediaMetadataRetriever.OPTION_CLOSEST)?.let { r ->
                            val t = thumbOf(r); r.recycle(); t
                        }
                    } finally { runCatching { mmr.release() } }
                } catch (e: Exception) { null }
                if (refThumb != null) cpuRotation = matchRotation(refThumb, pt, normRotation)
            }

            // ===== назад в начало, сброс декодера =====
            runCatching { codec.flush() }
            runCatching { extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }

            // ===== пул воркеров (cpuRotation / readW / readH известны) =====
            val swap = cpuRotation == 90 || cpuRotation == 270
            repeat(nWorkers + 2) {
                free.put(Bitmap.createBitmap(readW, readH, Bitmap.Config.ARGB_8888))
            }
            workers = (0 until nWorkers).map { w ->
                Thread({
                    val sampler = PolarSampler()
                    val quant = Quantizer()
                    val work = Bitmaps.square(Geom.SRC_SIZE_VID)
                    var rot: Bitmap? = null
                    try {
                        while (true) {
                            val t = queue.take()
                            if (t.idx < 0 || t.bmp == null) break
                            val frame = t.bmp
                            val forFit: Bitmap
                            if (cpuRotation == 0) {
                                forFit = frame
                            } else {
                                val rw = if (swap) readH else readW
                                val rh = if (swap) readW else readH
                                var r = rot
                                if (r == null) {
                                    r = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888); rot = r
                                }
                                Bitmaps.rotateDegrees(frame, r, cpuRotation)
                                forFit = r
                            }
                            Bitmaps.drawSquare(forFit, work, fitMode)
                            free.put(frame)
                            sampler.frameInto(work, 2, quant, out, Ani6.frameOffset(t.idx))
                            onProgress(done.incrementAndGet(), n)
                        }
                    } catch (e: InterruptedException) {
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    } finally {
                        work.recycle()
                        rot?.recycle()
                    }
                }, "vid-quant-$w").apply { isDaemon = true; start() }
            }

            // ===== основной пасс =====
            val produced = mainLoop(extractor, codec, scaler, decW, decH, fps, n, queue, free, failure)

            failure.get()?.let { throw it }
            if (produced <= 0) throw IllegalStateException("no frames could be read from this video")

            val last = produced - 1
            for (k in produced until n) {
                System.arraycopy(out, Ani6.frameOffset(last), out, Ani6.frameOffset(k), Geom.FRAME_STRIDE)
            }
            onProgress(n, n)
        } finally {
            val hadFailure = failure.get() != null
            if (!hadFailure && workers.isNotEmpty()) {
                repeat(workers.size) { runCatching { queue.offer(STOP, 5, TimeUnit.SECONDS) } }
            }
            workers.forEach { t ->
                runCatching { t.join(if (hadFailure) 500 else 5000) }
                if (t.isAlive) { t.interrupt(); runCatching { t.join(500) } }
            }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            scaler.release()
            extractor.release()
        }
    }

    /** Размер обратного чтения: длинная сторона ≤ READ_CAP, пропорции сохранены. */
    private fun readSide(w: Int, h: Int): Pair<Int, Int> {
        val big = maxOf(w, h, 1)
        return if (big > READ_CAP)
            Pair(maxOf(1, w * READ_CAP / big), maxOf(1, h * READ_CAP / big))
        else
            Pair(maxOf(1, w), maxOf(1, h))
    }

    /** Реальный размер кадра на выходе декодера (учитывая кроп, если он в формате). */
    private fun outputDims(f: MediaFormat, fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
        var w = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(fallbackW)
        var h = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(fallbackH)
        val cl = runCatching { f.getInteger("crop-left") }.getOrDefault(-1)
        val cr = runCatching { f.getInteger("crop-right") }.getOrDefault(-1)
        val ct = runCatching { f.getInteger("crop-top") }.getOrDefault(-1)
        val cb = runCatching { f.getInteger("crop-bottom") }.getOrDefault(-1)
        if (cl in 0..cr) w = cr - cl + 1
        if (ct in 0..cb) h = cb - ct + 1
        return Pair(maxOf(1, w), maxOf(1, h))
    }

    /** @return сколько кадров реально отрендерено и отдано в очередь (0..n). */
    private fun mainLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        scaler: GlVideoScaler,
        decW: Int, decH: Int,
        fps: Int, n: Int,
        queue: ArrayBlockingQueue<Task>,
        free: ArrayBlockingQueue<Bitmap>,
        failure: AtomicReference<Throwable?>
    ): Int {
        val info = MediaCodec.BufferInfo()
        val timeoutUs = 10_000L
        var inEos = false
        var outEos = false
        var wantIdx = 0
        var wantUs = 0L

        fun takeBitmap(): Bitmap {
            while (true) {
                failure.get()?.let { throw it }
                free.poll(200, TimeUnit.MILLISECONDS)?.let { return it }
            }
        }

        while (!outEos && wantIdx < n) {
            failure.get()?.let { throw it }

            if (!inEos) {
                val inIdx = codec.dequeueInputBuffer(timeoutUs)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)
                    val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (outIdx < 0) continue

            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            val pts = info.presentationTimeUs
            val take = info.size > 0 && wantIdx < n && pts >= wantUs
            codec.releaseOutputBuffer(outIdx, take)

            if (take) {
                scaler.awaitFrame()
                while (wantIdx < n && pts >= wantUs) {
                    val bmp = takeBitmap()
                    try {
                        scaler.renderInto(bmp, decW, decH)
                    } catch (e: Throwable) {
                        free.put(bmp)
                        throw e
                    }
                    queue.put(Task(wantIdx, bmp))
                    wantIdx++
                    wantUs = wantIdx.toLong() * 1_000_000L / fps
                }
            }
            if (eos) outEos = true
        }
        return wantIdx
    }
}
