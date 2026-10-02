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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private var job: Job? = null
    private val renderDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pov-render").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    fun pick(uri: Uri) {
        if (_busy.value) return
        cancelFlag = false
        _busy.value = true
        _state.value = State.Analyzing("video", "Reading video…")
        job = scope.launch(renderDispatcher) {
            try {
                val a = PovAnalyzer.analyze(app, uri,
                    step = { s -> (_state.value as? State.Analyzing)?.let { _state.value = it.copy(step = s) } },
                    cancelled = { cancelFlag || !isActive })
                _state.value = State.Ready(a)
            } catch (_: InterruptedException) {
                _state.value = State.Idle
            } catch (e: SecurityException) {
                _state.value = State.Failed("The gallery did not share this video with the app. Allow access to videos and try again.", null, uri)
            } catch (e: PovAnalyzer.NoAudio) {
                _state.value = State.Failed("This video has no sound track — the tick tone is what tells the sweeps apart.", null)
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
