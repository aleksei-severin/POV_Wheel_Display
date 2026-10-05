package com.povwheel.app.hall

/**
 * Разбор записей кольца лога Холла (формат — include/hall_log.h): 32 бита на запись,
 * [31:28] тип, [27:24] аргумент, [23:0] Δt в мкс от предыдущего события; HLOG_EXT несёт
 * старшие биты Δt следующей записи, HLOG_NOP — выравнивание без времени.
 *
 * Отдельно от [HallArchive] — без Android-зависимостей, чтобы проверять на ПК против
 * того же кодировщика, что в прошивке.
 */
object HallDecode {
    const val T_HALL = 0
    const val T_LIT = 1
    const val T_DARK = 2
    const val T_NOP = 14
    const val T_EXT = 15

    /**
     * [entries] с номера [seq0]; [t0] — время esp_timer, мкс, ДО первой записи (страница
     * всегда начинается на контрольной точке). [emit] получает только события
     * (HALL/LIT/DARK): номер записи, время, тип, аргумент.
     */
    inline fun decode(seq0: Long, t0: Long, entries: IntArray, count: Int,
                      emit: (seq: Long, t: Long, type: Int, arg: Int) -> Unit) {
        var t = t0
        var hi = 0L
        for (i in 0 until count) {
            val e = entries[i]
            val type = (e ushr 28) and 0xF
            val dt = (e and 0xFFFFFF).toLong()
            when (type) {
                T_EXT -> { hi = dt; continue }
                T_NOP -> continue
            }
            t += (hi shl 24) or dt
            hi = 0
            if (type == T_HALL || type == T_LIT || type == T_DARK) emit(seq0 + i, t, type, (e ushr 24) and 0xF)
        }
    }
}
