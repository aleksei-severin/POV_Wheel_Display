package com.povwheel.app.povvideo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.povwheel.app.convert.GlVideoScaler
import com.povwheel.app.convert.VideoFrames

/**
 * Проход декодера для привязки ролика к логу Холла: каждый кадр отрезка уменьшается на
 * GPU до ~320 точек по длинной стороне, переводится в яркость и уходит в
 * [PovAlignCore.Collector] — средняя яркость и межкадровая разность. Цвет и поворот из
 * метаданных не нужны: эти числа от ориентации кадра и от того, где в нём колесо, не
 * зависят.
 */
internal object PovVisDecoder {

    private const val LONG_SIDE = 320

    /**
     * Кадры с метками [fromUs]…[toUs] (мкс от первого кадра ролика; [startUs] — метка
     * первого кадра в контейнере) → сводка кадров. [progress] — доля пройденного отрезка.
     */
    fun bin(
        ctx: Context, uri: Uri, startUs: Long, fromUs: Long, toUs: Long,
        cancelled: () -> Boolean, progress: (Double) -> Unit
    ): PovAlignCore.Stats {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var scaler: GlVideoScaler? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val (track, fmt) = VideoFrames.videoTrack(ex) ?: throw IllegalStateException("no video track")
            ex.selectTrack(track)
            val codedW = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val codedH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            fmt.setInteger(MediaFormat.KEY_ROTATION, 0)
            var (ow, oh) = side(codedW, codedH)
            val sc = GlVideoScaler(ow, oh)
            scaler = sc
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, sc.surface, null, 0)
            c.start()

            val absFrom = startUs + fromUs
            val absTo = startUs + toUs
            ex.seekTo(absFrom, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var coll: PovAlignCore.Collector? = null
            var luma = ByteArray(ow * oh)
            var decW = codedW
            var decH = codedH
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            var sinceCheck = 0
            while (!outEos) {
                if (++sinceCheck >= 16) { sinceCheck = 0; if (cancelled()) throw InterruptedException() }
                if (!inEos) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)
                        val t = ex.sampleTime
                        val size = if (buf != null && t >= 0 && t <= absTo + 200_000) ex.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, t, 0); ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val d = outputDims(c.outputFormat, codedW, codedH)
                    decW = d.first; decH = d.second
                    continue
                }
                if (oi < 0) continue
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                val pts = info.presentationTimeUs
                val take = info.size > 0 && pts >= absFrom && pts <= absTo
                c.releaseOutputBuffer(oi, take)
                if (!take) continue
                sc.awaitFrame()
                if (coll == null) {
                    val s = side(decW, decH)
                    ow = s.first; oh = s.second
                    sc.setOutputSize(ow, oh)
                    luma = ByteArray(ow * oh)
                    coll = PovAlignCore.Collector(ow, oh)
                }
                sc.renderLuma(luma, decW, decH)
                coll.frame(luma, pts - startUs)
                if (toUs > fromUs) progress(((pts - absFrom).toDouble() / (toUs - fromUs)).coerceIn(0.0, 1.0))
            }
            return coll?.finish() ?: PovAlignCore.Collector(1, 1).finish()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { scaler?.release() }
            runCatching { ex.release() }
        }
    }

    private fun side(w: Int, h: Int): Pair<Int, Int> {
        val big = maxOf(w, h, 1)
        return Pair(maxOf(16, w * LONG_SIDE / big), maxOf(16, h * LONG_SIDE / big))
    }

    private fun outputDims(f: MediaFormat, fw: Int, fh: Int): Pair<Int, Int> {
        var w = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(fw)
        var h = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(fh)
        val cl = runCatching { f.getInteger("crop-left") }.getOrDefault(-1)
        val cr = runCatching { f.getInteger("crop-right") }.getOrDefault(-1)
        val ct = runCatching { f.getInteger("crop-top") }.getOrDefault(-1)
        val cb = runCatching { f.getInteger("crop-bottom") }.getOrDefault(-1)
        if (cl in 0..cr) w = cr - cl + 1
        if (ct in 0..cb) h = cb - ct + 1
        return Pair(maxOf(1, w), maxOf(1, h))
    }
}
