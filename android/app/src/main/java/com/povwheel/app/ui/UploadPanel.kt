package com.povwheel.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.povwheel.app.WheelVm
import com.povwheel.app.convert.Fit
import com.povwheel.app.convert.PreviewClip
import kotlin.math.ceil
import kotlin.math.roundToInt

// Пилюля фиксированной ширины: скруглённая рамка, как у чипа. Ширина задаётся
// снаружи и НЕ зависит от подписи — иначе «FPS 10» / «FPS 5» дёргали бы весь ряд.
private val PILL_HEIGHT = 34.dp
private val PILL_SHAPE = RoundedCornerShape(50)

@Composable
private fun Pill(
    text: String,
    width: Dp,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val border = if (selected) cs.primary else cs.outline
    val fg = when {
        !enabled -> cs.onSurface.copy(alpha = 0.38f)
        selected -> cs.primary
        else -> cs.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = PILL_SHAPE,
        color = if (selected) cs.primary.copy(alpha = 0.12f) else Color.Transparent,
        border = BorderStroke(1.dp, if (enabled) border else border.copy(alpha = 0.38f)),
        modifier = Modifier.width(width).height(PILL_HEIGHT)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        }
    }
}

// Та же пилюля, но с редактируемым числом внутри (длина ролика).
@Composable
private fun PillField(
    value: String,
    width: Dp,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val fg = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.38f)
    Surface(
        shape = PILL_SHAPE,
        color = Color.Transparent,
        border = BorderStroke(1.dp, cs.outline.copy(alpha = if (enabled) 1f else 0.38f)),
        modifier = Modifier.width(width).height(PILL_HEIGHT)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium.copy(
                    color = fg, textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { inner() }
                        Text("s", style = MaterialTheme.typography.labelMedium, color = fg)
                    }
                },
                modifier = Modifier.padding(horizontal = 7.dp)
            )
        }
    }
}

// Потолок выбора за раз. Фотопикер требует не меньше двух, а больше
// нескольких десятков анимаций всё равно не поместится в PSRAM колеса.
private const val MAX_PICK = 30

/**
 * Одно круглое превью в сетке. Кадр прогнан через то же полярное преобразование,
 * что и заливка (`DiscRender`), поэтому виден результат на ободе, а не квадрат.
 * Пока превью считается — крутилка. У выбранной ячейки [clip] не пуст — тогда
 * GIF/видео проигрываются, остальные показывают первый кадр.
 */
@Composable
private fun PosterCell(
    item: WheelVm.UpItem,
    selected: Boolean,
    size: Dp,
    enabled: Boolean,
    clip: PreviewClip? = null,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).background(cs.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) cs.primary else cs.outlineVariant,
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val p = item.poster
        if (clip != null || p != null) {
            AnimatedDisc(clip, p, Modifier.fillMaxSize().padding(2.dp).clip(CircleShape))
        } else {
            CircularProgressIndicator(
                Modifier.size(size * 0.34f),
                strokeWidth = 2.dp,
                color = cs.onSurfaceVariant
            )
        }
    }
}

/**
 * Сетка превью всех выбранных файлов. Число столбцов подбирается так, чтобы
 * ячейка (примерно квадратная) была максимальной и укладывалась и по ширине
 * области, и в желаемую высоту `target`: один файл — крупно, три десятка —
 * мелко, но всё видно разом. Тап по превью выбирает его для настройки.
 */
@Composable
private fun PosterGrid(
    items: List<WheelVm.UpItem>,
    selected: Int,
    enabled: Boolean,
    selClip: PreviewClip?,
    onSelect: (Int) -> Unit
) {
    val gap = 6.dp
    val target = 156.dp
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints {
            val availW = maxWidth
            val n = items.size
            var cols = 1
            var cell = 0.dp
            for (c in 1..n) {
                val r = ceil(n / c.toFloat()).toInt()
                val byW = (availW - gap * (c - 1)) / c
                val byH = (target - gap * (r - 1)) / r
                val cc = minOf(byW, byH)
                if (cc > cell) { cell = cc; cols = c }
            }
            cell = cell.coerceIn(34.dp, 104.dp)
            val rows = ceil(n / cols.toFloat()).toInt()
            Column(
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (row in 0 until rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (col in 0 until cols) {
                            val idx = row * cols + col
                            if (idx < n) {
                                PosterCell(
                                    items[idx], idx == selected, cell, enabled,
                                    clip = if (idx == selected) selClip else null
                                ) { onSelect(idx) }
                            } else {
                                Spacer(Modifier.size(cell))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Выбор файлов, конвертация и заливка.
 *
 * Всё дорогое считается на телефоне: декодирование, полярная передискретизация
 * и квантование median cut. На колесо уходит готовый файл ANI6, сжатый DEFLATE, —
 * распаковывает его подпрограмма из ПЗУ устройства, так что сжатие не стоит
 * прошивке ни байта флеша и примерно втрое поднимает реальную скорость.
 *
 * Пачку файлов больше нельзя гнать под одну гребёнку: сетка показывает превью
 * каждого, а ряд пилюль правит настройки того, что выбрано тапом. «Apply to all»
 * копирует их на всю пачку.
 */
@Composable
fun UploadPanel(vm: WheelVm) {
    val fs by vm.fsInfo.collectAsState()

    // Всё состояние панели живёт во ViewModel. В самой панели его держать
    // нельзя: уход на другую вкладку выкидывает её из композиции, и вместе с
    // ней умирали и выбор файлов, и прогресс, и — главное — сама корутина
    // заливки. См. комментарий у upItems в WheelVm.
    val items by vm.upItems.collectAsState()
    val sel by vm.upSel.collectAsState()
    val selClip by vm.upSelClip.collectAsState()
    val status by vm.upStatus.collectAsState()
    val statusKind by vm.upKind.collectAsState()
    val progress by vm.upProgress.collectAsState()
    val busy by vm.upBusy.collectAsState()

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

    val pickGallery = {
        gallery.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Add animation", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            if (items.isEmpty()) {
                // Аналог зоны перетаскивания из веба.
                Box(
                    Modifier.fillMaxWidth().height(132.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .clickable(enabled = !busy) { pickGallery() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⬆", fontSize = 26.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Choose videos, GIFs or images",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("tap to open your gallery",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                PosterGrid(items, sel.coerceIn(0, items.size - 1), !busy, selClip) { vm.selectUpItem(it) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { pickGallery() }, enabled = !busy) {
                    Text(if (items.isEmpty()) "Gallery" else "Choose other files")
                }
                TextButton(
                    onClick = { browser.launch(arrayOf("image/*", "video/*")) },
                    enabled = !busy
                ) { Text("Browse files") }
            }

            if (items.isNotEmpty()) {
                val selIdx = sel.coerceIn(0, items.size - 1)
                val cur = items[selIdx]

                Spacer(Modifier.height(6.dp))
                // Что за файл сейчас настраивается + действия над пачкой.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (items.size > 1) (selIdx + 1).toString() + "/" + items.size + "  " else "") + cur.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (items.size > 1) {
                        TextButton(onClick = { vm.applyUpSettingsToAll() }, enabled = !busy) {
                            Text("Apply to all", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    TextButton(onClick = { vm.removeUpItem(selIdx) }, enabled = !busy) {
                        Text("Remove", style = MaterialTheme.typography.labelMedium, color = Danger)
                    }
                }

                Spacer(Modifier.height(6.dp))
                // Пилюли фиксированной ширины: подпись меняется («Crop»/«Fit»,
                // «FPS 10»/«FPS 5»), а рамка нет, поэтому ряд не дёргается.
                // Правят настройки ВЫБРАННОГО файла.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Pill(
                        text = if (cur.fit == Fit.CROP) "Crop" else "Fit",
                        width = 46.dp, enabled = !busy,
                        onClick = { vm.setFit(if (cur.fit == Fit.CROP) Fit.FIT else Fit.CROP) }
                    )
                    Pill(
                        text = "Mirror back",
                        width = 84.dp, selected = cur.mirror, enabled = !busy,
                        onClick = { vm.setBackMirror(!cur.mirror) }
                    )
                    if (cur.isVideo) {
                        Pill(
                            text = "FPS " + cur.fps,
                            width = 54.dp, enabled = !busy,
                            onClick = { vm.setFps(when (cur.fps) { 10 -> 15; 15 -> 5; else -> 10 }) }
                        )
                        PillField(
                            value = String.format("%.1f", cur.lengthSec),
                            width = 56.dp, enabled = !busy,
                            onValueChange = {
                                val v = it.replace(',', '.').toDoubleOrNull()
                                if (v != null) vm.setLength(v)
                            }
                        )
                    }
                }

                if (cur.isVideo) {
                    Spacer(Modifier.height(8.dp))
                    val nfr = (cur.lengthSec * cur.fps).roundToInt().coerceAtLeast(1)
                    val kb = ((8 + nfr.toLong() * 16608) / 1024)
                    val trimmed = nfr > fs.maxFrames
                    Text(
                        (if (cur.srcDur > 0) String.format("source %.1f s · ", cur.srcDur) else "") +
                            String.format("%.1f s @ %d fps = %d frames · %d kB", cur.lengthSec, cur.fps, nfr, kb) +
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
