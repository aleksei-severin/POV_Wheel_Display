package com.povwheel.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.app.NotificationCompat
import com.povwheel.app.convert.FxMask

/**
 * Значок уведомлений приложения — закрученная радуга, как эффект Rainbow на ободе.
 *
 * Маленький значок (строка состояния, кружок в шапке уведомления, значок на
 * иконке приложения) Android рисует одним цветом — по одной лишь прозрачности,
 * поэтому это силуэт: семь полос, закрученных на пол-оборота
 * (res/drawable/ic_stat_rainbow.xml), в кружке цвета [RAINBOW_ACCENT]. Раньше
 * маленьким значком стояла иконка запуска — адаптивная, с непрозрачным фоном, и
 * по прозрачности от неё оставался серый круг с точкой. Цветом радуга видна в
 * большом значке уведомления: та же картинка, что эффект на колесе
 * (FxMask.RainbowDisc), только закрученная на целый оборот — так нагляднее.
 */
internal fun NotificationCompat.Builder.rainbowIcon(): NotificationCompat.Builder =
    setSmallIcon(R.drawable.ic_stat_rainbow)
        .setLargeIcon(rainbowLargeIcon)
        .setColor(RAINBOW_ACCENT)

/** Цвет кружка под маленьким значком — фиолетовый край радуги. */
private const val RAINBOW_ACCENT = 0xFFAF52DE.toInt()

private val rainbowLargeIcon: Bitmap by lazy {
    val px = 192
    val disc = FxMask.RainbowDisc(px).draw(FxMask.rainbowLut, 0, 50)
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        // Тёмная подложка — фон иконки запуска; отверстие ступицы остаётся тёмным.
        c.drawCircle(px / 2f, px / 2f, px / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0F14.toInt() })
        c.drawBitmap(disc, 0f, 0f, null)
    }
}
