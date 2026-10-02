package com.povwheel.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Карточка «Render POV Video»: ролик с колесом из галереи → сводка о тиках → рендер,
 * где каждая 1/6 оборота становится одним чистым кадром (как tools/pov_fps_blend на ПК).
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = hapticClick { openGallery() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Render POV Video") }

        when (val s = st) {
            is State.Idle -> Hint(
                "Pick a video of the spinning wheel recorded with its 18 kHz tick tone. " +
                    "Every 1/6 turn becomes one clean frame; the result is saved to the gallery next to the original."
            )

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
                Text("Saved to the gallery:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(s.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    s.w.toString() + "×" + s.h + " · " + String.format(Locale.US, "%.1f", s.a.durationSec / s.a.slow) +
                        " s · rendered in " + mmss(s.seconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Сводка о файле — те же строки, что скрипт выводит в консоль. */
@Composable
private fun Report(a: PovAnalysis) {
    Text(a.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Text(
        a.report.joinToString("\n"),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun mmss(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
