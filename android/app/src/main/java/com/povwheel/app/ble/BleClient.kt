package com.povwheel.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class BleException(message: String) : Exception(message)

/**
 * Состояние связи с одним колесом.
 *
 * [Error] — не украшение: раньше сорвавшаяся попытка возвращала [Disconnected],
 * и строка списка становилась неотличима от «никогда не подключались». Причину
 * при этом показывал всплывающий текст, который к моменту, когда пользователь
 * посмотрит на экран, уже исчез.
 */
enum class Link { Disconnected, Connecting, Ready, Error }

/**
 * Одно подключённое колесо.
 *
 * Класс определяют два правила.
 *
 * Первое: Android допускает ровно ОДНУ незавершённую операцию GATT на
 * соединение — не на характеристику. Поэтому каждый запрос идёт через [opLock],
 * а массовая заливка держит этот замок всё своё время.
 *
 * Второе: пропускная способность. Запись без подтверждения всё равно обязана
 * дождаться onCharacteristicWrite, прежде чем выдать следующую, но этот колбэк
 * приходит в момент, когда буфер принял стек, а не когда подтвердила другая
 * сторона, — поэтому канал остаётся заполненным. Сверху устройство выдаёт
 * кредитное окно (своё кольцо), и мы притормаживаем только тогда, когда запись
 * во флеш действительно отстала, а не после каждой посылки.
 *
 * Все поля, которые трогает колбэк GATT, помечены @Volatile: колбэк приходит на
 * потоке binder, а читают их корутины на другом.
 */
@SuppressLint("MissingPermission")
class BleClient(
    private val context: Context,
    val device: BluetoothDevice
) {
    val address: String get() = device.address

    @Volatile private var gatt: BluetoothGatt? = null
    // Соединение сейчас в «мягком» режиме (LOW_POWER, интервал ~0.5 с). При
    // подключении и после заливки — HIGH.
    @Volatile private var connLowPower = false
    @Volatile private var chCmd: BluetoothGattCharacteristic? = null
    @Volatile private var chRsp: BluetoothGattCharacteristic? = null
    @Volatile private var chData: BluetoothGattCharacteristic? = null
    @Volatile private var chFlow: BluetoothGattCharacteristic? = null
    @Volatile private var chTele: BluetoothGattCharacteristic? = null

    private val _link = MutableStateFlow(Link.Disconnected)
    val link: StateFlow<Link> = _link

    /** Чем закончилась последняя неудача. Показывается в строке списка. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Связь пропала сама, без нашего close(). Модель списка колёс по этому
     * событию перечитывает состояние: строка обязана показать обрыв сразу, а
     * не тогда, когда пользователь на неё нажмёт.
     */
    @Volatile var onLinkLost: (() -> Unit)? = null

    private val _tele = MutableStateFlow(Tele())
    val tele: StateFlow<Tele> = _tele

    @Volatile var hello: Hello? = null
        private set

    /** Согласованный ATT MTU. До завершения обмена — 23. */
    @Volatile private var mtu = 23
    val payloadSize: Int get() = mtu - 3

    /** Связи больше нет. Отдельно от _link: читается из циклов заливки. */
    @Volatile private var linkDown = false

    private val opLock = Mutex()
    private val seqGen = AtomicInteger(0)

    // Ответ сопоставляется по байту последовательности из запроса.
    private val pending = HashMap<Int, CompletableDeferred<Pair<Int, ByteArray>>>()
    // Подтверждение записи вместе с UUID характеристики, которую мы ждём.
    // Раньше это был один безымянный слот, а onCharacteristicWrite завершал
    // всё, что в нём лежало: опоздавший колбэк уже отброшенной по таймауту
    // записи досрочно завершал СЛЕДУЮЩУЮ. Во время заливки это сдвигает учёт
    // кредитов на кусок — то есть портит ровно тот механизм, ради скорости
    // которого всё и сделано.
    @Volatile private var writeAck: Pair<java.util.UUID, CompletableDeferred<Int>>? = null
    @Volatile private var descAck: CompletableDeferred<Int>? = null
    @Volatile private var readyAck: CompletableDeferred<Boolean>? = null

    // Управление потоком заливки, питается уведомлениями FLOW.
    @Volatile private var flowConsumed = 0L
    @Volatile private var flowStatus = 0
    @Volatile private var flowWaiter: CompletableDeferred<Unit>? = null
    /** Идёт ли передача. Пока false, уведомления FLOW игнорируются. */
    @Volatile private var xferActive = false
    /** Сколько байт мы отдали. Устройство не может переварить больше. */
    @Volatile private var flowSent = 0L

    var onLog: ((String) -> Unit)? = null

    // -------------------------------------------------------------- соединение

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (_link.value == Link.Ready) return@withContext true
        _link.value = Link.Connecting
        linkDown = false
        val ack = CompletableDeferred<Boolean>()
        readyAck = ack
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(20_000) { ack.await() } ?: false
        readyAck = null
        if (!ok) {
            fail("wheel did not respond")
            return@withContext false
        }
        // Сначала опознаём устройство и узнаём, что оно умеет. Если HELLO не
        // прошёл, соединение бесполезно: без согласованного MTU и списка
        // возможностей всё остальное всё равно посыплется, и честнее сказать
        // об этом сразу, чем показать «подключено» и молча не работать.
        hello = try {
            Hello.parse(request(Proto.OP_HELLO))
        } catch (e: Exception) {
            onLog?.invoke("HELLO failed: " + (e.message ?: ""))
            null
        }
        if (hello == null) {
            fail("handshake failed")
            return@withContext false
        }
        lastError = null
        _link.value = Link.Ready
        true
    }

    /** Свернуть соединение и оставить причину видимой в списке. */
    private fun fail(why: String) {
        close()
        lastError = why
        _link.value = Link.Error
    }

    fun close() {
        linkDown = true
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        _link.value = Link.Disconnected
        releaseWaiters("disconnected")
    }

    /**
     * Освобождает всех, кто ждёт. gatt.close() гасит колбэк
     * onConnectionStateChange, поэтому сделать это обязан тот, кто закрывает, —
     * иначе корутина висит до своего таймаута, а её место в карте клиентов
     * успевает занять новое соединение.
     */
    private fun releaseWaiters(why: String) {
        synchronized(pending) {
            pending.values.forEach { it.completeExceptionally(BleException(why)) }
            pending.clear()
        }
        writeAck?.second?.completeExceptionally(BleException(why))
        descAck?.complete(BluetoothGatt.GATT_FAILURE)
        readyAck?.complete(false)
        flowWaiter?.complete(Unit)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Сначала просим большой MTU: от него считается всё остальное.
                g.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                linkDown = true
                // Закрыть ОБЯЗАТЕЛЬНО, и именно здесь. Android держит около
                // трёх десятков клиентских интерфейсов GATT на приложение, и
                // каждый незакрытый обрыв забирает один навсегда: после десятка
                // отключений телефон перестаёт подключаться вообще, а выглядит
                // это как «колесо не отвечает».
                try { g.close() } catch (_: Exception) {}
                gatt = null
                val wasReady = _link.value == Link.Ready
                if (wasReady || _link.value == Link.Connecting) {
                    lastError = if (status == 0) "connection dropped"
                                else "connection dropped (status " + status + ")"
                    _link.value = Link.Error
                } else {
                    _link.value = Link.Disconnected
                }
                releaseWaiters("link lost")
                onLinkLost?.invoke()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) m else 23
            onLog?.invoke("MTU " + mtu)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(Proto.SVC)
            if (svc == null) {
                onLog?.invoke("service not found")
                readyAck?.complete(false)
                return
            }
            chCmd = svc.getCharacteristic(Proto.CMD)
            chRsp = svc.getCharacteristic(Proto.RSP)
            chData = svc.getCharacteristic(Proto.DATA)
            chFlow = svc.getCharacteristic(Proto.FLOW)
            chTele = svc.getCharacteristic(Proto.TELE)
            if (chCmd == null || chRsp == null || chData == null) {
                readyAck?.complete(false)
                return
            }
            // LE 2M PHY вдвое ускоряет радио там, где телефон это умеет. Это
            // просьба, а не требование: на железе с одним 1M она игнорируется.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    g.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED
                    )
                } catch (_: Exception) {}
            }
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            connLowPower = false
            // Подписка — сама по себе запись GATT, её тоже надо выстроить в очередь.
            Thread {
                val ok = subscribeBlocking(chRsp) &&
                         subscribeBlocking(chFlow) &&
                         subscribeBlocking(chTele)
                readyAck?.complete(ok)
            }.start()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            val w = writeAck ?: return
            if (w.first == c.uuid) w.second.complete(status)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int
        ) {
            descAck?.complete(status)
        }

        // Начиная с API 33 значение приходит аргументом.
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            dispatchNotify(c, value)
        }

        @Deprecated("до API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            dispatchNotify(c, c.value ?: ByteArray(0))
        }
    }

    private fun dispatchNotify(c: BluetoothGattCharacteristic, v: ByteArray) {
        when (c.uuid) {
            Proto.RSP -> {
                if (v.size < 4) return
                val seq = v[1].toInt() and 0xFF
                val status = v[2].toInt() and 0xFF
                val body = v.copyOfRange(4, v.size)
                val d = synchronized(pending) { pending.remove(seq) }
                d?.complete(Pair(status, body))
            }
            Proto.FLOW -> {
                // Ровно 9 байт: статус лежит последним, и на укороченной посылке
                // он читался бы как «всё хорошо», пряча оборванную заливку.
                if (v.size < 9) return
                if (!xferActive) return
                val f = Flow.parse(v)
                // Хвост от предыдущей передачи мог долететь уже после сброса
                // счётчиков. Переварить больше, чем мы отдали, устройство не
                // может — по этому и отличаем чужое уведомление от своего.
                if (f.consumed > flowSent) return
                flowConsumed = f.consumed
                flowStatus = f.status
                flowWaiter?.complete(Unit)
            }
            Proto.TELE -> {
                if (v.size >= Tele.SIZE) _tele.value = Tele.parse(v)
            }
        }
    }

    private fun subscribeBlocking(c: BluetoothGattCharacteristic?): Boolean {
        val g = gatt ?: return false
        if (c == null) return true                 // необязательная характеристика
        if (!g.setCharacteristicNotification(c, true)) return false
        val cccd = c.getDescriptor(
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        ) ?: return false
        val ack = CompletableDeferred<Int>()
        descAck = ack
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val issued: Boolean = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
        if (!issued) { descAck = null; return false }
        // Блокировать можно: это выполняется на своём потоке во время настройки.
        val start = System.currentTimeMillis()
        while (!ack.isCompleted && System.currentTimeMillis() - start < 5000) Thread.sleep(5)
        descAck = null
        // Без подписки ответы просто не придут, и дальше всё встанет по таймауту
        // с невнятной ошибкой — лучше признать неудачу здесь.
        return ack.isCompleted && ack.getCompleted() == BluetoothGatt.GATT_SUCCESS
    }

    // ------------------------------------------------------------------ запрос

    /** Шлёт команду и ждёт ответа. Бросает исключение при ненулевом статусе. */
    suspend fun request(op: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = 8000): ByteArray =
        opLock.withLock { requestUnlocked(op, payload, timeoutMs) }

    private suspend fun requestUnlocked(op: Int, payload: ByteArray, timeoutMs: Long): ByteArray {
        val g = gatt ?: throw BleException("not connected")
        val c = chCmd ?: throw BleException("not connected")
        val seq = seqGen.incrementAndGet() and 0xFF
        val pkt = ByteArray(2 + payload.size)
        pkt[0] = op.toByte()
        pkt[1] = seq.toByte()
        payload.copyInto(pkt, 2)

        val d = CompletableDeferred<Pair<Int, ByteArray>>()
        synchronized(pending) { pending[seq] = d }

        val res = try {
            writeChar(g, c, pkt, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            withTimeoutOrNull(timeoutMs) { d.await() }
        } catch (e: Exception) {
            // Иначе запись, не дошедшая до устройства, оставила бы запись в
            // pending навсегда, и её номер однажды совпал бы с чужим ответом.
            synchronized(pending) { pending.remove(seq) }
            throw e
        }
        if (res == null) {
            synchronized(pending) { pending.remove(seq) }
            throw BleException("timed out waiting for the wheel")
        }
        if (res.first != Proto.ST_OK) throw BleException(Proto.statusText(res.first))
        return res.second
    }

    private suspend fun writeChar(
        g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, type: Int
    ) {
        val ack = CompletableDeferred<Int>()
        writeAck = Pair(c.uuid, ack)
        val ok: Boolean
        if (Build.VERSION.SDK_INT >= 33) {
            ok = g.writeCharacteristic(c, v, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = type
                c.value = v
                ok = g.writeCharacteristic(c)
            }
        }
        if (!ok) { writeAck = null; throw BleException("the radio refused the write") }
        val st = withTimeoutOrNull(6000) { ack.await() }
        // Снимаем ссылку в любом случае: опоздавший колбэк не должен завершать
        // ожидание уже следующей записи.
        writeAck = null
        if (st == null) throw BleException("write timed out")
        if (st != BluetoothGatt.GATT_SUCCESS) throw BleException("write failed (" + st + ")")
    }

    /**
     * Читает длинный ответ. Устройство складывает его в промежуточный буфер и
     * отвечает только длиной; дальше мы вычитываем куски по смещению. Тянуть
     * самим, а не принимать поток уведомлений, значит, что потерянный фрагмент
     * нельзя пропустить молча.
     */
    private suspend fun requestStaged(op: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        opLock.withLock {
            val head = requestUnlocked(op, payload, 12_000)
            if (head.size < 4) return@withLock ByteArray(0)
            val total = Proto.wrap(head).int
            if (total <= 0) return@withLock ByteArray(0)
            val out = ByteArray(total)
            var off = 0
            while (off < total) {
                val want = minOf(payloadSize - 4, total - off)
                if (want <= 0) throw BleException("MTU too small for this reply")
                val req = Proto.buf(6)
                req.putInt(off)
                req.putShort(want.toShort())
                val slice = requestUnlocked(Proto.OP_FRAG, req.array(), 8000)
                if (slice.isEmpty()) throw BleException("the wheel stopped sending")
                slice.copyInto(out, off)
                off += slice.size
            }
            out
        }

    // ----------------------------------------------------------------- команды

    suspend fun getSettings(): Settings = Settings.parse(request(Proto.OP_GET_SET))
    suspend fun setSettings(s: Settings) { request(Proto.OP_SET_SET, s.pack()) }
    suspend fun save() { request(Proto.OP_SAVE) }
    suspend fun stop() { request(Proto.OP_STOP) }
    suspend fun reboot() { request(Proto.OP_REBOOT) }

    /**
     * Выключение в транспортный режим: колесо гасит ленту, сбрасывает настройки
     * во флеш и уходит в сон, из которого его поднимет только удержание кнопки.
     * Сразу после ответа связь оборвётся — так и задумано.
     */
    suspend fun powerOff() { request(Proto.OP_POWEROFF, timeoutMs = 4000) }
    suspend fun play(name: String) { request(Proto.OP_PLAY, name.toByteArray(Charsets.US_ASCII)) }
    suspend fun delete(name: String) { request(Proto.OP_DELETE, name.toByteArray(Charsets.US_ASCII)) }

    /**
     * Запуск процедурного эффекта. Точка покраснения Speed зашита на 45 км/ч —
     * регулятора в интерфейсе больше нет, а значение на устройстве могло
     * остаться другим от старой прошивки.
     */
    suspend fun effect(id: Int) {
        val b = Proto.buf(3)
        b.put(id.toByte())
        b.putShort(45.toShort())   // км/ч красной зоны Speed
        request(Proto.OP_EFFECT, b.array())
    }

    /**
     * Слайдшоу. При старте [names] задаёт отбор файлов (нужен [Hello.hasAlbumSel]):
     * `null` — отбор не трогать (стоп, либо смена только интервала); пустой список —
     * сбросить отбор; иначе [listMode] 0 — пропускать эти, 1 — играть только эти.
     * [effectMask] — биты 0..5 = эффекты 1..6 тоже в показе.
     */
    suspend fun album(
        start: Boolean, delayMs: Int,
        listMode: Int = 0, names: List<String>? = null, effectMask: Int = 0
    ) {
        if (!start || names == null) {
            val b = Proto.buf(5)
            b.put(if (start) 1 else 0)
            b.putInt(delayMs)
            request(Proto.OP_ALBUM, b.array())
            return
        }
        val enc = names.map { it.toByteArray(Charsets.US_ASCII) }.filter { it.size in 1..255 }
        var size = 9
        for (e in enc) size += 1 + e.size
        val b = Proto.buf(size)
        b.put(1)
        b.putInt(delayMs)
        b.put(if (listMode != 0) 1 else 0)
        b.putShort(enc.size.toShort())
        for (e in enc) { b.put(e.size.toByte()); b.put(e) }
        b.put((effectMask and 0x3F).toByte())
        request(Proto.OP_ALBUM, b.array())
    }

    suspend fun fsInfo(): FsInfo = FsInfo.parse(request(Proto.OP_FSINFO))

    suspend fun telemetry(): Tele = Tele.parse(request(Proto.OP_TELE)).also { _tele.value = it }

    suspend fun setTime(epoch: Long, tzSeconds: Int) {
        val b = Proto.buf(8)
        b.putInt(epoch.toInt())
        b.putInt(tzSeconds)
        request(Proto.OP_SETTIME, b.array())
    }

    suspend fun wifi(on: Boolean) { request(Proto.OP_WIFI, byteArrayOf(if (on) 1 else 0)) }

    /** Переименование. Имя видно в списке устройств и приходит в HELLO. */
    suspend fun setName(n: String) {
        request(Proto.OP_SETNAME, n.toByteArray(Charsets.US_ASCII))
    }

    suspend fun list(): List<DevFile> {
        val raw = requestStaged(Proto.OP_LIST)
        if (raw.size < 2) return emptyList()
        val p = Proto.wrap(raw)
        val n = p.short.toInt() and 0xFFFF
        val out = ArrayList<DevFile>(n)
        for (i in 0 until n) {
            if (!p.hasRemaining()) break
            val len = p.get().toInt() and 0xFF
            if (p.remaining() < len + 4) break
            val nm = ByteArray(len); p.get(nm)
            val sz = p.int.toLong() and 0xFFFFFFFFL
            out.add(DevFile(String(nm, Charsets.US_ASCII), sz))
        }
        return out
    }

    /** Миниатюра первого кадра: [секторы][радиусы] и следом RGB565 little-endian. */
    suspend fun preview(name: String): PreviewFrame? {
        val raw = requestStaged(Proto.OP_PREVIEW, name.toByteArray(Charsets.US_ASCII))
        if (raw.size < 2) return null
        val sec = raw[0].toInt() and 0xFF
        val rad = raw[1].toInt() and 0xFF
        if (sec == 0 || rad == 0 || raw.size < 2 + sec * rad * 2) return null
        return PreviewFrame(sec, rad, raw.copyOfRange(2, 2 + sec * rad * 2))
    }

    suspend fun logs(since: Long): Pair<Long, List<String>> {
        val b = Proto.buf(4); b.putInt(since.toInt())
        val raw = requestStaged(Proto.OP_LOGS, b.array())
        if (raw.size < 4) return Pair(since, emptyList())
        val total = Proto.wrap(raw).int.toLong() and 0xFFFFFFFFL
        val text = String(raw, 4, raw.size - 4, Charsets.UTF_8)
        val lines = if (text.isEmpty()) emptyList() else text.split('\n').filter { it.isNotEmpty() }
        return Pair(total, lines)
    }

    // ----------------------------------------------------------------- заливка

    data class Progress(val sent: Long, val totalWire: Long, val written: Long, val totalRaw: Long)

    /**
     * Передаёт готовый файл на колесо.
     *
     * В [wire] лежит то, что реально едет: либо сам файл, либо его поток raw
     * DEFLATE. [rawSize] и [rawCrc] всегда описывают РАСПАКОВАННЫЕ байты —
     * именно их устройство проверяет после распаковки.
     */
    suspend fun upload(
        name: String,
        wire: ByteArray,
        rawSize: Int,
        rawCrc: Long,
        compressed: Boolean,
        onProgress: (Progress) -> Unit
    ) = opLock.withLock {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val hdr = Proto.buf(14 + nameBytes.size)
        hdr.put(if (compressed) 1 else 0)
        hdr.put(0)
        hdr.putInt(rawSize)
        hdr.putInt(wire.size)
        hdr.putInt(rawCrc.toInt())
        hdr.put(nameBytes)
        streamBody(Proto.OP_UP_BEGIN, hdr.array(), Proto.OP_UP_END,
            wire, rawSize.toLong(), onProgress)
    }

    /**
     * Приоритет соединения. Два активных BLE-линка делят один радиомодуль
     * телефона, и «мягкий» (LOW_POWER, интервал ~0.5 с) почти не отбирает эфир у
     * заливки на соседнее колесо: без этого скорость на активное колесо падала
     * с ~110 до ~20 кБ/с. [low] false — вернуть HIGH.
     */
    suspend fun setLowPower(low: Boolean) {
        if (connLowPower == low) return
        val g = gatt ?: return
        try {
            g.requestConnectionPriority(
                if (low) BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                else BluetoothGatt.CONNECTION_PRIORITY_HIGH
            )
        } catch (_: Exception) { return }
        connLowPower = low
        kotlinx.coroutines.delay(150)   // дать параметрам соединения обновиться
    }

    /** Обновление прошивки. Тот же транспорт, другой приёмник на устройстве. */
    suspend fun otaUpdate(image: ByteArray, crc: Long, onProgress: (Progress) -> Unit) =
        opLock.withLock {
            val hdr = Proto.buf(8)
            hdr.putInt(image.size)
            hdr.putInt(crc.toInt())
            streamBody(Proto.OP_OTA_BEGIN, hdr.array(), Proto.OP_OTA_END,
                image, image.size.toLong(), onProgress)
        }

    /**
     * Общее тело для заливки файла и прошивки: они отличаются только опкодами,
     * и расходиться в обработке ошибок им незачем.
     */
    private suspend fun streamBody(
        opBegin: Int, header: ByteArray, opEnd: Int,
        wire: ByteArray, rawSize: Long,
        onProgress: (Progress) -> Unit
    ) {
        val g = gatt ?: throw BleException("not connected")
        val c = chData ?: throw BleException("not connected")

        flowConsumed = 0
        flowStatus = 0
        flowSent = 0
        xferActive = true
        try {
            val ready = UpReady.parse(requestUnlocked(opBegin, header, 12_000))
            // Потолок 512 — предел длины значения атрибута по спецификации.
            // При MTU 517 в посылку влезает 514, и устройство столько и
            // называло, но стек на той стороне отвергает всё, что длиннее 512,
            // а характеристика DATA идёт без подтверждения — отказ до нас не
            // доезжает. Ни одна заливка не проходила: телефон лил байты,
            // которые молча выбрасывались. Ограничение продублировано здесь и
            // в прошивке, чтобы починенной стороны хватало любой одной.
            val chunk = minOf(ready.chunk, payloadSize, 512).coerceAtLeast(20)
            val window = ready.window.coerceAtLeast(chunk.toLong() * 4)

            var off = 0
            var lastReport = 0L
            while (off < wire.size) {
                // Кредитное окно. Ожидание ВЗВОДИТСЯ ДО проверки условия:
                // уведомление FLOW, прилетевшее между проверкой и взводом,
                // разбудило бы предыдущее (уже завершённое) ожидание, а новое
                // не разбудил бы никто — и заливка вставала бы до таймаута,
                // хотя устройство давно готово принимать.
                while (true) {
                    val w = CompletableDeferred<Unit>()
                    flowWaiter = w
                    if (linkDown) throw BleException("connection lost")
                    if (flowStatus != Proto.ST_OK) throw BleException(Proto.statusText(flowStatus))
                    if (off - flowConsumed < window) break
                    withTimeoutOrNull(15_000) { w.await() }
                        ?: throw BleException("the wheel stopped acknowledging data")
                }

                val n = minOf(chunk, wire.size - off)
                // Счётчик отданного поднимаем ДО записи. Он служит фильтром
                // чужих уведомлений (см. dispatchNotify), и если поднять его
                // после, то FLOW про этот самый кусок — а устройство успевает
                // прислать его, пока writeChar ещё возвращается — окажется
                // «про то, чего мы не отправляли», и будет отброшен. Заливка от
                // этого не встанет, но показанная скорость отстаёт от реальной.
                flowSent = (off + n).toLong()
                writeChar(g, c, wire.copyOfRange(off, off + n),
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                off += n

                val now = System.currentTimeMillis()
                if (now - lastReport > 120 || off >= wire.size) {
                    lastReport = now
                    onProgress(Progress(off.toLong(), wire.size.toLong(), flowConsumed, rawSize))
                }
            }
            // Здесь устройство сверяет длину и CRC32 и сообщает вердикт.
            requestUnlocked(opEnd, ByteArray(0), 60_000)
        } catch (e: Exception) {
            // Оборванную передачу надо закрыть и на устройстве, иначе слот
            // останется занятым и следующая заливка получит «busy».
            try { requestUnlocked(Proto.OP_UP_ABORT, ByteArray(0), 5000) } catch (_: Exception) {}
            throw e
        } finally {
            xferActive = false
            flowWaiter = null
        }
    }
}

data class PreviewFrame(val sectors: Int, val radii: Int, val rgb565: ByteArray)
