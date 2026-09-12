package com.povwheel.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.povwheel.app.WheelEntry
import com.povwheel.app.WheelVm
import com.povwheel.app.ble.DevFile
import com.povwheel.app.ble.Link
import com.povwheel.app.ble.Tele
import com.povwheel.app.convert.Fit
import com.povwheel.app.convert.PreviewClip
import kotlin.math.roundToInt

// Потолок выбора за раз (фотопикер). Больше нескольких десятков анимаций всё
// равно не влезет в PSRAM колеса.
private const val MAX_PICK = 30

private enum class LibMode { NORMAL, DELETE, SLIDESHOW }

// Ячейка сетки: «+», ждущий заливки файл, файл с колеса или процедурный эффект.
private sealed interface Cell {
    object Add : Cell
    data class Pending(val item: WheelVm.UpItem, val index: Int) : Cell
    data class Stored(val file: DevFile) : Cell
    data class Effect(val id: Int) : Cell
}

private fun cellKey(c: Cell): Any = when (c) {
    Cell.Add -> "add"
    is Cell.Pending -> "p:" + c.index          // индекс уникален даже при дублях Uri
    is Cell.Stored -> "s:" + c.file.name
    is Cell.Effect -> "e:" + c.id
}

/**
 * Библиотека одной плиткой круглых анимированных превью, 5 в ряд:
 * **+**, ждущие заливки файлы, файлы с колеса, в хвосте — процедурные эффекты.
 *
 * - тап по файлу/эффекту — играть на колесе (зелёный ободок); тап по идущему
 *   эффекту гасит показ;
 * - длинный тап по файлу — режим удаления (чекбоксы + красная Delete);
 *   эффекты удалить нельзя;
 * - кнопка **Slideshow**: тап — режим выбора (файлы отмечены, эффекты нет — их
 *   добавляют вручную), внизу зелёная **Start**; длинный тап — интервал; идёт
 *   показ — кнопка **Stop slideshow**;
 * - **+**: добавить файлы. Ждущие заливки — жёлтый ободок, у льющегося сейчас
 *   ободок сматывается по прогрессу.
 *
 * Отбор для слайдшоу (файлы + маска эффектов) — фича прошивки (`Hello.hasAlbumSel`).
 * На старом колесе кнопка Slideshow просто включает/выключает показ всех файлов.
 */
/**
 * Единственный экран управления: плитка библиотеки, а следом — блоки настроек
 * ([extraItems], передаются из DeviceScreen как full-span элементы той же сетки,
 * чтобы всё скроллилось вместе и в одном стиле).
 */
@Composable
internal fun LibraryTab(
    vm: WheelVm,
    tele: Tele,
    leadingItems: (LazyGridScope.() -> Unit)? = null,
    extraItems: (LazyGridScope.() -> Unit)? = null
) {
    val files by vm.files.collectAsState()
    val fs by vm.fsInfo.collectAsState()
    val items by vm.upItems.collectAsState()
    val upSel by vm.upSel.collectAsState()
    val upSelClip by vm.upSelClip.collectAsState()
    val upBusy by vm.upBusy.collectAsState()
    val upCurUri by vm.upCurrentUri.collectAsState()
    val upProgress by vm.upProgress.collectAsState()
    val curWheel by vm.current.collectAsState()
    val wheels by vm.wheels.collectAsState()
    val syncPartnersMap by vm.syncPartners.collectAsState()
    val pendingSyncDelete by vm.pendingSyncDelete.collectAsState()

    val hasSel = vm.currentClient()?.hello?.hasAlbumSel == true
    val allNames = files.map { it.name }
    // Другие колёса, с которыми прямо сейчас можно синхронизировать слайдшоу.
    val otherReady = wheels.filter { it.link == Link.Ready && it.address != curWheel }
    val activePartners = curWheel?.let { syncPartnersMap[it] } ?: emptyList()

    var mode by remember { mutableStateOf(LibMode.NORMAL) }
    var checks by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Счётчик повторных тапов по «Slideshow» в режиме выбора: сохранённое → все →
    // ничего → все → ничего → …
    var slideCycle by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var intervalDialog by remember { mutableStateOf(false) }
    // Локальный интервал: пока пользователь не трогал — следуем за устройством.
    var secsLocal by remember { mutableStateOf(-1) }
    val slideSecs = if (secsLocal > 0) secsLocal else (tele.slideSecs.takeIf { it in 1..300 } ?: 10)

    // Партнёры (два и больше), отмеченные (пока не запущено) в режиме выбора
    // слайдшоу, и общий с ними список файлов (имя+размер совпадают у всех).
    var pickedPartners by remember { mutableStateOf<Set<String>>(emptySet()) }
    var commonNames by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Сменили колесо или библиотека уехала из-под режима — выходим из него.
    LaunchedEffect(curWheel) {
        mode = LibMode.NORMAL; checks = emptySet(); secsLocal = -1; slideCycle = 0
        pickedPartners = emptySet(); commonNames = emptySet()
    }
    LaunchedEffect(mode, files) {
        // Держим только реально существующие файлы + токены эффектов (@eN).
        if (mode != LibMode.NORMAL)
            checks = checks.filterTo(HashSet()) { it in allNames || vm.isSlideEffect(it) }
    }
    // Партнёров отметили (или сняли) — пересчитываем общий список и отмечаем
    // ровно его: только на общих для ВСЕХ выбранных колёс анимациях должны
    // стоять галочки (эффекты общие всегда — их отбор трогать не надо).
    LaunchedEffect(pickedPartners) {
        val partners = pickedPartners
        val addr = curWheel
        if (partners.isNotEmpty() && addr != null) {
            val common = vm.commonFileNames(addr, partners)
            commonNames = common
            checks = (common + checks.filter { vm.isSlideEffect(it) }).toSet()
        } else {
            commonNames = emptySet()
            if (mode == LibMode.SLIDESHOW) checks = vm.savedSlideSelection(allNames)
        }
    }

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)
    ) { picked -> vm.onFilesPicked(picked) }
    val onAdd = {
        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    // Пока выбираются партнёры для синхронного слайдшоу, плитка показывает всё
    // как обычно (файлы и эффекты) — просто файл, которого нет у кого-то из
    // выбранных партнёров, нельзя отметить (см. LibraryCell.lockedForSync):
    // иначе последовательность разойдётся между колёсами с первого же
    // переключения. Эффекты общие всегда (это не файл с колеса), поэтому их
    // теперь можно включать в синхронный показ наравне с анимациями.
    val syncPicking = mode == LibMode.SLIDESHOW && pickedPartners.isNotEmpty()
    val cells = remember(items, files) {
        buildList {
            add(Cell.Add)
            items.forEachIndexed { i, it -> add(Cell.Pending(it, i)) }
            files.forEach { f -> add(Cell.Stored(f)) }
            EFFECT_IDS.forEach { add(Cell.Effect(it)) }   // эффекты в хвосте плитки
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            leadingItems?.invoke(this)   // окошки DISPLAY/BATTERY — скроллятся вместе с лентой

            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                LibraryHeader(
                    freeText = if (fs.total > 0)
                        String.format("%.1f MB free", fs.free / 1048576.0) else "",
                    slideshowOn = tele.slideshow || activePartners.isNotEmpty(),
                    selecting = mode == LibMode.SLIDESHOW,
                    // hasSel → можно сделать слайдшоу из одних эффектов, файлы не нужны.
                    enabled = tele.slideshow || activePartners.isNotEmpty() || files.isNotEmpty() || hasSel,
                    playing = tele.play && !tele.slideshow,
                    onStop = { vm.stopDisplay() },
                    onTap = {
                        when {
                            activePartners.isNotEmpty() -> curWheel?.let { vm.stopSyncedSlideshow(it) }
                            tele.slideshow -> vm.stopSlideshow()
                            !hasSel -> vm.album(true, slideSecs * 1000)
                            // Первый тап — режим выбора с сохранённым набором.
                            mode != LibMode.SLIDESHOW -> {
                                checks = vm.savedSlideSelection(allNames)
                                slideCycle = 0
                                mode = LibMode.SLIDESHOW
                            }
                            // Дальше по кругу: все анимации из памяти → ничего → …
                            else -> {
                                slideCycle++
                                checks = if (slideCycle % 2 == 1) allNames.toSet() else emptySet()
                            }
                        }
                    },
                    onLongPress = { intervalDialog = true }
                )
            }

            if (items.isNotEmpty()) item(key = "upload", span = { GridItemSpan(maxLineSpan) }) {
                UploadStrip(vm)
            }

            items(cells.size, key = { cellKey(cells[it]) }) { i ->
                val cell = cells[i]
                val isSelPending = (cell as? Cell.Pending)?.index == upSel
                val playing = when (cell) {
                    // Идёт эффект — на ободе не файл, даже если tele.file ещё
                    // держит имя последнего (в слайдшоу оно не сбрасывается).
                    is Cell.Stored ->
                        tele.effect == 0 && tele.file == cell.file.name && (tele.play || tele.slideshow)
                    is Cell.Effect -> tele.effect == cell.id
                    else -> false
                }
                val checked = when (cell) {
                    is Cell.Stored -> cell.file.name in checks
                    is Cell.Effect -> ("@e" + cell.id) in checks
                    else -> false
                }
                // Файла нет у кого-то из выбранных для синхронного показа
                // партнёров — оставляем его в плитке (не убираем совсем), но
                // отмечать нельзя: иначе последовательность разойдётся между
                // колёсами с первого же переключения. Эффекты общие всегда.
                val lockedForSync = syncPicking && cell is Cell.Stored && cell.file.name !in commonNames
                LibraryCell(
                    vm = vm, cell = cell, mode = mode, upBusy = upBusy,
                    checked = checked,
                    lockedForSync = lockedForSync,
                    selectedPending = isSelPending,
                    pendingClip = if (isSelPending) upSelClip else null,
                    uploading = upCurUri != null && (cell as? Cell.Pending)?.item?.uri == upCurUri,
                    progress = upProgress,
                    playing = playing,
                    onAdd = onAdd,
                    onToggleCheck = { n -> checks = if (n in checks) checks - n else checks + n },
                    onEnterDelete = { n -> mode = LibMode.DELETE; checks = setOf(n) }
                )
            }

            extraItems?.invoke(this)
            item(key = "tail", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
        }

        when (mode) {
            LibMode.DELETE -> ActionBar(
                label = "Delete " + checks.size, danger = true, enabled = checks.isNotEmpty(),
                onAction = { confirmDelete = true },
                onCancel = { mode = LibMode.NORMAL; checks = emptySet() }
            )
            LibMode.SLIDESHOW -> Column {
                // Выбор партнёров доступен сразу, без отдельного диалога — тап
                // по имени колеса переключает его участие в группе, и можно
                // отметить сразу несколько (два, три и больше дисплеев).
                if (otherReady.isNotEmpty()) SyncTargetsRow(
                    others = otherReady,
                    picked = pickedPartners,
                    onToggle = { a -> pickedPartners = if (a in pickedPartners) pickedPartners - a else pickedPartners + a }
                )
                val synced = pickedPartners.isNotEmpty()
                ActionBar(
                    label = if (synced) "▶ Start synced slideshow" else "▶ Start slideshow",
                    danger = false, enabled = checks.isNotEmpty(),
                    // Длинная надпись «Start synced slideshow» должна уместиться в
                    // одну строку — расширяем кнопку действия за счёт Cancel.
                    cancelWeight = if (synced) 0.7f else 1f,
                    actionWeight = if (synced) 1.6f else 1f,
                    onAction = {
                        if (synced) vm.startSyncedSlideshow(pickedPartners, slideSecs, checks)
                        else vm.startSlideshow(slideSecs, checks, allNames)
                        mode = LibMode.NORMAL; pickedPartners = emptySet()
                    },
                    onCancel = { mode = LibMode.NORMAL; checks = emptySet(); pickedPartners = emptySet() }
                )
            }
            LibMode.NORMAL -> {}
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete " + checks.size + (if (checks.size == 1) " animation?" else " animations?")) },
        confirmButton = {
            TextButton(onClick = hapticClick {
                vm.deleteMany(checks.filterNot { vm.isSlideEffect(it) })   // эффекты не удаляются
                confirmDelete = false; mode = LibMode.NORMAL; checks = emptySet()
            }) { Text("Delete", color = Danger) }
        },
        dismissButton = { TextButton(onClick = hapticClick { confirmDelete = false }) { Text("Cancel") } }
    )

    if (intervalDialog) NumberDialog(
        title = "Slideshow interval",
        body = "How long each animation stays on screen before the next one.",
        initial = slideSecs, unit = "s", min = 1, max = 300,
        onDismiss = { intervalDialog = false },
        onSave = { secsLocal = it; vm.setSlideInterval(it); intervalDialog = false }
    )

    val pending = pendingSyncDelete
    if (pending != null) AlertDialog(
        onDismissRequest = { vm.confirmSyncDelete(false) },
        title = { Text("Delete for " + pending.partners.joinToString(", ") { it.second } + " too?") },
        text = {
            Text(
                (if (pending.names.size == 1) pending.names.first() else pending.names.size.toString() + " files") +
                    " — part of the synced slideshow with " + pending.partners.joinToString(", ") { it.second } +
                    ". Leaving it there will break the sync unless it's removed too."
            )
        },
        confirmButton = {
            TextButton(onClick = hapticClick { vm.confirmSyncDelete(true) }) {
                Text("Delete there too", color = Danger)
            }
        },
        dismissButton = { TextButton(onClick = hapticClick { vm.confirmSyncDelete(false) }) { Text("Keep it there") } }
    )
}

@Composable
private fun LibraryHeader(
    freeText: String,          // свободное место на флеше — справа от «Library»
    slideshowOn: Boolean,
    selecting: Boolean,
    enabled: Boolean,
    playing: Boolean,          // играет отдельная анимация/эффект (не слайдшоу)
    onStop: () -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Library",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.alignByBaseline()
        )
        // Занимает весь свободный зазор и первым ужимается (…), чтобы кнопки
        // Stop + Slideshow всегда влезли в одну строку.
        Text(
            freeText,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alignByBaseline()
        )

        // Слева от Slideshow: Stop для текущей анимации (бывшая кнопка окна DISPLAY).
        if (playing && !slideshowOn) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Danger.copy(alpha = 0.14f))
                    .border(1.dp, Danger, RoundedCornerShape(50))
                    .tapClickable(onClick = onStop)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text("■ Stop", style = MaterialTheme.typography.labelLarge, color = Danger)
            }
        }

        val border = when {
            !enabled -> cs.outline.copy(alpha = 0.38f)
            slideshowOn -> Danger
            selecting -> Accent
            else -> cs.outline
        }
        val fg = when {
            !enabled -> cs.onSurface.copy(alpha = 0.38f)
            slideshowOn -> Danger
            else -> cs.onSurface
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (slideshowOn && enabled) Danger.copy(alpha = 0.14f) else Color.Transparent)
                .border(1.dp, border, RoundedCornerShape(50))
                .tapCombinedClickable(enabled = enabled, onClick = onTap, onLongClick = onLongPress)
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                if (slideshowOn) "■ Stop slideshow" else "Slideshow",
                style = MaterialTheme.typography.labelLarge,
                color = fg
            )
        }
    }
}

/**
 * Строка над ActionBar в режиме выбора слайдшоу: с кем сейчас синхронизируем.
 * Доступна сразу, без отдельного диалога, — тап по имени колеса переключает
 * его участие в группе, отметить можно сразу несколько (два, три и больше).
 * Показывается, только пока есть хоть одно другое подключённое колесо
 * ([otherReady] на вызывающей стороне); список сам скроллится вправо, если
 * имена не влезают в ширину экрана.
 */
@Composable
private fun SyncTargetsRow(
    others: List<WheelEntry>,
    picked: Set<String>,
    onToggle: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Sync with:",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant
        )
        others.forEach { w ->
            SyncChip(w.name, selected = w.address in picked, onClick = { onToggle(w.address) })
        }
    }
}

/** Пилюля-переключатель одного колеса в [SyncTargetsRow]. В отличие от [Pill]
 *  ширина не фиксирована — колёса называют по-разному, а список и так скроллится. */
@Composable
private fun SyncChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val border = if (selected) cs.primary else cs.outline
    val fg = if (selected) cs.primary else cs.onSurface
    Surface(
        onClick = hapticClick(onClick),
        shape = PILL_SHAPE,
        color = if (selected) cs.primary.copy(alpha = 0.12f) else Color.Transparent,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.height(PILL_HEIGHT)
    ) {
        Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        }
    }
}

@Composable
private fun ActionBar(
    label: String,
    danger: Boolean,
    enabled: Boolean,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    cancelWeight: Float = 1f,
    actionWeight: Float = 1f
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = hapticClick(onCancel), modifier = Modifier.weight(cancelWeight)) { Text("Cancel") }
            Button(
                onClick = hapticClick(onAction), enabled = enabled, modifier = Modifier.weight(actionWeight),
                colors = ButtonDefaults.buttonColors(containerColor = if (danger) Danger else Ok),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun LibraryCell(
    vm: WheelVm,
    cell: Cell,
    mode: LibMode,
    upBusy: Boolean,
    checked: Boolean,
    // Файла нет у кого-то из партнёров, отмеченных для синхронного показа —
    // ячейка остаётся в плитке (чтобы не путать «нет на колесе» с «нельзя
    // синхронизировать»), только тускнеет и не отмечается тапом.
    lockedForSync: Boolean,
    selectedPending: Boolean,
    pendingClip: PreviewClip?,
    uploading: Boolean,
    progress: Float,
    playing: Boolean,
    onAdd: () -> Unit,
    onToggleCheck: (String) -> Unit,
    onEnterDelete: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var confirmRemove by remember { mutableStateOf(false) }

    val ring: Color? = when {
        cell is Cell.Pending -> Warn
        playing -> Ok
        else -> null
    }
    val ringW = if (cell is Cell.Pending && selectedPending) 3.dp else 2.dp

    // Внешний бокс НЕ обрезан по кругу — иначе чекбокс в углу срезается. Круглые
    // фон/ободок/содержимое (и обрезанный по кругу ripple) живут во внутреннем
    // боксе, чекбокс — поверх, в углу.
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .then(if (lockedForSync) Modifier.alpha(0.35f) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(if (cell is Cell.Effect) Color.Black else cs.surfaceVariant)
                .then(
                    // Пока ободок сматывается прогрессом — статичное кольцо не рисуем.
                    if (ring != null && !uploading) Modifier.border(ringW, ring, CircleShape)
                    else Modifier
                )
                .tapCombinedClickable(
                    onClick = {
                        when (cell) {
                            Cell.Add -> if (!upBusy) onAdd()
                            is Cell.Pending -> if (mode == LibMode.NORMAL) vm.selectUpItem(cell.index)
                            is Cell.Stored ->
                                if (mode == LibMode.NORMAL) vm.play(cell.file.name)
                                else if (!lockedForSync) onToggleCheck(cell.file.name)
                            is Cell.Effect -> when (mode) {
                                // тап по идущему эффекту гасит показ, по другому — запускает
                                LibMode.NORMAL ->
                                    if (playing) { vm.effect(0); vm.say("Display stopped") }
                                    else { vm.effect(cell.id); vm.say(effectName(cell.id)) }
                                LibMode.SLIDESHOW -> onToggleCheck("@e" + cell.id)
                                LibMode.DELETE -> {}   // эффект удалить нельзя
                            }
                        }
                    },
                    onLongClick = {
                        when (cell) {
                            is Cell.Stored -> if (mode == LibMode.NORMAL) onEnterDelete(cell.file.name)
                            is Cell.Pending -> if (mode == LibMode.NORMAL && !uploading) confirmRemove = true
                            else -> {}
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when (cell) {
                Cell.Add -> {
                    val plus = cs.onSurfaceVariant
                    Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                        drawCircle(
                            color = plus,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f))
                            )
                        )
                    }
                    Text("+", fontSize = 26.sp, fontWeight = FontWeight.Light, color = plus)
                }
                is Cell.Pending ->
                    if (cell.item.poster != null)
                        AnimatedDisc(pendingClip, cell.item.poster,
                            Modifier.fillMaxSize().padding(3.dp).clip(CircleShape))
                    else
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = cs.onSurfaceVariant)
                is Cell.Stored -> StoredDisc(vm, cell.file)
                is Cell.Effect -> EffectPreview(cell.id, Modifier.fillMaxSize())
            }

            if (uploading) {
                Canvas(Modifier.fillMaxSize().padding(1.dp)) {
                    val w = 3.dp.toPx()
                    drawArc(
                        color = Warn,
                        startAngle = -90f,
                        sweepAngle = -360f * (1f - progress.coerceIn(0f, 1f)),
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        style = Stroke(width = w, cap = StrokeCap.Round)
                    )
                }
            }
        }

        // Чекбоксы: файлы — в удалении и в слайдшоу; эффекты — только в слайдшоу
        // (удалить эффект нельзя).
        val showCheck = when (cell) {
            is Cell.Stored -> mode != LibMode.NORMAL
            is Cell.Effect -> mode == LibMode.SLIDESHOW
            else -> false
        }
        if (showCheck) CheckDot(checked, Modifier.align(Alignment.BottomEnd))

        // В режиме выбора у файла — его размер в левом нижнем углу.
        if (cell is Cell.Stored && mode != LibMode.NORMAL) {
            Text(
                fmtBytes(cell.file.size),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }

    if (confirmRemove && cell is Cell.Pending) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove from upload?") },
        text = { Text(cell.item.name) },
        confirmButton = {
            TextButton(onClick = hapticClick { vm.removeUpItem(cell.index); confirmRemove = false }) {
                Text("Remove", color = Danger)
            }
        },
        dismissButton = { TextButton(onClick = hapticClick { confirmRemove = false }) { Text("Cancel") } }
    )
}

private fun fmtBytes(b: Long): String = when {
    b >= 1_048_576 -> String.format("%.1f MB", b / 1_048_576.0)
    b >= 1024      -> (b / 1024).toString() + " kB"
    else           -> b.toString() + " B"
}

/** Кружок в углу превью в режиме выбора. */
@Composable
private fun CheckDot(checked: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    // Тонкая светлая подложка, чтобы кружок читался и на диске, и в пустом углу.
    Box(
        modifier.size(21.dp).clip(CircleShape)
            .background(cs.surface)
            .border(1.dp, cs.outlineVariant, CircleShape)
            .padding(1.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .background(if (checked) Ok else Color.Transparent)
                .then(if (checked) Modifier else Modifier.border(1.5.dp, cs.outline, CircleShape)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Круглое превью файла с колеса: локальный клип, иначе один кадр по BLE. */
@Composable
private fun StoredDisc(vm: WheelVm, f: DevFile) {
    val pv by vm.previewVersion.collectAsState()
    var thumb by remember(f.name + f.size) { mutableStateOf<Bitmap?>(null) }
    var clip by remember(f.name + f.size) { mutableStateOf<PreviewClip?>(null) }
    LaunchedEffect(f.name, f.size, pv) {
        val c = vm.localClip(f)
        if (c != null) clip = c
        else if (thumb == null) {
            val p = vm.thumb(f)
            if (p != null) thumb = WheelThumb.render(p, 96)
        }
    }
    AnimatedDisc(clip, thumb, Modifier.fillMaxSize().padding(3.dp).clip(CircleShape))
}

/**
 * Настройки и запуск заливки ждущих файлов. Появляется над сеткой, пока в ней
 * есть жёлтые превью. Всё дорогое (декод, полярная выборка, median cut) считает
 * телефон; на колесо уходит готовый ANI6, сжатый DEFLATE.
 */
@Composable
private fun UploadStrip(vm: WheelVm) {
    val fs by vm.fsInfo.collectAsState()
    val items by vm.upItems.collectAsState()
    val sel by vm.upSel.collectAsState()
    val status by vm.upStatus.collectAsState()
    val statusKind by vm.upKind.collectAsState()
    val progress by vm.upProgress.collectAsState()
    val busy by vm.upBusy.collectAsState()
    if (items.isEmpty()) return
    val selIdx = sel.coerceIn(0, items.size - 1)
    val cur = items[selIdx]

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Button(
            onClick = hapticClick { vm.startUpload() }, enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Working…" else "↑ Convert & upload (" + items.size + ")") }

        Spacer(Modifier.height(8.dp))
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = when (statusKind) {
                1 -> Ok; 2 -> Danger; else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (progress >= 0f) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(8.dp))
        // Имя файла и «Remove» убраны: ненужный файл убирается из очереди
        // длинным тапом по его жёлтой ячейке. Осталась только строка «какой из
        // нескольких сейчас настраивается» + «применить ко всем».
        if (items.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Editing " + (selIdx + 1) + " / " + items.size,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = hapticClick { vm.applyUpSettingsToAll() }, enabled = !busy) {
                    Text("Apply to all", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(6.dp))
        }

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
                        it.replace(',', '.').toDoubleOrNull()?.let { v -> vm.setLength(v) }
                    }
                )
            }
        }

        if (cur.isVideo) {
            Spacer(Modifier.height(8.dp))
            val nfr = (cur.lengthSec * cur.fps).roundToInt().coerceAtLeast(1)
            val kb = (8 + nfr.toLong() * 16608) / 1024
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
}

// ---- Пилюли настроек заливки (раньше жили в UploadPanel.kt) ----

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
        onClick = hapticClick(onClick),
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
