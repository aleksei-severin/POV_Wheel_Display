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
    val toneHz: Double,
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
    /** Моно, −1..1 — для детектора. */
    val mono: FloatArray?,
    /** Чередующиеся каналы, 16 бит — для звука результата. */
    val pcm: ShortArray?,
    val frames: Int
)

internal object PovAnalyzer {

    // Параметры — те же, что по умолчанию у скрипта.
    const val BEEP_FREQ = 18000.0        // пьезо прошивки (PIEZO_FREQ_HZ)
    const val BEEP_FREQ_OLD = 17000.0    // прежняя прошивка
    private const val BEEP_MS = 5.0
    private const val SNR_DB = 8.0
    private const val TONAL_DB = 6.0
    private const val SIDE_HZ = 700.0
    private const val MIN_REV_MS = 80.0
    private const val MIN_RPM = 90.0
    const val MIN_FPS = 10.0
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
        val audio = decodeAudio(ctx, uri, wantMono = true, wantPcm = false, cancelled = cancelled)
            ?: throw NoAudio()
        if (cancelled()) throw InterruptedException()
        val mono = audio.mono!!
        val offsetSec = (audio.startUs - videoStartUs) / 1e6

        // Подсказка о замедлении — частота съёмки из метаданных против частоты файла.
        val hint = if (captureFps > 0 && fileFps > 0) (captureFps / fileFps).roundToInt() else 0
        val slowTry = ArrayList<Double>()
        for (k in intArrayOf(hint, 1, 4, 8, 2)) if (k >= 1 && !slowTry.contains(k.toDouble())) slowTry.add(k.toDouble())
        val freqTry = doubleArrayOf(BEEP_FREQ, BEEP_FREQ_OLD)

        // Перебор замедлений и частот тона: звук «разгоняется» объявлением частоты
        // дискретизации в k раз выше (тот же asetrate, что в скрипте), тики ищутся с
        // обычными параметрами. Первое сочетание, где нашлось ≥ 4 оборотов, — берём.
        val tried = ArrayList<String>()
        var best = -1
        var bestTracks: List<TickTrack> = emptyList()
        var slow = slowTry[0]
        var tone = freqTry[0]
        var candidates = 0
        outer@ for (k in slowTry) {
            val srEff = (audio.sampleRate * k).roundToInt()
            for (f in freqTry) {
                if (cancelled()) throw InterruptedException()
                if (srEff / 2.0 < f + SIDE_HZ + 100) {
                    tried.add(fmt("  %s Hz, slow ×%s: audio too narrow (%d Hz) — skipped", num(f), num(k), audio.sampleRate))
                    continue
                }
                step("Looking for " + num(f / 1000) + " kHz ticks" + (if (k > 1) " (slow motion ×" + num(k) + ")" else "") + "…")
                val det = PovTicks()
                val cand = det.detect(mono, srEff, f, SIDE_HZ, BEEP_MS, SNR_DB, TONAL_DB, minPeriod, maxPeriod, cancelled)
                if (cancelled()) throw InterruptedException()
                val tr = det.grid(cand, maxPeriod, 0.25, 0.5, 5, 0.5, 2 * BEEPS_PER_REV)
                // Времена — по шкале файла: растягиваем в k раз и сдвигаем на начало звука.
                val fileTracks = tr.map { t -> TickTrack(DoubleArray(t.times.size) { t.times[it] * k + offsetSec }, t.real) }
                val iv = fileTracks.sumOf { it.times.size - 1 }
                tried.add(fmt("  %s Hz, slow ×%s (in file %s Hz, tick %s ms): %d intervals", num(f), num(k), num(f / k), num(BEEP_MS * k), iv))
                if (iv > best) { best = iv; bestTracks = fileTracks; slow = k; tone = f; candidates = cand.size / 3 }
                if (iv >= 4 * BEEPS_PER_REV) break@outer
            }
        }

        step("Planning…")
        // Треки — в пределах ролика.
        val coverEnd = durationSec
        val tracks = bestTracks.mapNotNull { t ->
            val idx = t.times.indices.filter { t.times[it] >= 0 && t.times[it] <= coverEnd }
            if (idx.size < 2) null else TickTrack(DoubleArray(idx.size) { t.times[idx[it]] }, BooleanArray(idx.size) { t.real[idx[it]] })
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
        if (best > 0 && tone != freqTry[0]) rep.add("Ticks found at " + num(tone) + " Hz — recorded with the older firmware.")
        rep.add(fmt("Tick candidates: %d; rendering segments: %d", candidates, tracks.size))
        tracks.forEachIndexed { i, t ->
            val ts = t.times
            var rMin = Double.MAX_VALUE; var rMax = 0.0
            for (k in 0 until ts.size - 1) {
                val rpm = 60.0 / ((ts[k + 1] - ts[k]) / slow * BEEPS_PER_REV)
                rMin = min(rMin, rpm); rMax = max(rMax, rpm)
            }
            val filled = t.real.count { !it }
            rep.add(fmt("  segment %d: %s–%s s, %d tick intervals (%d filled in), %d..%d rpm",
                i + 1, num3(ts.first()), num3(ts.last()), ts.size - 1, filled, rMin.roundToInt(), rMax.roundToInt()))
        }
        val natives = segs.filter { it.native && it.t1 - it.t0 > 0 }
        if (natives.isNotEmpty())
            rep.add("  no ticks, frames kept as recorded: " + natives.joinToString(", ") { num3(it.t0) + "–" + num3(it.t1) + " s" })
        rep.add(fmt("Blended frames (sweeps): %d", sweeps) +
            if (fpsSplit > 0) fmt(" — %d intervals longer than 1/%s s were split to keep ≥ %s fps", fpsSplit, num(MIN_FPS), num(MIN_FPS)) else "")
        if (tracks.isEmpty()) {
            rep.add("No rendering found — no tick tone on the audio (the piezo only ticks while the strip is lit).")
        } else {
            rep.add(fmt("Result: %s s at %s fps%s", num2(durationSec / slow), num2(fileFps * slow),
                if (slow > 1) " (real time)" else ""))
        }

        return PovAnalysis(
            uri, name, relPath, codedW, codedH, rotation, fileFps, durationSec, videoBps, vi.mime, slow, tone,
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

    /** Декодирует звуковую дорожку целиком (моно для детектора и/или 16-битный PCM). */
    fun decodeAudio(
        ctx: Context, uri: Uri, wantMono: Boolean, wantPcm: Boolean,
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
            var mono = FloatArray(if (wantMono) 1 shl 20 else 0)
            var pcm = ShortArray(if (wantPcm) 1 shl 20 else 0)
            var nMono = 0
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
                    if (isFloat) {
                        val fb = ob.asFloatBuffer()
                        val frames = fb.remaining() / chn
                        if (wantMono && nMono + frames > mono.size) mono = mono.copyOf(max(mono.size * 2, nMono + frames))
                        if (wantPcm && nPcm + frames * chn > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, nPcm + frames * chn))
                        for (f in 0 until frames) {
                            var s = 0f
                            for (k in 0 until chn) {
                                val v = fb.get()
                                s += v
                                if (wantPcm) pcm[nPcm++] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            }
                            if (wantMono) mono[nMono++] = s / chn
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        val frames = sb.remaining() / chn
                        if (wantMono && nMono + frames > mono.size) mono = mono.copyOf(max(mono.size * 2, nMono + frames))
                        if (wantPcm && nPcm + frames * chn > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, nPcm + frames * chn))
                        for (f in 0 until frames) {
                            var s = 0f
                            for (k in 0 until chn) {
                                val v = sb.get()
                                s += v
                                if (wantPcm) pcm[nPcm++] = v
                            }
                            if (wantMono) mono[nMono++] = s / chn / 32768f
                        }
                    }
                }
                c.releaseOutputBuffer(oi, false)
            }
            val frames = if (wantMono) nMono else nPcm / max(1, ch)
            return DecodedAudio(
                sr, max(1, ch), startUs,
                if (wantMono) mono.copyOf(nMono) else null,
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
