package com.povwheel.app.povvideo

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Разметка видео на склейки — то же, что считает tools/pov_fps_blend/POV-BlendFPS.ps1
 * перед рендером. Элемент i плана:
 *  - kind 0 — [counts] кадров идут как есть (колесо не рисует: тиков нет);
 *  - kind 1 — [counts] кадров склеиваются в один, который стоит на экране всё их время
 *    (часть длинной прорисовки, разбитой ради нижнего предела частоты кадров);
 *  - kind 2 — окно ровно в одну прорисовку: смешивание шести сдвинутых окон с дробным
 *    началом ([segT0], [segT] — точное начало и длина прорисовки в кадрах).
 */
class PovPlan(
    val kinds: IntArray,
    val counts: IntArray,
    val segT0: DoubleArray,
    val segT: DoubleArray
) {
    val totalFrames: Int = counts.sum()
}

/** Всё, что нужно рендеру, плюс строки сводки для экрана. */
class PovAnalysis(
    val uri: Uri,
    val displayName: String,
    /** Папка исходника в галерее (MediaStore RELATIVE_PATH), если её удалось узнать. */
    val relativePath: String?,
    val codedW: Int,
    val codedH: Int,
    val rotation: Int,
    val fileFps: Double,
    val durationSec: Double,
    /** Битрейт видеодорожки исходника, бит/с (0 — неизвестен). От него считается битрейт результата. */
    val videoBps: Double,
    /** Кодек исходника (MIME). */
    val videoMime: String,
    /** Во сколько раз файл медленнее реального времени (1 — обычная съёмка). */
    val slow: Double,
    val tracks: List<TickTrack>,
    /** Метки времени кадров, мкс от первого кадра, по возрастанию. */
    val ptsUs: LongArray,
    /** Метка первого кадра по шкале контейнера, мкс. */
    val videoStartUs: Long,
    val plan: PovPlan,
    val sweeps: Int,
    val report: List<String>
) {
    val renderable: Boolean get() = tracks.isNotEmpty() && plan.totalFrames > 0
    val outName: String get() = displayName.substringBeforeLast('.') + "_sync_" + sweeps + "sweeps.mp4"
}

/** Звук ролика, декодированный в PCM. */
internal class DecodedAudio(
    val sampleRate: Int,
    val channels: Int,
    /** Время первого сэмпла, мкс по шкале контейнера. */
    val startUs: Long,
    /** Для детектора: −1..1, [detChannels] каналов вперемешку (не больше двух). */
    val det: FloatArray?,
    val detChannels: Int,
    /** Чередующиеся каналы, 16 бит — для звука результата. */
    val pcm: ShortArray?,
    val frames: Int
)

internal object PovAnalyzer {

    // Параметры — те же, что по умолчанию у скрипта. Чирп — как в прошивке (PIEZO_CHIRP_*).
    const val CHIRP_LO_HZ = 15000.0
    const val CHIRP_HI_HZ = 20000.0
    const val CHIRP_SEC = 0.015
    private const val CHIRP_FADE_SEC = 0.0015
    private const val MIN_REV_MS = 80.0
    private const val MIN_RPM = 90.0
    const val MIN_FPS = 10.0
    const val MIN_WIN_FRAMES = 4         // = PovRenderer.MIN_WIN_FRAMES, -MinWindowFrames скрипта
    private const val BEEPS_PER_REV = 6
    private const val ARMS = 6
    private const val PER_BEEP = ARMS / BEEPS_PER_REV

    private val minPeriod = MIN_REV_MS / 1000.0 / BEEPS_PER_REV
    private val maxPeriod = 60.0 / (MIN_RPM * BEEPS_PER_REV)

    class NoAudio : Exception("this video has no audio track")

    fun analyze(
        ctx: Context, uri: Uri,
        step: (String) -> Unit,
        cancelled: () -> Boolean
    ): PovAnalysis {
        step("Reading video…")
        val (name, relPath) = sourceInfo(ctx, uri)

        val mmr = MediaMetadataRetriever()
        var codedW = 0; var codedH = 0; var rotation = 0; var captureFps = 0.0; var fileBps = 0.0
        try {
            mmr.setDataSource(ctx, uri)
            codedW = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            codedH = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            captureFps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull() ?: 0.0
            fileBps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toDoubleOrNull() ?: 0.0
        } finally { runCatching { mmr.release() } }
        rotation = ((rotation % 360) + 360) % 360

        // Метки кадров — из индекса контейнера, без декодирования.
        val vi = videoIndex(ctx, uri)
        val ptsAbs = vi.pts
        val videoStartUs = vi.startUs
        if (ptsAbs.size < 2) throw IllegalStateException("no video frames found")
        val pts = LongArray(ptsAbs.size) { ptsAbs[it] - videoStartUs }
        val avgDt = (pts.last() - pts.first()).toDouble() / (pts.size - 1) / 1e6
        val fileFps = 1.0 / avgDt
        val durationSec = pts.last() / 1e6 + avgDt
        // Битрейт видео — по сумме сжатых кадров; где размеры кадров недоступны (до
        // Android 9) — общий битрейт файла, звук в нём — малая доля.
        val videoBps = if (vi.bytes > 0 && durationSec > 0) vi.bytes * 8.0 / durationSec else fileBps
        if (cancelled()) throw InterruptedException()

        step("Decoding audio…")
        val audio = decodeAudio(ctx, uri, wantDet = true, wantPcm = false, cancelled = cancelled)
            ?: throw NoAudio()
        if (cancelled()) throw InterruptedException()
        val det = audio.det!!
        val offsetSec = (audio.startUs - videoStartUs) / 1e6

        // Подсказка о замедлении — частота съёмки из метаданных против частоты файла.
        val hint = if (captureFps > 0 && fileFps > 0) (captureFps / fileFps).roundToInt() else 0
        val slowTry = ArrayList<Double>()
        for (k in intArrayOf(hint, 1, 4, 8, 2)) if (k >= 1 && !slowTry.contains(k.toDouble())) slowTry.add(k.toDouble())

        // Перебор замедлений: звук «разгоняется» объявлением частоты дискретизации в k раз
        // выше (тот же asetrate, что в скрипте), чирпы ищутся с обычными параметрами. Первое
        // замедление, где нашлось ≥ 4 оборотов, — берём.
        val tried = ArrayList<String>()
        var best = -1
        var bestTracks: List<TickTrack> = emptyList()
        var bestInfo: PovChirp.Result? = null
        var slow = slowTry[0]
        for (k in slowTry) {
            if (cancelled()) throw InterruptedException()
            val srEff = (audio.sampleRate * k).roundToInt()
            if (srEff * 0.47 - CHIRP_LO_HZ < 1000) {
                tried.add(fmt("  slow ×%s: audio too narrow (%d Hz) — skipped", num(k), audio.sampleRate))
                continue
            }
            step("Looking for sync chirps" + (if (k > 1) " (slow motion ×" + num(k) + ")" else "") + "…")
            val r = PovChirp(CHIRP_LO_HZ, CHIRP_HI_HZ, CHIRP_SEC, CHIRP_FADE_SEC)
                .run(det, audio.detChannels, srEff, minPeriod, maxPeriod, 2 * BEEPS_PER_REV, cancelled)
            // Времена — по шкале файла: растягиваем в k раз и сдвигаем на начало звука.
            val fileTracks = r.tracks.map { t -> TickTrack(DoubleArray(t.times.size) { t.times[it] * k + offsetSec }, t.real, t.dir) }
            val iv = fileTracks.sumOf { it.times.size - 1 }
            tried.add(fmt("  slow ×%s (in file %s–%s kHz, chirp %s ms): %d intervals",
                num(k), num(CHIRP_LO_HZ / k / 1000), num(CHIRP_HI_HZ / k / 1000), num(CHIRP_SEC * 1000 * k), iv))
            if (iv > best) { best = iv; bestTracks = fileTracks; slow = k; bestInfo = r }
            if (iv >= 4 * BEEPS_PER_REV) break
        }

        step("Planning…")
        // Треки — в пределах ролика.
        val coverEnd = durationSec
        val tracks = bestTracks.mapNotNull { t ->
            val idx = t.times.indices.filter { t.times[it] >= 0 && t.times[it] <= coverEnd }
            if (idx.size < 2) null else TickTrack(DoubleArray(idx.size) { t.times[idx[it]] }, BooleanArray(idx.size) { t.real[idx[it]] }, t.dir)
        }

        // Разметка: отрисовка (интервалы между тиками) и всё остальное (кадры как есть).
        class Seg(val native: Boolean, val t0: Double, val t1: Double, val wins: Int)
        val segs = ArrayList<Seg>()
        var cursor = 0.0
        var sweeps = 0
        var fpsSplit = 0
        for (t in tracks) {
            val ts = t.times
            if (ts[0] > cursor) segs.add(Seg(true, cursor, ts[0], 0))
            for (k in 0 until ts.size - 1) {
                val dur = ts[k + 1] - ts[k]
                val wins = max(PER_BEEP, ceil(dur / slow * MIN_FPS - 1e-6).toInt())
                if (wins > PER_BEEP) fpsSplit++
                sweeps += wins
                segs.add(Seg(false, ts[k], ts[k + 1], wins))
            }
            cursor = ts.last()
        }
        if (coverEnd > cursor) segs.add(Seg(true, cursor, coverEnd, 0))

        val ptsSec = DoubleArray(pts.size) { pts[it] / 1e6 }
        fun frameIdx(t: Double): Double = frameIndex(ptsSec, t)
        fun frameAt(t: Double): Int = Math.rint(frameIdx(t)).toInt().coerceIn(0, pts.size)
        val kinds = ArrayList<Int>()
        val counts = ArrayList<Int>()
        val segT0 = ArrayList<Double>()
        val segT = ArrayList<Double>()
        for (sg in segs) {
            if (sg.native) {
                val n = frameAt(sg.t1) - frameAt(sg.t0)
                if (n > 0) { kinds.add(0); counts.add(n); segT0.add(0.0); segT.add(0.0) }
                continue
            }
            for (w in 0 until sg.wins) {
                val ta = if (w == 0) sg.t0 else sg.t0 + (sg.t1 - sg.t0) * w / sg.wins
                val tb = if (w == sg.wins - 1) sg.t1 else sg.t0 + (sg.t1 - sg.t0) * (w + 1) / sg.wins
                val n = frameAt(tb) - frameAt(ta)
                if (n < 1) continue
                kinds.add(if (sg.wins == PER_BEEP) 2 else 1); counts.add(n)
                val fa = frameIdx(ta)
                segT0.add(fa); segT.add(frameIdx(tb) - fa)
            }
        }
        val plan = PovPlan(kinds.toIntArray(), counts.toIntArray(), segT0.toDoubleArray(), segT.toDoubleArray())

        // ---- сводка — то же, что скрипт выводит в консоль ----
        val rep = ArrayList<String>()
        val dispW = if (rotation == 90 || rotation == 270) codedH else codedW
        val dispH = if (rotation == 90 || rotation == 270) codedW else codedH
        rep.add(fmt("Video: %d×%d, %s fps, %s s", dispW, dispH, num2(fileFps), num2(durationSec)) +
            if (videoBps > 0) ", " + num(Math.rint(videoBps / 1e5) / 10) + " Mbit/s" else "")
        if (captureFps > 0 && hint >= 2) rep.add(fmt("  captured at %s fps — looks like slow motion ×%d", num2(captureFps), hint))
        if (tried.size > 1) rep.addAll(tried)
        if (slow > 1) rep.add(fmt("Slow motion ×%s: rpm and intervals below are real; segment times are in the file's own time; the result plays in real time.", num(slow)))
        val info = bestInfo
        rep.add(fmt("Tick candidates: %d; rendering segments: %d", info?.candidates ?: 0, tracks.size))
        val w = info?.weights
        if (info != null && w != null && info.bandLo > 0) {
            val how = when (info.hyp) { "equal" -> "all bands equal"; "presence" -> "where the band is heard"; "learned" -> "learned from ticks"; else -> info.hyp }
            rep.add(fmt("  chirp heard at %s–%s kHz; band weights %s (%s)", num(info.bandLo / 1000), num(info.bandHi / 1000),
                w.joinToString(" ") { String.format(Locale.US, "%.2f", it) }, how))
        }
        tracks.forEachIndexed { i, t ->
            val ts = t.times
            var rMin = Double.MAX_VALUE; var rMax = 0.0
            for (k in 0 until ts.size - 1) {
                val rpm = 60.0 / ((ts[k + 1] - ts[k]) / slow * BEEPS_PER_REV)
                rMin = min(rMin, rpm); rMax = max(rMax, rpm)
            }
            val filled = t.real.count { !it }
            rep.add(fmt("  segment %d: %s–%s s, %d tick intervals (%d filled in), %d..%d rpm, sweep %s",
                i + 1, num3(ts.first()), num3(ts.last()), ts.size - 1, filled, rMin.roundToInt(), rMax.roundToInt(),
                if (t.dir < 0) "down (rear wheel or spinning backwards)" else "up"))
        }
        val natives = segs.filter { it.native && it.t1 - it.t0 > 0 }
        if (natives.isNotEmpty())
            rep.add("  no ticks, frames kept as recorded: " + natives.joinToString(", ") { num3(it.t0) + "–" + num3(it.t1) + " s" })
        rep.add(fmt("Blended frames (sweeps): %d", sweeps) +
            if (fpsSplit > 0) fmt(" — %d intervals longer than 1/%s s were split to keep ≥ %s fps", fpsSplit, num(MIN_FPS), num(MIN_FPS)) else "")
        // Медленная съёмка: прорисовка короче MIN_WIN_FRAMES кадров — окно склейки шире её.
        val ivs = ArrayList<Double>()
        for (t in tracks) for (k in 1 until t.times.size) ivs.add(t.times[k] - t.times[k - 1])
        if (ivs.isNotEmpty()) {
            ivs.sort()
            val framesPerSweep = ivs[ivs.size / 2] * fileFps
            if (framesPerSweep < MIN_WIN_FRAMES)
                rep.add(fmt("  a sweep is only ~%s frames: blend windows widened to %d frames (~%s sweeps) to close the gaps the camera's shutter leaves. Film at 120–240 fps for crisp results.",
                    num(Math.rint(framesPerSweep * 10) / 10), MIN_WIN_FRAMES, num(Math.rint(MIN_WIN_FRAMES / framesPerSweep * 10) / 10)))
        }
        if (tracks.isEmpty()) {
            rep.add("No rendering found — no sync chirps on the audio (the piezo only chirps while all six arms are powered).")
        } else {
            rep.add(fmt("Result: %s s at %s fps%s", num2(durationSec / slow), num2(fileFps * slow),
                if (slow > 1) " (real time)" else ""))
        }

        return PovAnalysis(
            uri, name, relPath, codedW, codedH, rotation, fileFps, durationSec, videoBps, vi.mime, slow,
            tracks, pts, videoStartUs, plan, sweeps, rep
        )
    }

    /** Дробный номер кадра для момента t (кадр j — от pts[j] до pts[j+1]). */
    fun frameIndex(pts: DoubleArray, t: Double): Double {
        val n = pts.size
        if (n == 0) return 0.0
        if (n == 1) return if (t >= pts[0]) 0.5 else 0.0
        if (t <= pts[0]) return (t - pts[0]) / (pts[1] - pts[0])
        if (t >= pts[n - 1]) return n - 1 + (t - pts[n - 1]) / (pts[n - 1] - pts[n - 2])
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (pts[m] <= t) lo = m else hi = m }
        return lo + (t - pts[lo]) / max(1e-9, pts[lo + 1] - pts[lo])
    }

    /** Имя файла и его папка в галерее (RELATIVE_PATH есть с Android 10). */
    private fun sourceInfo(ctx: Context, uri: Uri): Pair<String, String?> {
        var name = "video.mp4"
        var rel: String? = null
        val cols = if (Build.VERSION.SDK_INT >= 29)
            arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        else arrayOf(OpenableColumns.DISPLAY_NAME)
        runCatching {
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (cols.size > 1) rel = c.getString(1)
                }
            }
        }.onFailure {
            // Не все провайдеры знают RELATIVE_PATH — спросим одно имя.
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
            }
        }
        return Pair(name, rel)
    }

    /** Метки времени всех кадров видео (мкс, по возрастанию) и самая ранняя из них. */
    /** Индекс видеодорожки: метки кадров, первая метка, объём сжатых кадров (0 — неизвестен), кодек. */
    private class VideoIndex(val pts: LongArray, val startUs: Long, val bytes: Long, val mime: String)

    private fun videoIndex(ctx: Context, uri: Uri): VideoIndex {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var mime = ""
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("video/")) { track = i; mime = m; break }
            }
            if (track < 0) throw IllegalStateException("no video track")
            ex.selectTrack(track)
            var arr = LongArray(4096)
            var n = 0
            var bytes = 0L
            val sized = Build.VERSION.SDK_INT >= 28   // getSampleSize — с Android 9
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if (n == arr.size) arr = arr.copyOf(arr.size * 2)
                arr[n++] = t
                if (sized) bytes += max(0L, ex.sampleSize)
                if (!ex.advance()) break
            }
            val out = arr.copyOf(n)
            out.sort()
            return VideoIndex(out, if (n > 0) out[0] else 0L, bytes, mime)
        } finally { runCatching { ex.release() } }
    }

    /**
     * Декодирует звуковую дорожку целиком: для детектора — float, не больше двух каналов
     * вперемешку (стерео не сводится в моно: два микрофона телефона на 15–20 кГц бывают в
     * противофазе, и сумма гасила бы чирп — детектор складывает их по энергиям); и/или
     * 16-битный PCM всех каналов.
     */
    fun decodeAudio(
        ctx: Context, uri: Uri, wantDet: Boolean, wantPcm: Boolean,
        cancelled: () -> Boolean
    ): DecodedAudio? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            val startUs = max(0L, ex.sampleTime)
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var isFloat = false
            var det = FloatArray(if (wantDet) 1 shl 20 else 0)
            var pcm = ShortArray(if (wantPcm) 1 shl 20 else 0)
            var nDet = 0
            var detCh = 0
            var nPcm = 0
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            var sinceCheck = 0
            while (!outEos) {
                if (++sinceCheck >= 64) { sinceCheck = 0; if (cancelled()) throw InterruptedException() }
                if (!inEos) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        isFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    continue
                }
                if (oi < 0) continue
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                if (info.size > 0) {
                    val ob = c.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val chn = max(1, ch)
                    val dch = min(chn, 2)                 // детектору — первые два канала
                    if (wantDet && detCh == 0) detCh = dch
                    if (isFloat) {
                        val fb = ob.asFloatBuffer()
                        val frames = fb.remaining() / chn
                        if (wantDet && nDet + frames * dch > det.size) det = det.copyOf(max(det.size * 2, nDet + frames * dch))
                        if (wantPcm && nPcm + frames * chn > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, nPcm + frames * chn))
                        for (f in 0 until frames) {
                            for (k in 0 until chn) {
                                val v = fb.get()
                                if (wantPcm) pcm[nPcm++] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                if (wantDet && k < dch) det[nDet++] = v
                            }
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        val frames = sb.remaining() / chn
                        if (wantDet && nDet + frames * dch > det.size) det = det.copyOf(max(det.size * 2, nDet + frames * dch))
                        if (wantPcm && nPcm + frames * chn > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, nPcm + frames * chn))
                        for (f in 0 until frames) {
                            for (k in 0 until chn) {
                                val v = sb.get()
                                if (wantPcm) pcm[nPcm++] = v
                                if (wantDet && k < dch) det[nDet++] = v / 32768f
                            }
                        }
                    }
                }
                c.releaseOutputBuffer(oi, false)
            }
            val frames = if (wantDet) nDet / max(1, detCh) else nPcm / max(1, ch)
            return DecodedAudio(
                sr, max(1, ch), startUs,
                if (wantDet) det.copyOf(nDet) else null, max(1, detCh),
                if (wantPcm) pcm.copyOf(nPcm) else null,
                frames
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    private fun fmt(f: String, vararg a: Any?) = String.format(Locale.US, f, *a)
    /** Число без лишних нулей: 4 → «4», 4.5 → «4.5». */
    fun num(v: Double): String =
        if (v == floor(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    private fun num2(v: Double) = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    private fun num3(v: Double) = String.format(Locale.US, "%.3f", v)
}
