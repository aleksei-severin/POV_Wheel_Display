package com.povwheel.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.povwheel.app.povvideo.PovVideoController
import com.povwheel.app.povvideo.PovVideoController.Status
import com.povwheel.app.povvideo.PovVideoController.Summary
import java.util.Locale

/**
 * Карточка «Render POV Video»: ролики с колесом из галереи (можно несколько разом) →
 * по очереди для каждого поиск лога Холла в архиве на время записи и сводка → рендер,
 * где каждая 1/6 оборота становится одним чистым кадром. Сводки готовых роликов
 * остаются друг под другом, пока их не закроют.
 */
@Composable
fun PovVideoCard(ctrl: PovVideoController) {
    val entries by ctrl.entries.collectAsState()
    val busy by ctrl.busy.collectAsState()
    val ctx = LocalContext.current

    // До Android 10 запись в DCIM — только с разрешением; спрашиваем до того, как
    // ставить ролики в очередь, а не посреди неё.
    var afterWritePerm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val writePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val f = afterWritePerm
        afterWritePerm = null
        if (ok) f?.invoke()
    }
    fun withWritePerm(f: () -> Unit) {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            afterWritePerm = f
            writePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else f()
    }
    fun enqueue(uris: List<Uri>) { if (uris.isNotEmpty()) withWritePerm { ctrl.pick(uris) } }

    // Галерея (ACTION_PICK по видео MediaStore) — по ней же узнаём папку исходника,
    // чтобы положить результат рядом. Несколько роликов галерея возвращает в ClipData,
    // один — в data; галерея, не умеющая множественный выбор, просто вернёт один.
    // Нет галереи — системный выбор файлов.
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data ?: return@rememberLauncherForActivityResult
        val uris = ArrayList<Uri>()
        d.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { uris.add(it) } }
        if (uris.isEmpty()) d.data?.let { uris.add(it) }
        enqueue(uris)
    }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { l -> enqueue(l) }
    // Галерея не выдала права на выбранный ролик — просим доступ к видео и ставим его снова.
    var retryId by remember { mutableStateOf<Long?>(null) }
    val readPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val id = retryId
        retryId = null
        if (ok && id != null) ctrl.requeue(id)
    }

    fun openGallery() {
        // Лог Холла — сразу, пока выбирают ролики: колесо может ещё крутиться, а
        // только что снятый ролик должен найти свежий хвост лога.
        ctrl.prefetchLog()
        try {
            gallery.launch(
                Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            )
        } catch (_: ActivityNotFoundException) {
            docs.launch("video/*")
        }
    }

    var archiveOpen by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Вид — как у обычной Button, но с длинным тапом: он открывает архив лога
        // (выгрузить / загрузить). У Material-кнопки долгого нажатия нет. Пока очередь
        // в работе, кнопка тоже нажимается — выбранное встаёт в конец очереди.
        Surface(
            shape = ButtonDefaults.shape,
            color = cs.primary,
            contentColor = cs.onPrimary,
            modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MinHeight)
                .clip(ButtonDefaults.shape)
                .tapCombinedClickable(onLongClick = { archiveOpen = true }) { openGallery() }
        ) {
            Row(
                Modifier.padding(ButtonDefaults.ContentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                VideoCamIcon()
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Add videos to the queue" else "Render POV Video", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (archiveOpen) HallArchiveDialog(ctrl) { archiveOpen = false }

        if (entries.size > 1) {
            val done = entries.count { it.status is Status.Done }
            val failed = entries.count { it.status is Status.Failed }
            val waiting = entries.count { it.status == Status.Queued }
            Text(
                listOfNotNull(
                    plural(entries.size, "video"),
                    if (done > 0) done.toString() + " rendered" else null,
                    if (failed > 0) failed.toString() + " failed" else null,
                    if (waiting > 0) waiting.toString() + " waiting" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }

        val waiting = entries.count { it.status == Status.Queued }
        entries.forEachIndexed { i, e ->
            key(e.id) {
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                EntryView(
                    e,
                    index = if (entries.size > 1) i + 1 else 0,
                    waiting = waiting,
                    ctrl = ctrl,
                    onAllowAccess = {
                        retryId = e.id
                        readPerm.launch(
                            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                            else Manifest.permission.READ_EXTERNAL_STORAGE
                        )
                    },
                    onRenderAgain = { withWritePerm { ctrl.requeue(e.id) } }
                )
            }
        }

        // Закрыть — только готовые: ждущие и текущий ролик остаются.
        if (entries.any { it.finished }) {
            OutlinedButton(onClick = hapticClick { ctrl.clear() }) {
                Text(if (busy || entries.any { !it.finished }) "Close finished" else "Close")
            }
        }
    }
}

/** Один ролик очереди: имя, затем — по состоянию — ожидание, ход работы или сводка с итогом. */
@Composable
private fun EntryView(
    e: PovVideoController.Entry,
    /** Номер в очереди с 1; 0 — ролик один, номер не нужен. */
    index: Int,
    /** Сколько роликов ждёт после текущего — от этого зависят кнопки Skip / Stop all. */
    waiting: Int,
    ctrl: PovVideoController,
    onAllowAccess: () -> Unit,
    onRenderAgain: () -> Unit
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val name = e.summary?.displayName ?: e.name ?: "video"
        Text(
            (if (index > 0) "$index. " else "") + name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (e.status == Status.Queued) cs.onSurfaceVariant else LocalContentColor.current
        )
        e.summary?.let { Report(it) }

        // Кнопки текущего ролика: при ждущих — пропустить только его или остановить всё.
        @Composable
        fun stopButtons() {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (waiting > 0) {
                    OutlinedButton(onClick = hapticClick { ctrl.skip(e.id) }) { Text("Skip") }
                    OutlinedButton(onClick = hapticClick { ctrl.stopAll() }) { Text("Stop all") }
                } else OutlinedButton(onClick = hapticClick { ctrl.stopAll() }) { Text("Stop") }
            }
        }

        when (val s = e.status) {
            Status.Queued -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Waiting in the queue", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = hapticClick { ctrl.remove(e.id) }) { Text("Remove") }
            }

            is Status.Analyzing -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(s.step, style = MaterialTheme.typography.bodyMedium)
                }
                stopButtons()
            }

            is Status.Rendering -> {
                Spacer(Modifier.height(2.dp))
                if (s.done == 0) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(s.step, style = MaterialTheme.typography.bodySmall)
                } else {
                    val p = s.done.toFloat() / s.total.coerceAtLeast(1)
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text(
                        (p * 100).toInt().toString() + "% · " + s.done + " / " + s.total + " frames · " +
                            (s.etaSec?.let { "about " + mmss(it) + " left" } ?: "estimating time…"),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("Elapsed " + mmss(s.elapsedSec) + ". Keep the app open — the screen stays on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant)
                }
                stopButtons()
            }

            is Status.Done -> {
                // Сводка остаётся и после рендера: по ней видно, какой лог и какие
                // отрезки ролика пошли в склейку, — результат под ней.
                Spacer(Modifier.height(2.dp))
                Text("Saved to the gallery:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                SelectionContainer { Text(s.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                e.summary?.let { a ->
                    // Размеры — как видит зритель (с поворотом из метаданных); меньше исходника
                    // результат бывает, только если кодировщик или память видеокарты не тянут.
                    val rot90 = a.rotation == 90 || a.rotation == 270
                    val ow = if (rot90) s.h else s.w; val oh = if (rot90) s.w else s.h
                    val iw = if (rot90) a.codedH else a.codedW; val ih = if (rot90) a.codedW else a.codedH
                    val reduced = s.w.toLong() * s.h < a.codedW.toLong() * a.codedH * 0.98
                    Text(
                        ow.toString() + "×" + oh + " · " + String.format(Locale.US, "%.1f", a.realDurationSec) +
                            " s · rendered in " + mmss(s.seconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    if (reduced) Text(
                        "Smaller than the original " + iw + "×" + ih + ": this phone's video encoder or GPU memory can't handle the full size.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error
                    )
                }
                val u = s.uri
                if (u != null) Button(onClick = hapticClick {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) { }
                }) { Text("Open") }
            }

            is Status.Failed -> {
                // Ошибку выделяют долгим нажатием и копируют — чтобы прислать как есть.
                SelectionContainer { Text(s.message, style = MaterialTheme.typography.bodySmall, color = cs.error) }
                // Без доступа — сначала разрешение. Иначе просто ещё раз: лог мог
                // дойти с колеса уже после анализа.
                if (s.retry) Button(onClick = hapticClick(onAllowAccess)) { Text("Allow access") }
                else OutlinedButton(onClick = hapticClick(onRenderAgain)) { Text("Try again") }
            }

            Status.Stopped -> {
                Text("Stopped.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                OutlinedButton(onClick = hapticClick(onRenderAgain)) { Text("Render again") }
            }
        }
    }
}

/**
 * Архив лога Холла (длинный тап по «Render POV Video»): выгрузить ZIP — README.txt
 * со сводкой по дисплеям (сессии, когда горело изображение) и CSV событий — или
 * загрузить такой ZIP обратно. Загрузка сливает его с архивом телефона, ничего не
 * заменяя.
 */
@Composable
private fun HallArchiveDialog(ctrl: PovVideoController, onDismiss: () -> Unit) {
    val op by ctrl.archive.collectAsState()
    val working = op is PovVideoController.ArchiveOp.Working
    // Сводка архива — заново после каждой выгрузки/загрузки.
    val stats by produceState<com.povwheel.app.hall.HallArchive.Stats?>(null, working) {
        value = ctrl.archiveStats()
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u ->
        u?.let { ctrl.exportArchive(it) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { ctrl.importArchive(it) }
    }
    DisposableEffect(Unit) { onDispose { ctrl.resetArchiveOp() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hall log archive") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = stats
                Text(
                    when {
                        s == null -> "Reading the archive…"
                        s.sessions == 0 -> "The archive is empty."
                        else -> s.wheels.toString() + (if (s.wheels == 1) " display · " else " displays · ") +
                            s.sessions + (if (s.sessions == 1) " session · " else " sessions · ") +
                            String.format(Locale.US, "%.1f MB", s.bytes / 1048576.0) +
                            (if (s.firstUs != null && s.lastUs != null) "\n" + dateSpan(s.firstUs, s.lastUs) else "")
                    },
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Export saves a ZIP: README.txt with a summary per display — every session and the " +
                        "times its image was on — plus one CSV per session with every sensor event.\n" +
                        "Import merges such a ZIP into this phone's archive: new events are added, " +
                        "nothing already here is replaced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (val o = op) {
                    is PovVideoController.ArchiveOp.Working -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(o.step, style = MaterialTheme.typography.bodySmall)
                    }
                    is PovVideoController.ArchiveOp.Done ->
                        SelectionContainer { Text(o.message, style = MaterialTheme.typography.bodySmall, color = Ok) }
                    is PovVideoController.ArchiveOp.Failed ->
                        SelectionContainer { Text(o.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    PovVideoController.ArchiveOp.Idle -> {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = hapticClick { export.launch(ctrl.exportFileName()) },
                        enabled = !working && (stats?.sessions ?: 0) > 0
                    ) { Text("Export…") }
                    OutlinedButton(
                        onClick = hapticClick { import.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        enabled = !working
                    ) { Text("Import…") }
                }
            }
        },
        confirmButton = { TextButton(onClick = hapticClick(onDismiss)) { Text("Close") } }
    )
}

/** «2026-09-20 … 2026-10-06» по времени первой и последней записи архива. */
private fun dateSpan(firstUs: Double, lastUs: Double): String {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val a = f.format(java.util.Date((firstUs / 1000).toLong()))
    val b = f.format(java.util.Date((lastUs / 1000).toLong()))
    return if (a == b) a else "$a … $b"
}

/**
 * Сводка о файле — те же строки, что скрипт выводит в консоль (имя файла — над ней).
 * Выделяется долгим нажатием и копируется, как обычный текст: без SelectionContainer
 * Text в Compose не выделяется вообще. Он раскладывает детей друг на друга, как Box,
 * поэтому шкала и строки — в своём Column с тем же шагом, что у карточки.
 */
@Composable
private fun Report(a: Summary) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SyncBar(a)
            Text(
                a.report.joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Шкала ролика: где есть синхронизация по логу Холла (окрашено) и где кадры пойдут как
 * есть. Лог мог сохраниться не целиком — рендер всё равно идёт, просто склеены будут
 * только окрашенные отрезки.
 */
@Composable
private fun SyncBar(a: Summary) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val dur = a.durationSec.coerceAtLeast(1e-3)
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        for (iv in a.syncRanges) {
            val x0 = (iv[0] / dur).toFloat().coerceIn(0f, 1f) * size.width
            val x1 = (iv[1] / dur).toFloat().coerceIn(0f, 1f) * size.width
            if (x1 > x0) drawRoundRect(fill, topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = r)
        }
    }
    val covered = a.syncRanges.sumOf { it[1] - it[0] }
    Text(
        if (a.syncRanges.isEmpty()) "No synced range in this video"
        else String.format(Locale.US, "Synced by the Hall log: %.1f of %.1f s", covered, a.durationSec),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Значок видеокамеры цветом содержимого кнопки. Рисуется сам: библиотеки значков
 * в приложении нет нарочно (см. build.gradle.kts), а символа камеры, который
 * одинаково выглядел бы во всех шрифтах, тоже нет.
 */
@Composable
private fun VideoCamIcon() {
    val c = LocalContentColor.current
    Canvas(Modifier.size(width = 20.dp, height = 14.dp)) {
        val u = size.height / 14f
        // Корпус.
        drawRoundRect(c, topLeft = Offset(0f, 1.5f * u), size = Size(13f * u, 11f * u),
            cornerRadius = CornerRadius(2.5f * u, 2.5f * u))
        // Объектив — трапеция справа.
        val lens = Path().apply {
            moveTo(14f * u, 5.5f * u)
            lineTo(20f * u, 2f * u)
            lineTo(20f * u, 12f * u)
            lineTo(14f * u, 8.5f * u)
            close()
        }
        drawPath(lens, c)
    }
}

private fun plural(n: Int, word: String) = n.toString() + " " + word + (if (n == 1) "" else "s")

private fun mmss(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
