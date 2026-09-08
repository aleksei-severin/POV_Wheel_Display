package com.povwheel.app.convert

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
 * подряд: декодер не перезапускается, кадр стоит десяток-другой миллисекунд, а
 * тяжёлая полярная выборка и median cut раскиданы по пулу воркеров и считаются,
 * пока GPU готовит следующий кадр.
 *
 * Декодер (`GlVideoScaler`) отдаёт кадр уже уменьшенным; поворот ролика и
 * вписывание Crop/Fit делает CPU той же проверенной геометрией, что у картинок
 * и GIF (`Bitmaps.rotateDegrees` + `Bitmaps.drawSquare`).
 *
 * Если этот телефон/кодек/GL не поднимается — бросаем исключение, и [Converter]
 * откатывается на медленный, но всеядный путь через MMR.
 */
internal object VideoFrames {

    class SetupError(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private class Task(val idx: Int, val bmp: Bitmap?)
    private val STOP = Task(-1, null)

    /**
     * Декодирует [n] кадров с шагом 1/[fps] c, вписывает каждый в квадрат
     * `Geom.SRC_SIZE_VID` и пишет квантованный результат прямо в [out] по
     * смещениям `Ani6.frameOffset(i)`. Заголовок ANI6 в [out] уже проставлен.
     */
    fun decode(
        context: Context,
        uri: Uri,
        out: ByteArray,
        fitMode: Int,
        fps: Int,
        n: Int,
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
        val srcW = format.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = format.getInteger(MediaFormat.KEY_HEIGHT)
        // MediaFormat.containsKey — только с API 29, поэтому просто ловим NPE от
        // отсутствующего ключа. Значение уже нормализовано в 0/90/180/270.
        val rotation = try {
            format.getInteger(MediaFormat.KEY_ROTATION)
        } catch (_: Exception) { 0 }

        val scaler = try {
            GlVideoScaler(srcW, srcH)
        } catch (e: Throwable) {
            extractor.release()
            throw SetupError("GL init failed", e)
        }
        val readW = scaler.outW
        val readH = scaler.outH

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

        // ---- пул воркеров: поворот + вписывание + выборка + квантование ----
        // Воркеров немного: каждый держит Quantizer (~4.5 МБ гистограмма) и
        // буфер под поворот (~кадр). 3 хватает, чтобы спрятать выборку за декод.
        val nWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, 3)
        val queue = ArrayBlockingQueue<Task>(nWorkers * 2)
        val free = ArrayBlockingQueue<Bitmap>(nWorkers + 2)
        val done = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val swap = rotation == 90 || rotation == 270
        var workers: List<Thread> = emptyList()

        val produced: Int
        try {
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
                            if (rotation == 0) {
                                forFit = frame
                            } else {
                                val rw = if (swap) readH else readW
                                val rh = if (swap) readW else readH
                                var r = rot
                                if (r == null) {
                                    r = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888); rot = r
                                }
                                Bitmaps.rotateDegrees(frame, r, rotation)
                                forFit = r
                            }
                            Bitmaps.drawSquare(forFit, work, fitMode)
                            free.put(frame)      // вернуть в пул ПОСЛЕ того, как drawSquare прочитал
                            sampler.frameInto(work, 2, quant, out, Ani6.frameOffset(t.idx))
                            onProgress(done.incrementAndGet(), n)
                        }
                    } catch (e: InterruptedException) {
                        // штатное завершение при аварии
                    } catch (e: Throwable) {
                        failure.compareAndSet(null, e)
                    } finally {
                        work.recycle()
                        rot?.recycle()
                    }
                }, "vid-quant-$w").apply { isDaemon = true; start() }
            }

            produced = runLoop(extractor, codec, scaler, fps, n, queue, free, failure)
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

        failure.get()?.let { throw SetupError("frame worker failed", it) }
        if (produced <= 0) throw IllegalStateException("no frames could be read from this video")

        // Ролик короче запрошенного — добиваем хвост последним кадром, ровно как
        // старый путь. Воркеры уже завершены, поэтому out[produced-1] дописан.
        val last = produced - 1
        for (k in produced until n) {
            System.arraycopy(out, Ani6.frameOffset(last), out, Ani6.frameOffset(k), Geom.FRAME_STRIDE)
        }
        onProgress(n, n)
    }

    /** @return сколько кадров реально отрендерено и отдано в очередь (0..n). */
    private fun runLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        scaler: GlVideoScaler,
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

            // --- подать сжатые сэмплы ---
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

            // --- забрать декодированные ---
            val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (outIdx < 0) continue    // TRY_AGAIN / FORMAT_CHANGED — размеры берём из формата трека

            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            val pts = info.presentationTimeUs
            // Берём первый кадр, чей pts дотянул до текущей отметки («вперёд-ближайший»).
            val take = info.size > 0 && wantIdx < n && pts >= wantUs
            codec.releaseOutputBuffer(outIdx, take)

            if (take) {
                scaler.awaitFrame()
                // Один кадр может закрыть несколько отметок, если fps выше
                // частоты ролика — рендерим столько раз (текстура уже загружена).
                while (wantIdx < n && pts >= wantUs) {
                    val bmp = takeBitmap()
                    try {
                        scaler.renderInto(bmp)
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
