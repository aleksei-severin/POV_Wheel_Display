package com.povwheel.app.povvideo

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.povwheel.app.hall.HallArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * «Render POV Video»: выбор ролика → сводка (как в консоли у скрипта) → рендер с
 * прогрессом и оценкой оставшегося времени → MP4 в галерее рядом с исходником.
 *
 * Живёт во [com.povwheel.app.WheelVm], поэтому переживает поворот экрана и уход с
 * карточки. Рендер идёт в собственном потоке: GL-контекст привязан к потоку.
 */
class PovVideoController(private val app: Application, private val scope: CoroutineScope) {

    sealed interface State {
        data object Idle : State
        data class Analyzing(val name: String, val step: String) : State
        data class Ready(val a: PovAnalysis) : State
        data class Rendering(
            val a: PovAnalysis,
            val done: Int,
            val total: Int,
            val elapsedSec: Int,
            /** null — пока не набралось данных для оценки. */
            val etaSec: Int?,
            val step: String
        ) : State
        data class Done(val a: PovAnalysis, val path: String, val uri: Uri?, val seconds: Int, val w: Int, val h: Int) : State
        /** [retry] — ролик, который не дали прочитать: после разрешения на видео пробуем снова. */
        data class Failed(val message: String, val a: PovAnalysis?, val retry: Uri? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val _busy = MutableStateFlow(false)
    /** Идёт анализ или рендер — экран держим включённым. */
    val busy: StateFlow<Boolean> = _busy

    @Volatile private var cancelFlag = false
    /** Рендер стартует сам, как только анализ нашёл лог (хотя бы на часть ролика). */
    @Volatile private var autoRender = false

    /** true — однократно: экран запускает рендер сам (через свой запрос разрешений). */
    fun consumeAutoRender(): Boolean { val v = autoRender; autoRender = false; return v }
    private var job: Job? = null
    private val renderDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pov-render").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * Внеочередная выгрузка лога Холла со всех колёс на связи (задаёт WheelVm).
     * Ролик обычно снимают прямо перед тем, как открыть приложение, и хвост
     * поездки фоновый опрос ещё не забрал — анализ тогда не нашёл бы лога на
     * последние секунды или на весь ролик целиком.
     */
    var logRefresher: (suspend () -> Unit)? = null
    private var logJob: Job? = null

    /**
     * Нажали «Render POV Video» — тянем лог сразу, пока человек выбирает ролик в
     * галерее: к моменту выбора он, как правило, уже в архиве.
     */
    fun prefetchLog() {
        if (logJob?.isActive == true) return
        val r = logRefresher ?: return
        logJob = scope.launch { runCatching { withTimeoutOrNull(15_000) { r() } } }
    }

    fun pick(uri: Uri) {
        if (_busy.value) return
        cancelFlag = false
        autoRender = true
        _busy.value = true
        _state.value = State.Analyzing("video", "Reading video…")
        if (logJob?.isActive != true) prefetchLog()
        val pending = logJob
        job = scope.launch(renderDispatcher) {
            try {
                if (pending?.isActive == true) {
                    _state.value = State.Analyzing("video", "Fetching the latest Hall log…")
                    pending.join()
                    _state.value = State.Analyzing("video", "Reading video…")
                }
                val a = PovAnalyzer.analyze(app, uri,
                    step = { s -> (_state.value as? State.Analyzing)?.let { _state.value = it.copy(step = s) } },
                    cancelled = { cancelFlag || !isActive })
                _state.value = State.Ready(a)
            } catch (_: InterruptedException) {
                _state.value = State.Idle
            } catch (e: SecurityException) {
                _state.value = State.Failed("The gallery did not share this video with the app. Allow access to videos and try again.", null, uri)
            } catch (e: PovAnalyzer.NoLog) {
                _state.value = State.Failed(e.message ?: "There is no Hall log for this video.", null)
            } catch (e: Throwable) {
                _state.value = State.Failed("Could not read this video: " + (e.message ?: e.javaClass.simpleName), null)
            } finally {
                _busy.value = false
            }
        }
    }

    fun render() {
        val a = (_state.value as? State.Ready)?.a ?: return
        if (_busy.value || !a.renderable) return
        cancelFlag = false
        _busy.value = true
        _state.value = State.Rendering(a, 0, a.plan.totalFrames, 0, null, "Preparing…")
        job = scope.launch(renderDispatcher) {
            val t0 = System.nanoTime()
            var target: Saver.Target? = null
            try {
                val tgt = Saver.create(app, a.outName, a.relativePath)
                target = tgt
                var lastUi = 0L
                var etaSmooth = -1.0
                val res = withContext(renderDispatcher) {
                    PovRenderer(app, a).render(
                        tgt.pfd.fileDescriptor,
                        cancelled = { cancelFlag || !isActive },
                        progress = { done, total ->
                            val now = System.nanoTime()
                            if (now - lastUi >= 250_000_000L || done >= total) {
                                lastUi = now
                                val el = (now - t0) / 1e9
                                // Оценка по средней скорости с начала — после первых 2 % и
                                // 3 с, сглаженная, чтобы цифра не прыгала от кадра к кадру.
                                var eta: Int? = null
                                if (done > 0 && done >= total / 50 && el > 3) {
                                    val raw = el / done * (total - done)
                                    etaSmooth = if (etaSmooth < 0) raw else 0.8 * etaSmooth + 0.2 * raw
                                    eta = etaSmooth.toInt()
                                }
                                _state.value = State.Rendering(a, done, total, el.toInt(), eta, "Rendering…")
                            }
                        }
                    )
                }
                val path = Saver.publish(app, tgt)
                target = null
                _state.value = State.Done(a, path.first, path.second, ((System.nanoTime() - t0) / 1e9).toInt(), res.width, res.height)
            } catch (_: InterruptedException) {
                _state.value = State.Ready(a)
            } catch (e: Throwable) {
                _state.value = State.Failed("Rendering failed: " + (e.message ?: e.javaClass.simpleName), a)
            } finally {
                target?.let { Saver.discard(app, it) }
                _busy.value = false
            }
        }
    }

    // ------------------------------------------------ архив лога: выгрузка и загрузка

    sealed interface ArchiveOp {
        data object Idle : ArchiveOp
        data class Working(val step: String) : ArchiveOp
        data class Done(val message: String) : ArchiveOp
        data class Failed(val message: String) : ArchiveOp
    }

    private val _archive = MutableStateFlow<ArchiveOp>(ArchiveOp.Idle)
    /** Ход выгрузки/загрузки архива лога Холла (окно по длинному тапу на кнопке). */
    val archive: StateFlow<ArchiveOp> = _archive
    private var archiveJob: Job? = null

    fun resetArchiveOp() { if (_archive.value !is ArchiveOp.Working) _archive.value = ArchiveOp.Idle }

    /** Что сейчас в архиве — для окна. */
    suspend fun archiveStats(): HallArchive.Stats =
        withContext(Dispatchers.IO) {
            HallArchive.init(app)
            HallArchive.stats()
        }

    /** Имя файла выгрузки по умолчанию: pov-hall-log-2026-10-06_1540.zip. */
    fun exportFileName(): String =
        "pov-hall-log-" + java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US)
            .format(java.util.Date()) + ".zip"

    /** Весь архив — в ZIP по [uri]. Сначала свежий хвост лога с колёс на связи. */
    fun exportArchive(uri: Uri) {
        if (archiveJob?.isActive == true) return
        archiveJob = scope.launch {
            try {
                logRefresher?.let { r ->
                    _archive.value = ArchiveOp.Working("Fetching the latest Hall log…")
                    runCatching { withTimeoutOrNull(15_000) { r() } }
                }
                _archive.value = ArchiveOp.Working("Exporting…")
                val res = withContext(Dispatchers.IO) {
                    HallArchive.init(app)
                    val out = app.contentResolver.openOutputStream(uri, "wt")
                        ?: throw java.io.IOException("cannot write the chosen file")
                    out.use { o ->
                        HallArchive.export(o) { s -> _archive.value = ArchiveOp.Working("Exporting " + s + "…") }
                    }
                }
                _archive.value = if (res.sessions == 0) ArchiveOp.Done("The archive is empty — nothing to export.")
                else ArchiveOp.Done("Exported " + plural(res.wheels, "display") + ", " + plural(res.sessions, "session") +
                    ", " + String.format(Locale.US, "%,d", res.events) + " events. README.txt inside has the summary.")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _archive.value = ArchiveOp.Failed("Export failed: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** ZIP по [uri] (формат выгрузки) — слить с архивом телефона, ничего не заменяя. */
    fun importArchive(uri: Uri) {
        if (archiveJob?.isActive == true) return
        archiveJob = scope.launch {
            try {
                _archive.value = ArchiveOp.Working("Importing…")
                val res = withContext(Dispatchers.IO) {
                    HallArchive.init(app)
                    val inp = app.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("cannot read the chosen file")
                    inp.use { i ->
                        HallArchive.import(i, File(app.cacheDir, "hall_import")) { s ->
                            _archive.value = ArchiveOp.Working("Importing " + s + "…")
                        }
                    }
                }
                _archive.value = ArchiveOp.Done(
                    if (res.events == 0L) "Nothing new — everything in this file is already in the archive (" +
                        plural(res.sessions, "session") + " checked)."
                    else "Merged " + plural(res.sessions, "session") + " from " + plural(res.wheels, "display") + ": " +
                        String.format(Locale.US, "%,d", res.events) + " new events."
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _archive.value = ArchiveOp.Failed("Import failed: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun plural(n: Int, word: String) = n.toString() + " " + word + (if (n == 1) "" else "s")

    fun cancel() { cancelFlag = true }

    fun reset() { if (!_busy.value) _state.value = State.Idle }

    /** Для onCleared ViewModel: остановить рендер, файл-заготовка удалится сам. */
    fun shutdown() { cancelFlag = true; job?.cancel() }

    /**
     * Запись результата в галерею. С Android 10 — через MediaStore в ту же папку, что
     * исходник (если это DCIM/Movies/Pictures — другие папки видео-коллекция не
     * принимает), пока файл пишется, он скрыт (IS_PENDING). До Android 10 — в DCIM/Camera
     * файлом и сканером медиа (нужно разрешение на запись).
     */
    internal object Saver {
        class Target(val uri: Uri?, val file: File?, val pfd: ParcelFileDescriptor, val shown: String)

        fun create(ctx: Context, name: String, srcRel: String?): Target {
            if (Build.VERSION.SDK_INT >= 29) {
                val allowed = listOf("DCIM/", "Movies/", "Pictures/")
                val rel = srcRel?.takeIf { r -> allowed.any { r.startsWith(it) } } ?: "Movies/POV Wheel/"
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = ctx.contentResolver.insert(coll, v) ?: throw IllegalStateException("cannot create a file in the gallery")
                val pfd = try {
                    ctx.contentResolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("cannot open the new gallery file")
                } catch (e: Exception) {
                    runCatching { ctx.contentResolver.delete(uri, null, null) }
                    throw e
                }
                return Target(uri, null, pfd, rel + name)
            }
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            dir.mkdirs()
            var f = File(dir, name)
            var i = 1
            while (f.exists()) { f = File(dir, name.substringBeforeLast('.') + " (" + i++ + ").mp4") }
            val pfd = ParcelFileDescriptor.open(f,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
            return Target(null, f, pfd, "DCIM/Camera/" + f.name)
        }

        /** Делает файл видимым в галерее; возвращает (путь для показа, uri для открытия). */
        fun publish(ctx: Context, t: Target): Pair<String, Uri?> {
            runCatching { t.pfd.close() }
            if (t.uri != null) {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(t.uri, v, null, null)
                // MediaStore мог переименовать файл, если такое имя уже было.
                var shown = t.shown
                runCatching {
                    ctx.contentResolver.query(t.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { shown = t.shown.substringBeforeLast('/') + "/" + it }
                    }
                }
                return Pair(shown, t.uri)
            }
            t.file?.let { MediaScannerConnection.scanFile(ctx, arrayOf(it.path), arrayOf("video/mp4"), null) }
            return Pair(t.shown, null)
        }

        fun discard(ctx: Context, t: Target) {
            runCatching { t.pfd.close() }
            t.uri?.let { u -> runCatching { ctx.contentResolver.delete(u, null, null) } }
            t.file?.let { runCatching { it.delete() } }
        }
    }
}
