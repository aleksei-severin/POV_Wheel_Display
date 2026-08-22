package com.povwheel.app.convert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF

/**
 * Вписывание источника в квадратный рабочий холст. Одна функция для картинок,
 * кадров GIF, кадров WebP и видео, ровно как в браузере: кадрирование не должно
 * зависеть от формата источника.
 */
object Fit {
    const val CROP = 0   // центральный квадрат
    const val FIT = 1    // целиком, с полями
}

object Bitmaps {
    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = false
    }

    /**
     * Рисует [src] в квадратный битмап размера [size].
     *
     * CROP берёт центральный квадрат (у широкого кадра съедаются края);
     * FIT сохраняет кадр целиком и добавляет поля. Нетронутая область остаётся
     * прозрачной, а полярная выборка читает её как чёрное — так же, как
     * очищенный холст в браузере.
     *
     * Смещение задаётся матрицей, а не целочисленным прямоугольником-источником:
     * у кадра с нечётной разницей сторон центр приходится на половину пикселя,
     * и округление сдвинуло бы картинку относительно того, что делал браузер.
     */
    fun drawSquare(src: Bitmap, dst: Bitmap, mode: Int) {
        val size = dst.width
        val c = Canvas(dst)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return

        val scale: Double
        val tx: Double
        val ty: Double
        if (mode == Fit.FIT) {
            val k = minOf(size.toDouble() / w, size.toDouble() / h)
            scale = k
            tx = (size - w * k) / 2.0
            ty = (size - h * k) / 2.0
        } else {
            val sq = minOf(w, h).toDouble()
            val k = size / sq
            scale = k
            tx = -((w - sq) / 2.0) * k
            ty = -((h - sq) / 2.0) * k
        }

        // Масштабирование в Android — обычное билинейное: четыре отсчёта, и при
        // сжатии кадра 1080p до 400 px это даёт заметный алиасинг. В браузере
        // стоял качественный многоотсчётный фильтр. Последовательное деление
        // пополам приближает площадное усреднение, и детали обода не
        // превращаются в рябь.
        var cur = src
        var owned = false
        val targetW = w * scale
        val targetH = h * scale
        while (cur.width / 2 >= targetW && cur.height / 2 >= targetH &&
               cur.width > 2 && cur.height > 2) {
            val nw = cur.width / 2
            val nh = cur.height / 2
            val half = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            Canvas(half).drawBitmap(
                cur, null, RectF(0f, 0f, nw.toFloat(), nh.toFloat()), paint
            )
            if (owned) cur.recycle()
            cur = half
            owned = true
        }

        // Уменьшенная копия могла потерять полпикселя на нечётной стороне,
        // поэтому масштаб берём из её фактических размеров, а не из числа делений.
        val m = Matrix()
        m.setScale(
            (scale / (cur.width.toDouble() / w)).toFloat(),
            (scale / (cur.height.toDouble() / h)).toFloat()
        )
        m.postTranslate(tx.toFloat(), ty.toFloat())
        c.drawBitmap(cur, m, paint)
        if (owned) cur.recycle()
    }

    fun square(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    /**
     * Разворачивает картинку по метке EXIF. Браузер делает это сам (по
     * умолчанию image-orientation: from-image), а BitmapFactory — нет, и снимок
     * с телефона приезжал бы на колесо лежащим на боку.
     */
    fun applyExif(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(270f)
            else -> return bmp
        }
        return try {
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            out
        } catch (e: Exception) {
            bmp
        }
    }
}
