package com.povwheel.app.hall

/**
 * Разбор записей кольца лога Холла (формат — include/hall_log.h): 32 бита на запись,
 * [31:28] тип, [27:24] аргумент, [23:0] Δt в мкс от предыдущего события; HLOG_EXT несёт
 * старшие биты Δt следующей записи, HLOG_NOP — выравнивание без времени.
 *
 * Упакованные записи (P4 — четыре события по 7 бит, P2 — два по 14) несут только
 * поправку q к предсказанию: шаг = датчик[-1] − датчик[-2] (mod 6), K = шаг ? 6 : 1,
 * датчик = датчик[-1] + шаг, Δt = Δt[-K] + q·[PACK_US]. Правило то же, что у кодировщика
 * в hall_log.cpp, — менять только вместе. Предысторию разборщик не сбрасывает: где ей
 * не на что опереться (контрольная точка, начало блока на флеше), кодировщик пишет
 * полные записи.
 *
 * Отдельно от [HallArchive] — без Android-зависимостей, чтобы проверять на ПК против
 * того же кодировщика, что в прошивке.
 */
object HallDecode {
    const val T_HALL = 0
    const val T_LIT = 1
    const val T_DARK = 2
    const val T_P4 = 3
    const val T_P2 = 4
    const val T_NOP = 14
    const val T_EXT = 15

    /** Шаг поправки в упакованных записях, мкс (HLOG_PACK_US). */
    const val PACK_US = 16L

    private const val HIST = 6

    /**
     * [entries] с номера [seq0]; [t0] — время esp_timer, мкс, ДО первой записи (страница
     * всегда начинается на контрольной точке). [emit] получает только события
     * (HALL/LIT/DARK): номер записи, время, тип, аргумент. В одной упакованной записи —
     * несколько событий с одним номером и разным временем.
     */
    fun decode(seq0: Long, t0: Long, entries: IntArray, count: Int,
               emit: (seq: Long, t: Long, type: Int, arg: Int) -> Unit) {
        var t = t0
        var hi = 0L
        // Последние события Холла: датчик и Δt, [0] — самое свежее.
        val sens = IntArray(HIST)
        val gap = LongArray(HIST)
        var n = 0
        fun remember(s: Int, g: Long) {
            for (k in HIST - 1 downTo 1) { sens[k] = sens[k - 1]; gap[k] = gap[k - 1] }
            sens[0] = s; gap[0] = g
            if (n < HIST) n++
        }
        for (i in 0 until count) {
            val e = entries[i]
            val type = (e ushr 28) and 0xF
            when (type) {
                T_EXT -> { hi = (e and 0xFFFFFF).toLong(); continue }
                T_NOP -> continue
                T_P4, T_P2 -> {
                    hi = 0
                    val wide = type == T_P2
                    for (k in 0 until if (wide) 2 else 4) {
                        // Сдвиг влево ставит знак слота в бит 31, арифметический вправо — расширяет.
                        val q = if (wide) (e shl (4 + 14 * k)) shr 18 else (e shl (4 + 7 * k)) shr 25
                        if (q == (if (wide) -8192 else -64)) break      // пустой хвост
                        // Без двух предыдущих событий шаг не узнать: страница начата не
                        // там, где кодировщик это допускает, — дальше время не восстановить.
                        if (n < 2) return
                        val step = Math.floorMod(sens[0] - sens[1], 6)
                        val kk = if (step == 0) 1 else 6
                        if (n < kk) return
                        val s = (sens[0] + step) % 6
                        val g = gap[kk - 1] + q * PACK_US
                        t += g
                        emit(seq0 + i, t, T_HALL, s)
                        remember(s, g)
                    }
                }
                else -> {
                    val dt = (hi shl 24) or (e and 0xFFFFFF).toLong()
                    hi = 0
                    t += dt
                    val arg = (e ushr 24) and 0xF
                    if (type == T_HALL || type == T_LIT || type == T_DARK) emit(seq0 + i, t, type, arg)
                    if (type == T_HALL) remember(arg, dt)
                }
            }
        }
    }
}
