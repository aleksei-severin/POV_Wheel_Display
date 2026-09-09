package com.povwheel.app.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Круглое превью-«кино»: несколько кадров, прогнанных через то же полярное
 * преобразование, что и заливка (`DiscRender`). Кадры выбираются из источника
 * равномерно, число ограничено — превью не обязано быть той же длины, что
 * анимация на ободе, ему хватает показать, что она вообще движется.
 *
 * Один кадр — обычная статичная миниатюра, `animated` тогда `false`.
 */
data class PreviewClip(val frames: List<Bitmap>, val delayMs: Int) {
    val animated: Boolean get() = frames.size > 1

    fun recycle() = frames.forEach { if (!it.isRecycled) it.recycle() }
}

/**
 * Кэш готовых превью на диске приложения. Фото-пикер Android отдаёт доступ к
 * выбранному файлу только на время жизни процесса, поэтому «взять исходник с
 * телефона» второй раз (после перезапуска) уже нельзя. Вместо исходника при
 * заливке сохраняется вот этот компактный рендер — спрайт-лист PNG плюс
 * маленький заголовок, ~0.5 МБ на файл. Библиотека потом крутит его локально, не
 * дёргая кадры по BLE.
 */
object PreviewClips {

    // Сторона диска в кэше и потолок кадров. Миниатюра в библиотеке — 48 dp, так
    // что больше не нужно; 16 кадров достаточно, чтобы движение читалось.
    const val CACHE_PX = 132
    const val CACHE_FRAMES = 16

    // Превью выбранного файла на экране загрузки крупнее и чуть длиннее.
    const val UPLOAD_PX = 196
    const val UPLOAD_FRAMES = 20

    private const val MAGIC = 0x50564331          // "PVC1"

    fun fileFor(dir: File, deviceName: String) = File(dir, deviceName + ".pvc")

    /**
     * Пишет клип одним спрайт-листом: кадры уложены вертикально в столбец.
     * Запись во временный файл с переименованием — оборванная на середине заливка
     * не оставит битого превью.
     */
    fun save(dst: File, clip: PreviewClip) {
        if (clip.frames.isEmpty()) return
        val w = clip.frames[0].width
        val h = clip.frames[0].height
        val n = clip.frames.size
        // RGB_565: угловые прозрачные поля диска всё равно обрезаются круглой
        // маской и в библиотеке, и на экране загрузки, а память — вдвое меньше.
        val sheet = Bitmap.createBitmap(w, h * n, Bitmap.Config.RGB_565)
        val c = Canvas(sheet)
        clip.frames.forEachIndexed { i, f -> c.drawBitmap(f, 0f, (i * h).toFloat(), null) }
        dst.parentFile?.mkdirs()
        val tmp = File(dst.path + ".tmp")
        try {
            DataOutputStream(tmp.outputStream().buffered()).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(n)
                o.writeInt(clip.delayMs)
                o.writeInt(w)
                o.writeInt(h)
                sheet.compress(Bitmap.CompressFormat.PNG, 100, o)
            }
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
        } catch (e: Exception) {
            tmp.delete()
        } finally {
            sheet.recycle()
        }
    }

    fun load(src: File): PreviewClip? {
        if (!src.exists()) return null
        return try {
            DataInputStream(src.inputStream().buffered()).use { i ->
                if (i.readInt() != MAGIC) return null
                val n = i.readInt()
                val delay = i.readInt()
                val w = i.readInt()
                val h = i.readInt()
                if (n <= 0 || w <= 0 || h <= 0 || n > 4096) return null
                val opt = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val sheet = BitmapFactory.decodeStream(i, null, opt) ?: return null
                val frames = ArrayList<Bitmap>(n)
                for (k in 0 until n) {
                    if ((k + 1) * h > sheet.height) break
                    frames.add(Bitmap.createBitmap(sheet, 0, k * h, w, h))
                }
                sheet.recycle()
                if (frames.isEmpty()) null else PreviewClip(frames, delay.coerceIn(20, 500))
            }
        } catch (e: Exception) {
            null
        }
    }
}
