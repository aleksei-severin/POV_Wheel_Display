package com.povwheel.app.povvideo

import kotlin.math.max

/**
 * Время ролика → реальное время: кусочно-линейное. Отрезок i начинается с [at] [i] мкс от
 * первого кадра (at[0] = 0) и идёт в [rate] [i] раз медленнее реального времени.
 *
 * Обычная съёмка — один отрезок ×1, slow motion целиком (Samsung пишет частоту съёмки в
 * метаданные, GoPro — нет) — один отрезок ×k. Slo-mo, отданное с iPhone через «поделиться»
 * (мессенджер, экспорт), приходит «запечённым» в 30 к/с: первые и последние секунды идут в
 * реальном времени, середина — ×4 (120 к/с) или ×8 (240 к/с), и переключение мгновенное
 * (видно и по гашениям слайдшоу, и по фазе лучей в кадре). Метки кадров у такого файла
 * ровные — по ним замедление не увидеть; где оно, подсказывает звук ([SlowMoAudio]).
 * Прежде весь ролик считался одним замедлением: IMG_9082 (×1, ×4, ×1) рендерился как ×10
 * и выходил в разы быстрее, IMG_5297 — как ×1, и склеивалась лишь часть.
 */
class TimeMap private constructor(private val at: DoubleArray, private val rate: DoubleArray) {

    /** Реальное время начала каждого отрезка, мкс от первого кадра. */
    private val real0 = DoubleArray(at.size)

    init {
        for (i in 1 until at.size) real0[i] = real0[i - 1] + (at[i] - at[i - 1]) / rate[i - 1]
    }

    /** Самое сильное замедление; у постоянного — оно и есть. */
    val slow: Double = rate.max()

    /** Весь ролик с одним замедлением. */
    val constant: Boolean get() = at.size == 1

    /** Начало замедленного участка, мкс файла: null — замедлен с первого кадра (или постоянное). */
    val slowFrom: Double? get() = if (!constant && rate[0] == 1.0 && slow > 1) at[1] else null

    /** Конец замедленного участка, мкс файла: null — до последнего кадра (или постоянное). */
    val slowTo: Double? get() = if (!constant && rate[rate.size - 1] == 1.0 && slow > 1) at[at.size - 1] else null

    private fun seg(fileUs: Double): Int {
        var i = 0
        while (i + 1 < at.size && fileUs >= at[i + 1]) i++
        return i
    }

    /** Реальное время (мкс от первого кадра) момента файла [fileUs]; вне ролика — продолжение крайних отрезков. */
    fun real(fileUs: Double): Double {
        val i = seg(fileUs)
        return real0[i] + (fileUs - at[i]) / rate[i]
    }

    /** Обратное к [real]. */
    fun file(realUs: Double): Double {
        var i = 0
        while (i + 1 < at.size && realUs >= real0[i + 1]) i++
        return at[i] + (realUs - real0[i]) * rate[i]
    }

    /** Замедление в момент файла [fileUs]. */
    fun rate(fileUs: Double): Double = rate[seg(fileUs)]

    companion object {
        fun constant(slow: Double) = TimeMap(doubleArrayOf(0.0), doubleArrayOf(slow))

        /**
         * ×1 до [from], ×[slow] до [to], дальше снова ×1 (мкс файла). null — этой части
         * нет: замедление с первого кадра или до последнего.
         */
        fun ramp(from: Double?, to: Double?, slow: Double): TimeMap {
            val a = ArrayList<Double>(3)
            val r = ArrayList<Double>(3)
            if (from != null && from > 0) { a.add(0.0); r.add(1.0) }
            a.add(if (from != null) max(0.0, from) else 0.0); r.add(slow)
            if (to != null && to > a[a.size - 1]) { a.add(to); r.add(1.0) }
            if (a.size == 1) return constant(slow)
            a[0] = 0.0
            return TimeMap(a.toDoubleArray(), r.toDoubleArray())
        }
    }
}
