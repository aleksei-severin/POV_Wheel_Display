package com.povwheel.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.povwheel.app.povvideo.PovAnalysis
import com.povwheel.app.povvideo.PovVideoController
import com.povwheel.app.povvideo.PovVideoController.State
import java.util.Locale

/**
 * Карточка «Render POV Video»: ролик с колесом из галереи → поиск лога Холла в архиве на
 * время записи и сводка → рендер, где каждая 1/6 оборота становится одним чистым кадром.
 */
@Composable
fun PovVideoCard(ctrl: PovVideoController) {
    val st by ctrl.state.collectAsState()
    val busy by ctrl.busy.collectAsState()
    val ctx = LocalContext.current

    // Галерея (ACTION_PICK по видео MediaStore) — по ней же узнаём папку исходника,
    // чтобы положить результат рядом. Нет галереи — системный выбор файла.
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.data?.let { ctrl.pick(it) }
    }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { ctrl.pick(it) } }
    // Галерея не выдала права на выбранный ролик — просим доступ к видео и повторяем.
    var retryUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val readPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val r = retryUri
        if (ok && r != null) ctrl.pick(r)
    }
    // До Android 10 запись в DCIM — только с разрешением.
    val writePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ctrl.render()
    }

    fun openGallery() {
        // Лог Холла — сразу, пока выбирают ролик: колесо может ещё крутиться, а
        // только что снятый ролик должен найти свежий хвост лога.
        ctrl.prefetchLog()
        try {
            gallery.launch(Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
        } catch (_: ActivityNotFoundException) {
            docs.launch("video/*")
        }
    }

    fun startRender() {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) writePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else ctrl.render()
    }

    // Лог нашёлся (хотя бы на часть ролика) — рендер начинается сам; что синхронизировано,
    // показывает полоса над сводкой. Отменить можно кнопкой Stop.
    LaunchedEffect(st) {
        val s = st
        if (s is State.Ready && s.a.renderable && ctrl.consumeAutoRender()) startRender()
    }

    var archiveOpen by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Вид — как у обычной Button, но с длинным тапом: он открывает архив лога
        // (выгрузить / загрузить). У Material-кнопки долгого нажатия нет.
        Surface(
            shape = ButtonDefaults.shape,
            color = if (!busy) cs.primary else cs.onSurface.copy(alpha = 0.12f),
            contentColor = if (!busy) cs.onPrimary else cs.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MinHeight)
                .clip(ButtonDefaults.shape)
                .tapCombinedClickable(onLongClick = { archiveOpen = true }) { if (!busy) openGallery() }
        ) {
            Row(
                Modifier.padding(ButtonDefaults.ContentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                VideoCamIcon()
                Spacer(Modifier.width(8.dp))
                Text("Render POV Video", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (archiveOpen) HallArchiveDialog(ctrl) { archiveOpen = false }

        when (val s = st) {
            is State.Idle -> {}

            is State.Analyzing -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(s.step, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = hapticClick { ctrl.cancel() }) { Text("Cancel") }
            }

            is State.Ready -> {
                Report(s.a)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.a.renderable) Button(onClick = hapticClick { startRender() }) { Text("Render") }
                    OutlinedButton(onClick = hapticClick { ctrl.reset() }) { Text("Close") }
                }
            }

            is State.Rendering -> {
                Report(s.a)
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = hapticClick { ctrl.cancel() }) { Text("Stop") }
            }

            is State.Done -> {
                // Сводка остаётся и после рендера: по ней видно, какой лог и какие
                // отрезки ролика пошли в склейку, — результат под ней.
                Report(s.a)
                Spacer(Modifier.height(2.dp))
                Text("Saved to the gallery:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(s.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                // Размеры — как видит зритель (с поворотом из метаданных); меньше исходника
                // результат бывает, только если кодировщик или память видеокарты не тянут.
                val rot90 = s.a.rotation == 90 || s.a.rotation == 270
                val ow = if (rot90) s.h else s.w; val oh = if (rot90) s.w else s.h
                val iw = if (rot90) s.a.codedH else s.a.codedW; val ih = if (rot90) s.a.codedW else s.a.codedH
                val reduced = s.w.toLong() * s.h < s.a.codedW.toLong() * s.a.codedH * 0.98
                Text(
                    ow.toString() + "×" + oh + " · " + String.format(Locale.US, "%.1f", s.a.durationSec / s.a.slow) +
                        " s · rendered in " + mmss(s.seconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (reduced) Text(
                    "Smaller than the original " + iw + "×" + ih + ": this phone's video encoder or GPU memory can't handle the full size.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val u = s.uri
                    if (u != null) Button(onClick = hapticClick {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) { }
                    }) { Text("Open") }
                    OutlinedButton(onClick = hapticClick { ctrl.reset() }) { Text("Close") }
                }
            }

            is State.Failed -> {
                s.a?.let { Report(it) }
                Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val r = s.retry
                    if (r != null) Button(onClick = hapticClick {
                        retryUri = r
                        readPerm.launch(
                            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                            else Manifest.permission.READ_EXTERNAL_STORAGE
                        )
                    }) { Text("Allow access") }
                    OutlinedButton(onClick = hapticClick { ctrl.reset() }) { Text("Close") }
                }
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
                        Text(o.message, style = MaterialTheme.typography.bodySmall, color = Ok)
                    is PovVideoController.ArchiveOp.Failed ->
                        Text(o.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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

/** Сводка о файле — те же строки, что скрипт выводит в консоль. */
@Composable
private fun Report(a: PovAnalysis) {
    Text(a.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    SyncBar(a)
    Text(
        a.report.joinToString("\n"),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Шкала ролика: где есть синхронизация по логу Холла (окрашено) и где кадры пойдут как
 * есть. Лог мог сохраниться не целиком — рендер всё равно идёт, просто склеены будут
 * только окрашенные отрезки.
 */
@Composable
private fun SyncBar(a: PovAnalysis) {
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

private fun mmss(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
