package com.povwheel.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Протокол обмена с колесом. Файл — зеркало заголовка прошивки include/povble.h:
 * каждая структура ниже разложена байт в байт так же, little-endian, без
 * выравнивания (в прошивке стоит pack(1)).
 *
 * Всё двоичное, а не JSON, намеренно: в одну посылку BLE влезает 244…514
 * полезных байт, и тратить их на имена полей значит платить лишним round-trip
 * на каждый чих.
 */
object Proto {
    const val VERSION = 1

    private fun u(s: String): UUID = UUID.fromString(s)
    val SVC  = u("5f6b1000-9c4e-4a7d-b3f2-1d8e6a5c4b30")
    val CMD  = u("5f6b1001-9c4e-4a7d-b3f2-1d8e6a5c4b30")
    val RSP  = u("5f6b1002-9c4e-4a7d-b3f2-1d8e6a5c4b30")
    val DATA = u("5f6b1003-9c4e-4a7d-b3f2-1d8e6a5c4b30")
    val FLOW = u("5f6b1004-9c4e-4a7d-b3f2-1d8e6a5c4b30")
    val TELE = u("5f6b1005-9c4e-4a7d-b3f2-1d8e6a5c4b30")

    // Коды операций, в ногу с enum PovOp
    const val OP_HELLO     = 0x01
    const val OP_GET_SET   = 0x02
    const val OP_SET_SET   = 0x03
    const val OP_SAVE      = 0x04
    const val OP_LIST      = 0x05
    const val OP_PLAY      = 0x06
    const val OP_STOP      = 0x07
    const val OP_DELETE    = 0x08
    const val OP_EFFECT    = 0x09
    const val OP_ALBUM     = 0x0A
    const val OP_TELE      = 0x0B
    const val OP_PREVIEW   = 0x0C
    const val OP_SETTIME   = 0x0D
    const val OP_FSINFO    = 0x0E
    const val OP_LOGS      = 0x0F
    const val OP_UP_BEGIN  = 0x10
    const val OP_UP_END    = 0x11
    const val OP_UP_ABORT  = 0x12
    const val OP_OTA_BEGIN = 0x13
    const val OP_OTA_END   = 0x14
    const val OP_REBOOT    = 0x15
    const val OP_WIFI      = 0x16
    const val OP_SLEEP     = 0x17
    const val OP_FRAG      = 0x18
    const val OP_SETNAME   = 0x19
    const val OP_POWEROFF  = 0x1A   // транспортный режим — будит только удержание кнопки

    /**
     * Предел имени — столько же, сколько держит PovHello.name вместе с
     * завершающим нулём. Только ASCII: имя уезжает в рекламный пакет побайтно,
     * и кириллица там обрежется посреди буквы.
     */
    const val NAME_MAX = 19
    fun nameOk(n: String) = n.isNotEmpty() && n.length <= NAME_MAX &&
        n.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    const val ST_OK = 0   // остальные коды см. PovStatus в прошивке

    /**
     * Текст кода состояния — ВСЕГДА вместе с самим числом.
     *
     * Без числа код 8 печатался словом «failed», и строка на экране получалась
     * «name.bin — failed: failed»: ровно то, что пользователь и увидел, когда
     * ни одна заливка не проходила. Три совершенно разные причины (сторож
     * молчания, неудачное открытие файла, ошибка транспорта) выглядели
     * одинаково и не давали зацепиться вообще ни за что.
     */
    fun statusText(s: Int): String = when (s) {
        0 -> "OK"
        1 -> "unknown command (1)"
        2 -> "bad argument (2)"
        3 -> "device busy (3)"
        4 -> "not found (4)"
        5 -> "not enough space (5)"
        6 -> "checksum mismatch (6)"
        7 -> "the wheel received nothing at all (7) — chunks are being rejected"
        8 -> "the wheel reported a failure (8)"
        9 -> "out of memory on the wheel (9)"
        else -> "error " + s
    }

    const val FEAT_DEFLATE   = 0x0001
    const val FEAT_OTA       = 0x0002
    const val FEAT_PREVIEW   = 0x0004
    const val FEAT_WIFI      = 0x0008
    const val FEAT_ALBUM_SEL = 0x0010   // OP_ALBUM понимает отбор файлов для слайдшоу

    fun buf(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    fun wrap(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    /** Читает поле фиксированной длины, дополненное нулями (ASCII). */
    fun readStr(p: ByteBuffer, n: Int): String {
        val a = ByteArray(n)
        p.get(a)
        var end = a.indexOf(0)
        if (end < 0) end = n
        return String(a, 0, end, Charsets.US_ASCII)
    }
}

/** Ответ на OP_HELLO, 48 байт. */
data class Hello(
    val proto: Int, val arms: Int, val ledsPerSide: Int, val palColors: Int,
    val sectors: Int, val frameStride: Int, val mtu: Int, val features: Int,
    val uptimeS: Long, val name: String, val fw: String
) {
    val hasDeflate  get() = features and Proto.FEAT_DEFLATE != 0
    val hasOta      get() = features and Proto.FEAT_OTA != 0
    val hasPreview  get() = features and Proto.FEAT_PREVIEW != 0
    val hasAlbumSel get() = features and Proto.FEAT_ALBUM_SEL != 0

    companion object {
        const val SIZE = 48
        fun parse(b: ByteArray): Hello {
            val p = Proto.wrap(b)
            val proto = p.get().toInt() and 0xFF
            val arms = p.get().toInt() and 0xFF
            val lps = p.get().toInt() and 0xFF
            var pal = p.get().toInt() and 0xFF
            if (pal == 0) pal = 256
            val sectors = p.short.toInt() and 0xFFFF
            val stride = p.short.toInt() and 0xFFFF
            val mtu = p.short.toInt() and 0xFFFF
            val feat = p.short.toInt() and 0xFFFF
            val up = p.int.toLong() and 0xFFFFFFFFL
            val name = Proto.readStr(p, 20)
            val fw = Proto.readStr(p, 12)
            return Hello(proto, arms, lps, pal, sectors, stride, mtu, feat, up, name, fw)
        }
    }
}

/**
 * Блок настроек, 39 байт, симметричный на чтение и запись.
 *
 * Всё, что имеет побочные эффекты (эффект, слайдшоу, воспроизведение), сюда
 * намеренно не входит: такая смена ждёт, пока рендер отпустит буфер кадра,
 * поэтому в прошивке у неё свои команды.
 */
data class Settings(
    var bmin: Int = 1, var bmax: Int = 31,
    var angle: Int = 93,
    var gammaX100: Int = 250, var satX100: Int = 150, var contrastX10: Int = 50,
    var circ: Int = 2355, var armReverse: Int = 0,
    var ablX10: Int = 1000,
    var rgX10: Int = 1000, var ggX10: Int = 800, var bgX10: Int = 1000,
    var rpmOn: Int = 120, var rpmOff: Int = 100
) {
    fun pack(): ByteArray {
        val b = Proto.buf(SIZE)
        b.put(bmin.toByte()); b.put(bmax.toByte())
        b.putShort(angle.toShort())
        b.putShort(gammaX100.toShort()); b.putShort(satX100.toShort())
        b.putShort(contrastX10.toShort()); b.putShort(circ.toShort())
        b.put(armReverse.toByte()); b.put(0)
        b.putShort(ablX10.toShort())
        b.putShort(rgX10.toShort()); b.putShort(ggX10.toShort()); b.putShort(bgX10.toShort())
        b.putShort(rpmOn.toShort()); b.putShort(rpmOff.toShort())
        return b.array()
    }

    companion object {
        const val SIZE = 26
        fun parse(a: ByteArray): Settings {
            val p = Proto.wrap(a)
            val s = Settings()
            s.bmin = p.get().toInt() and 0xFF
            s.bmax = p.get().toInt() and 0xFF
            s.angle = p.short.toInt()
            s.gammaX100 = p.short.toInt() and 0xFFFF
            s.satX100 = p.short.toInt() and 0xFFFF
            s.contrastX10 = p.short.toInt() and 0xFFFF
            s.circ = p.short.toInt() and 0xFFFF
            s.armReverse = p.get().toInt() and 0xFF
            p.get()
            s.ablX10 = p.short.toInt() and 0xFFFF
            s.rgX10 = p.short.toInt() and 0xFFFF
            s.ggX10 = p.short.toInt() and 0xFFFF
            s.bgX10 = p.short.toInt() and 0xFFFF
            s.rpmOn = p.short.toInt() and 0xFFFF
            s.rpmOff = p.short.toInt() and 0xFFFF
            return s
        }
    }
}

/** Телеметрия, 80 байт, приходит уведомлением дважды в секунду. */
data class Tele(
    val rpm: Float = 0f, val dir: Int = 0, val pwr: Int = 0,
    val stepDeg: Float = 0f, val fillUs: Long = 0, val kmh: Float = 0f,
    val vbatMv: Int = 0, val vusbMv: Int = 0, val ocvMv: Int = 0,
    val sagMv: Int = 0, val riseMv: Int = 0,
    val soc: Int = 0, val chg: Int = 0, val usb: Boolean = false,
    val lux: Int = 0, val bri: Int = 0, val effBri: Int = 0,
    val ablRms: Int = 0, val ablCap: Int = 100, val cutoff: Boolean = false,
    val effect: Int = 0, val play: Boolean = false, val slideshow: Boolean = false,
    val framesTotal: Int = 0, val wifi: Boolean = false,
    val stateVer: Long = 0, val fileVer: Long = 0, val epoch: Long = 0,
    val file: String = "",
    /** Интервал слайдшоу на устройстве, секунды. */
    val slideSecs: Int = 10
) {
    companion object {
        const val SIZE = 82
        fun parse(a: ByteArray): Tele {
            val p = Proto.wrap(a)
            val rpm = (p.short.toInt() and 0xFFFF) / 10f
            val dir = p.get().toInt()
            val pwr = p.get().toInt() and 0xFF
            val step = (p.short.toInt() and 0xFFFF) / 100f
            val fill = p.int.toLong() and 0xFFFFFFFFL
            val kmh = (p.short.toInt() and 0xFFFF) / 10f
            val vbat = p.short.toInt()
            val vusb = p.short.toInt()
            val ocv = p.short.toInt()
            val sag = p.short.toInt()
            val rise = p.short.toInt() and 0xFFFF
            val soc = p.get().toInt() and 0xFF
            val chg = p.get().toInt() and 0xFF
            val usb = (p.get().toInt() and 0xFF) != 0
            val lux = (p.get().toInt() and 0xFF) * 4
            val bri = p.get().toInt() and 0xFF
            val eb = p.get().toInt() and 0xFF
            val rms = p.get().toInt() and 0xFF
            val cap = p.get().toInt() and 0xFF
            val cut = (p.get().toInt() and 0xFF) != 0
            val eff = p.get().toInt() and 0xFF
            val play = (p.get().toInt() and 0xFF) != 0
            val slide = (p.get().toInt() and 0xFF) != 0
            val ft = p.get().toInt() and 0xFF
            val wifi = (p.get().toInt() and 0xFF) != 0
            val sv = p.int.toLong() and 0xFFFFFFFFL
            val fv = p.int.toLong() and 0xFFFFFFFFL
            val ep = p.int.toLong() and 0xFFFFFFFFL
            val file = Proto.readStr(p, 32)
            val slideSecs = p.short.toInt() and 0xFFFF
            return Tele(rpm, dir, pwr, step, fill, kmh, vbat, vusb, ocv, sag, rise,
                soc, chg, usb, lux, bri, eb, rms, cap, cut, eff, play, slide, ft, wifi,
                sv, fv, ep, file, slideSecs)
        }
    }
}

data class FsInfo(
    val total: Long = 0, val used: Long = 0, val free: Long = 0,
    val psramFree: Long = 0, val frameStride: Int = 16608, val maxFrames: Int = 240
) {
    companion object {
        const val SIZE = 20
        fun parse(a: ByteArray): FsInfo {
            val p = Proto.wrap(a)
            val t = p.int.toLong() and 0xFFFFFFFFL
            val u = p.int.toLong() and 0xFFFFFFFFL
            val f = p.int.toLong() and 0xFFFFFFFFL
            val ps = p.int.toLong() and 0xFFFFFFFFL
            val fs = p.short.toInt() and 0xFFFF
            val mf = p.short.toInt() and 0xFFFF
            return FsInfo(t, u, f, ps, fs, mf)
        }
    }
}

/** Одна запись из ответа OP_LIST. */
data class DevFile(val name: String, val size: Long) {
    /** Убирает расширение .bin и префикс типа — как делал веб-интерфейс. */
    val pretty: String
        get() {
            var n = name.removeSuffix(".bin")
            for (p in listOf("img_", "gif_", "vid_", "anm_")) {
                if (n.startsWith(p)) { n = n.substring(4); break }
            }
            return n
        }

    val kind: String
        get() = when {
            name.startsWith("vid_") -> "VIDEO"
            name.startsWith("gif_") -> "GIF"
            name.startsWith("anm_") -> "WEBP"
            else -> "IMAGE"
        }

    /** Число кадров, выведенное из размера: заголовок 8 байт плюс N × 16608. */
    val frames: Int get() = if (size > 8) ((size - 8) / 16608L).toInt() else 0
}

data class UpReady(val chunk: Int, val window: Long) {
    companion object {
        fun parse(a: ByteArray): UpReady {
            val p = Proto.wrap(a)
            val c = p.short.toInt() and 0xFFFF
            val w = p.int.toLong() and 0xFFFFFFFFL
            return UpReady(c, w)
        }
    }
}

data class Flow(val consumed: Long, val written: Long, val status: Int) {
    companion object {
        fun parse(a: ByteArray): Flow {
            val p = Proto.wrap(a)
            val c = p.int.toLong() and 0xFFFFFFFFL
            val w = p.int.toLong() and 0xFFFFFFFFL
            val s = if (p.hasRemaining()) p.get().toInt() and 0xFF else 0
            return Flow(c, w, s)
        }
    }
}
