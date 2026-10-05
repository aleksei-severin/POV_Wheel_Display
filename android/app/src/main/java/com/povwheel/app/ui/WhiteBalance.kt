package com.povwheel.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.roundToInt

/**
 * Баланс белого одной цветовой температурой вместо трёх усилений R/G/B.
 *
 * Усиления — множители линейного света (ШИМ диода), поэтому и кривая считается в
 * линейном свете: цвет чёрного тела при температуре K (локус Планка по
 * аппроксимации Kim et al., 1667…25000 K) → XYZ → линейный sRGB, делённый на него
 * же при 6500 K. Этот множитель накладывается на заводской белый [BASE] — то есть
 * поправка на зелёный оттенок самих диодов (G 80 %) сохраняется при любой
 * температуре, двигается только ось «тёплый ↔ холодный». Старший канал всегда
 * 100 %: ползунок меняет оттенок, а не яркость.
 *
 * Чего одним ползунком не сделать — сдвиг по оси «зелёный ↔ пурпурный». Для
 * этого в веб-интерфейсе остались три ползунка; выставленное там приложение
 * показывает как «Custom».
 */
internal object WhiteBalance {
    const val K_MIN = 3000
    const val K_MAX = 10000
    const val K_STEP = 250
    const val K_NEUTRAL = 6500

    /** Заводской белый (Restore defaults), ‰ как в Settings.rgX10…bgX10. */
    val BASE = intArrayOf(1000, 800, 1000)

    // Подписи и цвета градиентной дорожки.
    val WARM_COLOR = Color(0xFFFF9A3C)
    val NEUTRAL_COLOR = Color(0xFFF4F4F4)
    val COOL_COLOR = Color(0xFF6FA8FF)

    /** Цвет точки на бегунке — тот же градиент, что у дорожки, в положении [k]. */
    fun tint(k: Int): Color {
        val f = (k - K_MIN).toFloat() / (K_MAX - K_MIN)
        return if (f < 0.5f) lerp(WARM_COLOR, NEUTRAL_COLOR, f * 2f)
               else lerp(NEUTRAL_COLOR, COOL_COLOR, f * 2f - 1f)
    }

    /** Линейный sRGB чёрного тела при температуре [k], яркость Y = 1. */
    private fun planck(k: Double): DoubleArray {
        val t2 = k * k
        val t3 = t2 * k
        val x = if (k <= 4000) -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / k + 0.179910
                else -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / k + 0.240390
        val y = when {
            k <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            k <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        val cx = x / y
        val cz = (1 - x - y) / y
        val r = 3.2406 * cx - 1.5372 - 0.4986 * cz
        val g = -0.9689 * cx + 1.8758 + 0.0415 * cz
        val b = 0.0557 * cx - 0.2040 + 1.0570 * cz
        return doubleArrayOf(maxOf(r, 0.0), maxOf(g, 0.0), maxOf(b, 0.0))
    }

    private val ref = planck(K_NEUTRAL.toDouble())

    // Все положения ползунка считаются один раз: их всего 29.
    private val table: List<IntArray> = (K_MIN..K_MAX step K_STEP).map { k ->
        val p = planck(k.toDouble())
        val m = DoubleArray(3) { BASE[it] * p[it] / ref[it] }
        val top = m.max()
        IntArray(3) { (m[it] / top * 1000).roundToInt().coerceIn(0, 1000) }
    }

    /** Положение ползунка → ближайшее деление. */
    fun snap(k: Float): Int =
        (K_MIN + ((k - K_MIN) / K_STEP).roundToInt() * K_STEP).coerceIn(K_MIN, K_MAX)

    /** Усиления R/G/B (‰) для температуры [k] (кратной [K_STEP]). */
    fun gains(k: Int): IntArray = table[(snap(k.toFloat()) - K_MIN) / K_STEP]

    class Fit(val kelvin: Int, val exact: Boolean)

    /**
     * Какой температуре соответствуют текущие усиления. Сравниваются оттенки, не
     * яркость: усиления сначала нормируются по старшему каналу. [Fit.exact] —
     * лежат ли они на кривой (с точностью ~1.5 % на канал); нет — выставлены
     * вручную, ползунок встаёт на ближайшую температуру.
     */
    fun kelvinOf(r: Int, g: Int, b: Int): Fit {
        val top = maxOf(r, g, b)
        if (top <= 0) return Fit(K_NEUTRAL, false)
        val cur = doubleArrayOf(r * 1000.0 / top, g * 1000.0 / top, b * 1000.0 / top)
        var best = 0
        var bestErr = Double.MAX_VALUE
        table.forEachIndexed { i, t ->
            val e = (0..2).sumOf { (t[it] - cur[it]) * (t[it] - cur[it]) }
            if (e < bestErr) { bestErr = e; best = i }
        }
        return Fit(K_MIN + best * K_STEP, bestErr <= 3 * 15.0 * 15.0)
    }
}
