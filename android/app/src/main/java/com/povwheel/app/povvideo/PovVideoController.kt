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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * «Render POV Video»: выбор роликов (одного или нескольких) → по очереди для каждого
 * сводка (как в консоли у скрипта) → рендер с прогрессом и оценкой оставшегося
 * времени → MP4 в галерее рядом с исходником.
 *
 * Живёт во [com.povwheel.app.WheelVm], поэтому переживает поворот экрана и уход с
 * карточки. Очередь разбирает один собственный поток: GL-контекст привязан к потоку,
 * да и два рендера разом телефон всё равно не потянет.
 */
class PovVideoController(private val app: Application, private val scope: CoroutineScope) {

    /**
     * Что экран показывает о ролике после анализа. Сам анализ (метки кадров, тики, план)
     * держим только пока ролик в работе: в очереди их может быть много, а занимают они
     * мегабайты.
     */
    class Summary(
        val displayName: String,
        val durationSec: Double,
        /** Длина в реальном времени (у slow motion короче файла) — столько идёт результат. */
        val realDurationSec: Double,
        val rotation: Int,
        val codedW: Int,
        val codedH: Int,
        val syncRanges: List<DoubleArray>,
        val report: List<String>
    ) {
        constructor(a: PovAnalysis) : this(a.displayName, a.durationSec, a.realDurationSec, a.rotation,
            a.codedW, a.codedH, a.syncRanges, a.report)
    }

    sealed interface Status {
        data object Queued : Status
        data class Analyzing(val step: String) : Status
        data class Rendering(
            val done: Int,
            val total: Int,
            val elapsedSec: Int,
            /** null — пока не набралось данных для оценки. */
            val etaSec: Int?,
            val step: String
        ) : Status
        data class Done(val path: String, val uri: Uri?, val seconds: Int, val w: Int, val h: Int) : Status
        /** [retry] — ролик не дали прочитать: после разрешения на видео можно поставить его снова. */
        data class Failed(val message: String, val retry: Boolean = false) : Status
        /** Остановлен кнопкой (Skip / Stop) — можно поставить в очередь заново. */
        data object Stopped : Status
    }

    /** Ролик в очереди. [name] — null, пока не узнали имя файла. */
    data class Entry(val id: Long, val uri: Uri, val name: String?, val summary: Summary?, val status: Status) {
        val finished: Boolean get() = status is Status.Done || status is Status.Failed || status is Status.Stopped
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** Все ролики по порядку: готовые со своими сводками, текущий, ждущие очереди. */
    val entries: StateFlow<List<Entry>> = _entries
    private val _busy = MutableStateFlow(false)
    /** Очередь в работе (анализ или рендер) — экран держим включённым. */
    val busy: StateFlow<Boolean> = _busy

    /** Очередь и смена текущего ролика — под ним: добавляют с главного потока, берёт рабочий. */
    private val lock = Any()
    private var nextId = 1L
    /** Ролик, который сейчас в работе (-1 — никакого). */
    @Volatile private var currentId = -1L
    /**
     * Ролик, которому велели остановиться. По id, а не флагом: Skip, нажатый в момент
     * смены ролика, не должен зацепить следующий.
     */
    @Volatile private var cancelId = -1L
    private var worker: Job? = null
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

    private fun edit(id: Long, f: (Entry) -> Entry) = _entries.update { l -> l.map { if (it.id == id) f(it) else it } }
    private fun setStatus(id: Long, s: Status) = edit(id) { it.copy(status = s) }

    /**
     * Ролики из галереи — в конец очереди. Можно и посреди работы: очередь просто
     * станет длиннее. Каждый анализируется и, если лог нашёлся хотя бы на часть
     * ролика, сразу рендерится; сводки готовых остаются на экране друг за другом.
     */
    fun pick(uris: List<Uri>) {
        val add = synchronized(lock) {
            // Тот же ролик, ещё ждущий или в работе, второй раз не ставим.
            val waiting = _entries.value.filter { !it.finished }.map { it.uri }.toSet()
            val add = uris.distinct().filter { it !in waiting }.map { Entry(nextId++, it, null, null, Status.Queued) }
            if (add.isEmpty()) return
            _entries.update { it + add }
            add
        }
        // Лог — раньше рабочего: первый анализ дождётся свежего хвоста.
        if (logJob?.isActive != true) prefetchLog()
        synchronized(lock) { startWorkerLocked() }
        // Имена — сразу, чтобы ждущие в очереди не висели безымянными.
        scope.launch(Dispatchers.IO) {
            for (e in add) {
                val n = runCatching { PovAnalyzer.sourceInfo(app, e.uri).first }.getOrNull() ?: continue
                edit(e.id) { if (it.name == null) it.copy(name = n) else it }
            }
        }
    }

    /**
     * Остановленный или упавший ролик — снова в очередь (в конец, чтобы сводки шли по
     * порядку). С новым id: старый мог остаться в [cancelId] от Skip.
     */
    fun requeue(id: Long) {
        if (logJob?.isActive != true) prefetchLog()
        synchronized(lock) {
            val e = _entries.value.firstOrNull { it.id == id } ?: return
            if (e.status !is Status.Failed && e.status !is Status.Stopped) return
            _entries.update { l -> l.filter { it.id != id } + e.copy(id = nextId++, summary = null, status = Status.Queued) }
            startWorkerLocked()
        }
    }

    /** Убрать ждущий ролик из очереди. */
    fun remove(id: Long) {
        synchronized(lock) { _entries.update { l -> l.filter { !(it.id == id && it.status == Status.Queued) } } }
    }

    /** Пропустить ролик, который сейчас в работе, — очередь пойдёт дальше. */
    fun skip(id: Long) { if (currentId == id) cancelId = id }

    /** Остановить текущий ролик и снять с очереди все ждущие. */
    fun stopAll() {
        synchronized(lock) {
            _entries.update { l -> l.map { if (it.status == Status.Queued) it.copy(status = Status.Stopped) else it } }
            cancelId = currentId
        }
    }

    /** Убрать сводки готовых роликов (ждущие и текущий остаются). */
    fun clear() {
        synchronized(lock) { _entries.update { l -> l.filter { !it.finished } } }
    }

    private fun startWorkerLocked() {
        if (_busy.value) return
        _busy.value = true
        worker = scope.launch(renderDispatcher) {
            try {
                while (true) {
                    val e = synchronized(lock) {
                        val n = _entries.value.firstOrNull { it.status == Status.Queued }
                        if (n == null) {
                            currentId = -1
                            _busy.value = false
                        } else {
                            currentId = n.id
                            setStatus(n.id, Status.Analyzing("Reading video…"))
                        }
                        n
                    } ?: break
                    process(e)
                }
            } catch (c: CancellationException) {
                synchronized(lock) { currentId = -1; _busy.value = false }
                throw c
            }
        }
    }

    /** Один ролик: анализ, затем (если лог нашёлся) рендер. Исход — в статус ролика. */
    private suspend fun process(e: Entry) {
        val id = e.id
        val job = currentCoroutineContext()[Job]
        val cancelled = { cancelId == id || job?.isActive == false }

        val a: PovAnalysis
        try {
            logJob?.let { j ->
                if (j.isActive) {
                    setStatus(id, Status.Analyzing("Fetching the latest Hall log…"))
                    j.join()
                    setStatus(id, Status.Analyzing("Reading video…"))
                }
            }
            a = PovAnalyzer.analyze(app, e.uri,
                step = { s -> edit(id) { if (it.status is Status.Analyzing) it.copy(status = Status.Analyzing(s)) else it } },
                cancelled = cancelled)
        } catch (c: CancellationException) {
            throw c
        } catch (_: InterruptedException) {
            setStatus(id, Status.Stopped); return
        } catch (_: SecurityException) {
            setStatus(id, Status.Failed("The gallery did not share this video with the app. Allow access to videos and try again.", retry = true)); return
        } catch (x: PovAnalyzer.NoLog) {
            setStatus(id, Status.Failed(x.message ?: "There is no Hall log for this video.")); return
        } catch (x: Throwable) {
            setStatus(id, Status.Failed("Could not read this video: " + (x.message ?: x.javaClass.simpleName))); return
        }
        edit(id) { it.copy(name = a.displayName, summary = Summary(a)) }
        if (!a.renderable) { setStatus(id, Status.Failed("Nothing to render: the Hall log does not cover this video.")); return }
        if (cancelled()) { setStatus(id, Status.Stopped); return }

        setStatus(id, Status.Rendering(0, a.plan.totalFrames, 0, null, "Preparing…"))
        val t0 = System.nanoTime()
        var target: Saver.Target? = null
        try {
            val tgt = Saver.create(app, a.outName, a.relativePath)
            target = tgt
            var lastUi = 0L
            var etaSmooth = -1.0
            // Рендер — прямо в потоке очереди: GL-контекст привязан к потоку.
            val res = PovRenderer(app, a).render(
                tgt.pfd.fileDescriptor,
                cancelled = cancelled,
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
                        setStatus(id, Status.Rendering(done, total, el.toInt(), eta, "Rendering…"))
                    }
                }
            )
            val path = Saver.publish(app, tgt)
            target = null
            setStatus(id, Status.Done(path.first, path.second, ((System.nanoTime() - t0) / 1e9).toInt(), res.width, res.height))
        } catch (c: CancellationException) {
            throw c
        } catch (_: InterruptedException) {
            setStatus(id, Status.Stopped)
        } catch (x: Throwable) {
            setStatus(id, Status.Failed("Rendering failed: " + (x.message ?: x.javaClass.simpleName)))
        } finally {
            target?.let { Saver.discard(app, it) }
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

    /** Для onCleared ViewModel: остановить рендер, файл-заготовка удалится сам. */
    fun shutdown() { stopAll(); worker?.cancel() }

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
