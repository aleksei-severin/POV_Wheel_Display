package com.povwheel.app.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.povwheel.app.WheelVm
import com.povwheel.app.convert.Ani6
import com.povwheel.app.convert.Converter
import com.povwheel.app.convert.Fit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val fs by vm.fsInfo.collectAsState()
    val connected by vm.connected.collectAsState()
    val mirror by vm.mirrorAll.collectAsState()

    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var poster by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("Waiting for a file…") }
    var statusKind by remember { mutableStateOf(0) }        // 0 обычный, 1 успех, 2 ошибка
    var progress by remember { mutableStateOf(-1f) }
    var busy by remember { mutableStateOf(false) }

    var fitMode by remember { mutableStateOf(Fit.CROP) }
    var fps by remember { mutableStateOf(10) }
    var lengthSec by remember { mutableStateOf(10.0) }
    var isVideo by remember { mutableStateOf(false) }
    var srcDuration by remember { mutableStateOf(0.0) }

    val converter = remember { Converter(ctx) }

    // Долгая конвертация плюс передача на мегабайты переживает таймаут экрана,
    // а погасший экран подвесил бы очередь BLE на середине файла.
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    // Разбор выбранного — общий для галереи и файлового менеджера.
    val onPicked: (List<Uri>) -> Unit = { picked ->
        if (picked.isNotEmpty()) {
            uris = picked
            scope.launch {
                val first = picked.first()
                val kind = withContext(Dispatchers.IO) {
                    val sniff = try {
                        if (converter.mimeOf(first).startsWith("video/")) null
                        else converter.readBytes(first)
                    } catch (e: Exception) { null }
                    converter.kindOf(first, sniff)
                }
                isVideo = kind == Converter.Kind.VIDEO
                if (isVideo) {
                    srcDuration = withContext(Dispatchers.IO) { converter.videoDurationSec(first) }
                    val cap = fs.maxFrames.toDouble() / fps
                    lengthSec = maxOf(0.5, minOf(if (srcDuration > 0) srcDuration else cap, cap))
                }
                poster = withContext(Dispatchers.Default) { converter.posterOf(first, fitMode, 216) }
                status = if (picked.size > 1) picked.size.toString() + " files selected. Press Upload."
                         else converter.displayName(first) + " ready. Press Upload."
                statusKind = 1
            }
        }
    }

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

    // Перерисовываем миниатюру при смене режима кадрирования — как в вебе.
    LaunchedEffect(fitMode) {
        val u = uris.firstOrNull() ?: return@LaunchedEffect
        poster = withContext(Dispatchers.Default) { converter.posterOf(u, fitMode, 216) }
    }

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
                    FilterChip(fitMode == Fit.CROP, { fitMode = Fit.CROP }, { Text("Crop") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(fitMode == Fit.FIT, { fitMode = Fit.FIT }, { Text("Fit") })
                }

                if (isVideo) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("FPS", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp))
                        listOf(5, 10, 15).forEach { f ->
                            FilterChip(fps == f, {
                                fps = f
                                // Смена fps только УКОРАЧИВАЕТ выбранную длину и
                                // никогда не удлиняет — правило то же, что в вебе.
                                val cap = fs.maxFrames.toDouble() / f
                                if (lengthSec > cap) lengthSec = cap
                            }, { Text(f.toString()) })
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
                                if (v != null) lengthSec = v.coerceIn(0.5, fs.maxFrames.toDouble() / fps)
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
                onClick = {
                    val list = uris
                    if (list.isEmpty()) { status = "Select a file first."; statusKind = 2; return@Button }
                    val targets = if (mirror) connected else listOfNotNull(vm.currentClient())
                    if (targets.isEmpty()) { status = "Not connected."; statusKind = 2; return@Button }

                    busy = true
                    statusKind = 0
                    scope.launch {
                        var ok = 0
                        var fail = 0
                        for (u in list) {
                            val label = converter.displayName(u)
                            try {
                                status = label + " — converting…"
                                progress = -1f
                                val res = withContext(Dispatchers.Default) {
                                    converter.convert(
                                        u, fitMode, fs.maxFrames,
                                        Converter.VideoOpts(fps, lengthSec),
                                        object : Converter.Progress {
                                            override fun stage(text: String) { status = label + " — " + text }
                                            override fun frames(done: Int, total: Int) {
                                                status = label + " — converting " + done + "/" + total +
                                                        " frames (" + (done * 100 / maxOf(total, 1)) + "%)"
                                                progress = done.toFloat() / maxOf(total, 1)
                                            }
                                        }
                                    )
                                }
                                res.warning?.let { vm.say(it) }

                                val crc = withContext(Dispatchers.Default) { Ani6.crc32(res.data) }

                                for (c in targets) {
                                    val wire = withContext(Dispatchers.Default) {
                                        Ani6.encodeForWire(res.data, c.hello?.hasDeflate ?: false)
                                    }
                                    val ratio = res.data.size.toDouble() / maxOf(wire.bytes.size, 1)
                                    val started = System.currentTimeMillis()
                                    c.upload(res.fileName, wire.bytes, res.data.size, crc, wire.compressed) { p ->
                                        progress = p.sent.toFloat() / maxOf(p.totalWire, 1L)
                                        val kb = p.sent / 1024
                                        val tot = p.totalWire / 1024
                                        val secs = (System.currentTimeMillis() - started) / 1000.0
                                        val rate = if (secs > 0.4) (p.sent / 1024.0 / secs) else 0.0
                                        status = label + " — " +
                                            (p.sent * 100 / maxOf(p.totalWire, 1L)) + "%  ·  " +
                                            kb + " / " + tot + " kB" +
                                            (if (wire.compressed) String.format("  ·  x%.1f smaller", ratio) else "") +
                                            (if (rate > 0) String.format("  ·  %.0f kB/s", rate) else "")
                                    }
                                }
                                ok++
                            } catch (e: Throwable) {
                                // Throwable, а не Exception: длинная анимация —
                                // это 8 МБ исходника плюс столько же под
                                // сжатый поток, и OutOfMemoryError здесь вполне
                                // достижим. Он наследуется от Error, мимо
                                // catch(Exception) проходил насквозь и ронял
                                // приложение — с застрявшим busy и незаснувшим
                                // экраном вместо сообщения об ошибке.
                                fail++
                                val why = e.message?.takeIf { it.isNotBlank() }
                                    ?: e::class.java.simpleName
                                status = label + " — failed: " + why
                                statusKind = 2
                            }
                        }
                        progress = -1f
                        busy = false
                        if (fail == 0) {
                            status = if (ok == 1) "Uploaded." else ok.toString() + " files uploaded."
                            statusKind = 1
                            uris = emptyList()
                            poster = null
                        } else {
                            status = ok.toString() + " uploaded, " + fail + " failed."
                            statusKind = 2
                        }
                        vm.refreshFiles()
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Working…" else "↑ Convert & upload") }
        }
    }
}
