package com.povwheel.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.povwheel.app.WheelVm
import com.povwheel.app.convert.Fit
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Потолок выбора за раз. Фотопикер требует не меньше двух, а больше
// нескольких десятков анимаций всё равно не поместится в PSRAM колеса.
private const val MAX_PICK = 30

/**
 * Выбор файла, конвертация и заливка.
 *
 * Всё дорогое считается на телефоне: декодирование, полярная передискретизация
 * и квантование median cut. На колесо уходит готовый файл ANI6, сжатый DEFLATE, —
 * распаковывает его подпрограмма из ПЗУ устройства, так что сжатие не стоит
 * прошивке ни байта флеша и примерно втрое поднимает реальную скорость.
 */
@Composable
fun UploadPanel(vm: WheelVm) {
    val fs by vm.fsInfo.collectAsState()

    // Всё состояние панели живёт во ViewModel. В самой панели его держать
    // нельзя: уход на другую вкладку выкидывает её из композиции, и вместе с
    // ней умирали и выбор файлов, и прогресс, и — главное — сама корутина
    // заливки. См. комментарий у upUris в WheelVm.
    val uris by vm.upUris.collectAsState()
    val poster by vm.upPoster.collectAsState()
    val status by vm.upStatus.collectAsState()
    val statusKind by vm.upKind.collectAsState()
    val progress by vm.upProgress.collectAsState()
    val busy by vm.upBusy.collectAsState()
    val fitMode by vm.upFit.collectAsState()
    val fps by vm.upFps.collectAsState()
    val lengthSec by vm.upLength.collectAsState()
    val mirror by vm.upBackMirror.collectAsState()
    val isVideo by vm.upIsVideo.collectAsState()
    val srcDuration by vm.upSrcDur.collectAsState()


    // Разбор выбранного — общий для галереи и файлового менеджера.
    val onPicked: (List<Uri>) -> Unit = { picked -> vm.onFilesPicked(picked) }

    /**
     * Системный выбор медиа — та самая галерея, а не файловый менеджер.
     *
     * Через `OpenMultipleDocuments` открывался SAF, и там видео сплошь и рядом
     * не показывались, хотя лежат в тех же папках: два запрошенных типа
     * (image и video) уезжают провайдеру списком в EXTRA_MIME_TYPES, а сам тип
     * запроса становится «любой», и часть реализаций DocumentsUI — особенно в
     * прошивках вендоров — этот список игнорирует, показывая только первый тип.
     *
     * У фотопикера этой проблемы нет по построению: он спрашивает не «файлы
     * такого-то MIME», а медиатеку целиком, и `ImageAndVideo` в ней — родная
     * категория. Ему же не нужно никаких разрешений: доступ выдаётся ровно на
     * выбранные элементы. На устройствах без фотопикера androidx сам
     * откатывается на `ACTION_OPEN_DOCUMENT`, так что minSdk 26 не страдает.
     */
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)
    ) { picked -> onPicked(picked) }

    // Запасной путь: файл вне медиатеки галерее не виден — скачанная в
    // «Загрузки» гифка, анимированный WebP, ролик из мессенджера.
    val browser = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { picked -> onPicked(picked ?: emptyList()) }


    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Add animation", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            // Аналог зоны перетаскивания из веба
            Box(
                Modifier.fillMaxWidth().height(132.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable(enabled = !busy) {
                        gallery.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            )
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (poster != null) {
                    Image(poster!!.asImageBitmap(), null,
                        Modifier.size(104.dp).clip(RoundedCornerShape(52.dp)))
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⬆", fontSize = 26.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Choose a video, GIF or image",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("tap to open your gallery",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Галерея показывает только то, что попало в медиатеку. Файл из
            // «Загрузок» или из папки мессенджера туда может не попасть, и без
            // этой кнопки до него было бы никак не добраться.
            TextButton(
                onClick = { browser.launch(arrayOf("image/*", "video/*")) },
                enabled = !busy
            ) { Text("Browse files instead") }

            if (uris.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Framing", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 8.dp))
                    FilterChip(fitMode == Fit.CROP, { vm.setFit(Fit.CROP) }, { Text("Crop") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(fitMode == Fit.FIT, { vm.setFit(Fit.FIT) }, { Text("Fit") })
                }

                // Зеркалить заднюю сторону колеса. Выключено по умолчанию: спереди
                // и сзади горят те же пиксели (сзади картинка читается зеркально).
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { vm.setBackMirror(!mirror) }
                ) {
                    Checkbox(checked = mirror, onCheckedChange = { vm.setBackMirror(it) }, enabled = !busy)
                    Text("Mirror back face — readable from both sides",
                        style = MaterialTheme.typography.bodySmall)
                }

                if (isVideo) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("FPS", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp))
                        listOf(5, 10, 15).forEach { f ->
                            FilterChip(fps == f, { vm.setFps(f) }, { Text(f.toString()) })
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Length", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp))
                        OutlinedTextField(
                            value = String.format("%.1f", lengthSec),
                            onValueChange = {
                                val v = it.replace(',', '.').toDoubleOrNull()
                                if (v != null) vm.setLength(v)
                            },
                            modifier = Modifier.width(110.dp),
                            singleLine = true,
                            suffix = { Text("s") }
                        )
                    }
                    val n = (lengthSec * fps).roundToInt().coerceAtLeast(1)
                    val kb = ((8 + n.toLong() * 16608) / 1024)
                    val trimmed = n > fs.maxFrames
                    Text(
                        (if (srcDuration > 0) String.format("source %.1f s · ", srcDuration) else "") +
                            String.format("%.1f s @ %d fps = %d frames · %d kB", lengthSec, fps, n, kb) +
                            (if (trimmed) "  — trimmed, device fits " + fs.maxFrames + " frames" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = if (trimmed) Warn else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = when (statusKind) { 1 -> Ok; 2 -> Danger
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant }
            )

            if (progress >= 0f) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.startUpload() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Working…" else "↑ Convert & upload") }
        }
    }
}
