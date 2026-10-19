package com.povwheel.app.povvideo

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * GLES 3.0 для рендера POV-видео: кольцо последних кадров — слои текстурных массивов,
 * склейка окна — один проход шейдера по этим слоям, результат — в поверхность
 * видеокодировщика.
 *
 * Кольцо хранит кадры в YUV 4:2:0 (BT.709): яркость — R8 в полном разрешении, цвет —
 * RG8 в половинном. 1.5 байта на пиксель вместо 4 у RGBA — так в память видеокарты
 * помещается кольцо в разрешении исходника, хоть 4K. Результат кодируется в тот же
 * 4:2:0, так что потерь нет; шейдер склейки переводит кадры обратно в RGB до максимума
 * по каналам.
 *
 * Ночная обработка ([allocate] с night): результат склейки — в RGBA16F с мипмапами, и
 * перед кодировщиком он проходит локальный тон-маппинг (см. FS_TONE): тёмное вокруг
 * колеса поднимается, светлое (дисплей, фонари) остаётся как есть. Тон-маппинг идёт до
 * 8 бит — поднятые в разы тени иначе пошли бы полосами. Без ночной обработки результат,
 * как и прежде, RGBA8 и копируется в кодировщик как есть.
 *
 * Ориентация: кадр декодера рисуется через матрицу SurfaceTexture (в ней кроп и
 * вертикальный флип видеокадра), так что на поверхности кодировщика он стоит так же, как
 * в «сыром» потоке (без поворота из метаданных). Поворот потом дописывается в контейнер
 * подсказкой ориентации — без пересчёта пикселей.
 *
 * Всё, кроме onFrameAvailable, вызывается из одного потока — того, что создал объект.
 */
internal class PovGl {
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var win: EGLSurface = EGL14.EGL_NO_SURFACE

    // ---- вход: кадры декодера ----
    private val oesTex: Int
    private val surfaceTexture: SurfaceTexture
    /** Сюда декодер отдаёт кадры. */
    val decoderSurface: Surface
    private val cbThread = HandlerThread("pov-frame").apply { start() }
    private val frameLock = Object()
    private var frameReady = false
    private val stMatrix = FloatArray(16)

    // ---- программы ----
    private val progCopy: Int
    private val progStoreY: Int
    private val progStoreUV: Int
    private val progPresent: Int
    private val progBlend: Int
    // Программы ночной обработки: 0 — не собрались на этом драйвере (тогда её просто нет,
    // обычный рендер от них не зависит).
    private var progBox = 0
    private var progTone = 0
    private val aPosCopy: Int
    private val uStCopy: Int
    private val aPosY: Int
    private val uStY: Int
    private val aPosUV: Int
    private val uStUV: Int
    private val aPosPresent: Int
    private val aPosBlend: Int
    private val uCap: Int
    private val uJ0: Int
    private val uN: Int
    private val uNight: Int
    private val uLo: Int
    private val uHi: Int
    private val uW: Int
    private var aPosBox = 0
    private var uBoxMode = 0
    private var uBoxLod = 0
    private var uBoxDir = 0
    private var uBoxR = 0
    private var aPosTone = 0

    // ---- текстуры ----
    var w = 0; private set
    var h = 0; private set
    var cap = 0; private set
    /** Ночная обработка включена (запрошена в [allocate] и видеокарта её умеет). */
    var night = false; private set
    private var uvW = 0
    private var uvH = 0
    private var ringY = 0
    private var ringUV = 0
    private var resTex = 0
    // База тон-маппинга: уровень мипмапа [lod] результата, текстуры RG16F его размера.
    private var lowA = 0
    private var lowB = 0
    private var lod = 0
    private var lowW = 0
    private var lowH = 0
    private var boxR = 0
    private val fbo = IntArray(1)

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    init {
        initEgl()
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        surfaceTexture = SurfaceTexture(oesTex)
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(frameLock) { frameReady = true; frameLock.notifyAll() }
        }, Handler(cbThread.looper))
        decoderSurface = Surface(surfaceTexture)

        progCopy = build(VS_EXT, FS_EXT)
        aPosCopy = GLES20.glGetAttribLocation(progCopy, "aPos")
        uStCopy = GLES20.glGetUniformLocation(progCopy, "uSt")
        progStoreY = build(VS_EXT, FS_EXT_Y)
        aPosY = GLES20.glGetAttribLocation(progStoreY, "aPos")
        uStY = GLES20.glGetUniformLocation(progStoreY, "uSt")
        progStoreUV = build(VS_EXT, FS_EXT_UV)
        aPosUV = GLES20.glGetAttribLocation(progStoreUV, "aPos")
        uStUV = GLES20.glGetUniformLocation(progStoreUV, "uSt")
        progPresent = build(VS_2D, FS_2D)
        aPosPresent = GLES20.glGetAttribLocation(progPresent, "aPos")
        progBlend = build(VS_300, FS_BLEND)
        aPosBlend = GLES20.glGetAttribLocation(progBlend, "aPos")
        uCap = GLES20.glGetUniformLocation(progBlend, "uCap")
        uJ0 = GLES20.glGetUniformLocation(progBlend, "uJ0")
        uN = GLES20.glGetUniformLocation(progBlend, "uN")
        uNight = GLES20.glGetUniformLocation(progBlend, "uNight")
        uLo = GLES20.glGetUniformLocation(progBlend, "uLo")
        uHi = GLES20.glGetUniformLocation(progBlend, "uHi")
        uW = GLES20.glGetUniformLocation(progBlend, "uW")
        GLES20.glUseProgram(progBlend)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progBlend, "uY"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progBlend, "uUV"), 1)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(progBlend, "uBlack"), BLACK_T, BLACK_S, BLACK_SP0, BLACK_SCALE)
        buildNightPrograms()
        GLES20.glGenFramebuffers(1, fbo, 0)
        check("init")
    }

    private fun buildNightPrograms() {
        val box = runCatching { build(VS_300, FS_BOX) }.getOrDefault(0)
        val tone = if (box != 0) runCatching { build(VS_300, FS_TONE) }.getOrDefault(0) else 0
        if (box == 0 || tone == 0) {
            if (box != 0) runCatching { GLES20.glDeleteProgram(box) }
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* ошибка сборки — уже учтена */ }
            return
        }
        progBox = box
        aPosBox = GLES20.glGetAttribLocation(box, "aPos")
        uBoxMode = GLES20.glGetUniformLocation(box, "uMode")
        uBoxLod = GLES20.glGetUniformLocation(box, "uLod")
        uBoxDir = GLES20.glGetUniformLocation(box, "uDir")
        uBoxR = GLES20.glGetUniformLocation(box, "uR")
        GLES20.glUseProgram(box)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(box, "uSrc"), 0)
        progTone = tone
        aPosTone = GLES20.glGetAttribLocation(tone, "aPos")
        GLES20.glUseProgram(tone)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(tone, "uRes"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(tone, "uAB"), 1)
    }

    /**
     * Видеокарта рисует в 16-битные float-текстуры — без этого ночной обработки нет
     * (результат склейки в 8 битах полосил бы поднятые тени). В GLES 3.2 это база, в 3.0/3.1 —
     * расширения EXT_color_buffer_half_float или EXT_color_buffer_float, они есть почти везде.
     */
    private fun halfFloatTargets(): Boolean {
        val ver = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        Regex("OpenGL ES (\\d+)\\.(\\d+)").find(ver)?.let { m ->
            val major = m.groupValues[1].toInt(); val minor = m.groupValues[2].toInt()
            if (major > 3 || (major == 3 && minor >= 2)) return true
        }
        val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        return "GL_EXT_color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext
    }

    /** Наибольшее число слоёв текстурного массива на этом GPU. */
    fun maxLayers(): Int {
        val v = IntArray(1)
        GLES20.glGetIntegerv(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS, v, 0)
        return v[0]
    }

    /** Наибольшая сторона текстуры на этом GPU. */
    fun maxTextureSize(): Int {
        val v = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, v, 0)
        return max(2048, v[0])
    }

    /**
     * Кольцо [cap] кадров [w]×[h] (Y — R8, Cb/Cr — RG8 вдвое меньше по каждой стороне) и
     * текстура результата. false — не хватило памяти видеокарты (вызывающий уменьшит
     * размер и попробует снова). [wantNight] — ночная обработка: результат RGBA16F с
     * мипмапами до уровня базы; если видеокарта в такое не рисует, обработки не будет
     * ([night] = false), а результат — обычный RGBA8.
     */
    fun allocate(w: Int, h: Int, cap: Int, wantNight: Boolean): Boolean {
        freeTextures()
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* сброс прежних ошибок */ }
        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        ringY = t[0]; ringUV = t[1]
        val cw = (w + 1) / 2; val chh = (h + 1) / 2
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ringY)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_R8, w, h, cap)
        texParams(GLES30.GL_TEXTURE_2D_ARRAY)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ringUV)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_RG8, cw, chh, cap)
        texParams(GLES30.GL_TEXTURE_2D_ARRAY)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) { freeTextures(); return false }
        night = wantNight && progTone != 0 && halfFloatTargets() && allocNightResult(w, h)
        if (!night && !allocPlainResult(w, h)) { freeTextures(); return false }
        this.w = w; this.h = h; this.cap = cap; uvW = cw; uvH = chh
        return true
    }

    private fun allocPlainResult(w: Int, h: Int): Boolean {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        resTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resTex)
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
        texParams(GLES20.GL_TEXTURE_2D)
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    /**
     * Результат для ночной обработки: RGBA16F (в альфе — лог-яркость, см. FS_BLEND) с
     * мипмапами до уровня [lod], и две RG16F этого уровня для базы. Уровень — тот, где
     * короткая сторона не больше [BASE_SIDE] (4K — 1/8, 1080p — 1/4); радиус фильтра базы
     * — [BASE_RADIUS] от короткой стороны кадра, как ни уменьшай. false — памяти нет или
     * видеокарта всё же не рисует в такие текстуры (всё созданное здесь удалено).
     */
    private fun allocNightResult(w: Int, h: Int): Boolean {
        var k = 0
        while ((min(w, h) shr k) > BASE_SIDE) k++
        val lw = max(1, w shr k); val lh = max(1, h shr k)
        val t = IntArray(3)
        GLES20.glGenTextures(3, t, 0)
        resTex = t[0]; lowA = t[1]; lowB = t[2]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resTex)
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, k + 1, GLES30.GL_RGBA16F, w, h)
        texParams(GLES20.GL_TEXTURE_2D)
        // с фильтром без мипмапов уровни выше нулевого для texelFetch «не существуют»
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_NEAREST)
        for (tex in intArrayOf(lowA, lowB)) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RG16F, lw, lh)
            texParams(GLES20.GL_TEXTURE_2D)
        }
        var ok = GLES20.glGetError() == GLES20.GL_NO_ERROR
        if (ok) {
            // мипмапы RGBA16F умеет не всякий драйвер, даже рисующий в такие текстуры
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resTex)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            ok = GLES20.glGetError() == GLES20.GL_NO_ERROR
        }
        if (ok) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            for (tex in intArrayOf(resTex, lowA)) {
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) ok = false
            }
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, 0, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        if (!ok) {
            runCatching { GLES20.glDeleteTextures(3, intArrayOf(resTex, lowA, lowB), 0) }
            resTex = 0; lowA = 0; lowB = 0
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* не наша ошибка — у обычного результата своя проверка */ }
            return false
        }
        lod = k; lowW = lw; lowH = lh
        boxR = max(1, Math.round(BASE_RADIUS * min(w, h) / (1 shl k)))
        return true
    }

    /** Поверхность кодировщика; дальше рисуем в неё (и в FBO). */
    fun attachEncoder(surface: Surface) {
        win = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (win == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
        if (!EGL14.eglMakeCurrent(dpy, win, win, ctx)) throw RuntimeException("eglMakeCurrent(window) failed")
    }

    /** Сбрасывает признак готового кадра (после flush декодера). */
    fun resetFrame() { synchronized(frameLock) { frameReady = false } }

    /** Ждёт кадр декодера и забирает его во внешнюю текстуру. */
    fun awaitFrame(timeoutMs: Long = 5000) {
        synchronized(frameLock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!frameReady) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw RuntimeException("timed out waiting for a decoded frame")
                frameLock.wait(left)
            }
            frameReady = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
    }

    /**
     * Текущий кадр декодера → слой кольца: яркость в полном разрешении, цвет — в половинном
     * (линейная выборка в центре каждого блока 2×2 и есть среднее четырёх пикселей).
     */
    fun storeFrame(layer: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTextureLayer(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, ringY, 0, layer)
        GLES20.glViewport(0, 0, w, h)
        drawOes(progStoreY, aPosY, uStY)
        GLES30.glFramebufferTextureLayer(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, ringUV, 0, layer)
        GLES20.glViewport(0, 0, uvW, uvH)
        drawOes(progStoreUV, aPosUV, uStUV)
    }

    /**
     * Текущий кадр декодера — уменьшенной картинкой «как увидит зритель» без поворота:
     * чтение FBO идёт снизу вверх, поэтому строки переворачиваются.
     */
    fun frameThumb(tw: Int, th: Int): Bitmap {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, tw, th, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        texParams(GLES20.GL_TEXTURE_2D)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
        GLES20.glViewport(0, 0, tw, th)
        drawOes(progCopy, aPosCopy, uStCopy)
        val buf = ByteBuffer.allocateDirect(tw * th * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glDeleteTextures(1, t, 0)
        val flipped = ByteBuffer.allocateDirect(tw * th * 4).order(ByteOrder.nativeOrder())
        val row = ByteArray(tw * 4)
        for (y in 0 until th) {
            buf.position((th - 1 - y) * tw * 4)
            buf.get(row)
            flipped.put(row)
        }
        flipped.rewind()
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(flipped)
        return bmp
    }

    /**
     * Склейка окна в текстуру результата. Кадры окна — слои кольца подряд, начиная с
     * [j0] (уже по модулю cap), [n] штук; окна k = 0..11 — отрезки [lo[k], hi[k]]
     * (относительно первого кадра) с весами [wt]: у каждого окна поканальный максимум
     * (lighten) его кадров, результат — среднее окон с весами и мягкий чёрный (FS_BLEND).
     */
    fun blend(j0: Int, n: Int, lo: IntArray, hi: IntArray, wt: FloatArray) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, resTex, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progBlend)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ringY)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ringUV)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uCap, cap)
        GLES20.glUniform1i(uJ0, j0)
        GLES20.glUniform1i(uN, n)
        GLES20.glUniform1i(uNight, if (night) 1 else 0)
        GLES20.glUniform1iv(uLo, 12, lo, 0)
        GLES20.glUniform1iv(uHi, 12, hi, 0)
        GLES20.glUniform1fv(uW, 12, wt, 0)
        drawQuad(aPosBlend)
    }

    /**
     * Результат → поверхность кодировщика с меткой времени [ptsNs]. С ночной обработкой —
     * через тон-маппинг: база по мипмапу результата ([toneBase]), затем FS_TONE.
     */
    fun present(outW: Int, outH: Int, ptsNs: Long) {
        if (night) toneBase()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resTex)
        if (night) {
            GLES20.glUseProgram(progTone)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lowB)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            drawQuad(aPosTone)
        } else {
            GLES20.glUseProgram(progPresent)
            drawQuad(aPosPresent)
        }
        EGLExt.eglPresentationTimeANDROID(dpy, win, ptsNs)
        if (!EGL14.eglSwapBuffers(dpy, win)) throw RuntimeException("eglSwapBuffers failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
    }

    /**
     * База тон-маппинга — fast guided filter лог-яркости (как night_tm.py, которым ночные
     * ролики проверялись офлайн): мипмап усредняет L из альфы результата до уровня [lod],
     * там — четыре прохода прямоугольного фильтра радиуса [boxR] (по строкам и столбцам —
     * среднее и среднее квадрата, из них коэффициенты a, b; затем среднее a и b). Итог в
     * [lowB]; FS_TONE берёт его билинейно и считает базу как a·L + b по полному кадру,
     * поэтому края (диск против тёмного фона) не расплываются в ореол. Уровень — 1/64
     * пикселей кадра, проходы почти ничего не стоят рядом со склейкой.
     */
    private fun toneBase() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resTex)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glViewport(0, 0, lowW, lowH)
        GLES20.glUseProgram(progBox)
        GLES20.glUniform1i(uBoxR, boxR)
        fun pass(src: Int, srcLod: Int, dst: Int, mode: Int, dx: Int, dy: Int) {
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, dst, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src)
            GLES20.glUniform1i(uBoxMode, mode)
            GLES20.glUniform1i(uBoxLod, srcLod)
            GLES20.glUniform2i(uBoxDir, dx, dy)
            drawQuad(aPosBox)
        }
        pass(resTex, lod, lowA, 0, 1, 0)    // по строкам: среднее L и L²
        pass(lowA, 0, lowB, 2, 0, 1)        // по столбцам, затем a и b
        pass(lowB, 0, lowA, 1, 1, 0)        // среднее a и b — по строкам
        pass(lowA, 0, lowB, 1, 0, 1)        // и по столбцам
    }

    fun release() {
        freeTextures()
        runCatching { GLES20.glDeleteFramebuffers(1, fbo, 0) }
        runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0) }
        runCatching { GLES20.glDeleteProgram(progCopy) }
        runCatching { GLES20.glDeleteProgram(progStoreY) }
        runCatching { GLES20.glDeleteProgram(progStoreUV) }
        runCatching { GLES20.glDeleteProgram(progPresent) }
        runCatching { GLES20.glDeleteProgram(progBlend) }
        if (progBox != 0) runCatching { GLES20.glDeleteProgram(progBox) }
        if (progTone != 0) runCatching { GLES20.glDeleteProgram(progTone) }
        runCatching { decoderSurface.release() }
        runCatching { surfaceTexture.release() }
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (win != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(dpy, win) }
            if (pbuf != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(dpy, pbuf) }
            runCatching { EGL14.eglDestroyContext(dpy, ctx) }
            EGL14.eglReleaseThread()
            runCatching { EGL14.eglTerminate(dpy) }
        }
        dpy = EGL14.EGL_NO_DISPLAY
        runCatching { cbThread.quitSafely() }
    }

    // ------------------------------------------------------------------ детали

    private fun freeTextures() {
        val t = IntArray(5)
        var n = 0
        for (x in intArrayOf(ringY, ringUV, resTex, lowA, lowB)) if (x != 0) t[n++] = x
        if (n > 0) runCatching { GLES20.glDeleteTextures(n, t, 0) }
        ringY = 0; ringUV = 0; resTex = 0; lowA = 0; lowB = 0
        night = false
    }

    private fun drawOes(prog: Int, aPos: Int, uSt: Int) {
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        drawQuad(aPos)
    }

    private fun drawQuad(aPos: Int) {
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun texParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun initEgl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) throw RuntimeException("no EGL display")
        val v = IntArray(2)
        if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw RuntimeException("eglInitialize failed")
        // Конфигурация, пригодная для поверхности кодировщика (RECORDABLE). Pbuffer нужен
        // лишь для того, чтобы сделать контекст текущим до создания кодировщика; не на
        // всех GPU он сочетается с RECORDABLE — тогда контекст без поверхности.
        fun choose(surfaceType: Int): EGLConfig? {
            val attr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, surfaceType,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            return if (EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, n, 0) && n[0] > 0) cfgs[0] else null
        }
        var withPbuffer = true
        cfg = choose(EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT)
        if (cfg == null) { withPbuffer = false; cfg = choose(EGL14.EGL_WINDOW_BIT) }
        if (cfg == null) throw RuntimeException("OpenGL ES 3.0 with a recordable surface is not available on this phone")
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext(ES3) failed")
        if (withPbuffer) {
            pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (pbuf == EGL14.EGL_NO_SURFACE) withPbuffer = false
        }
        val ok = if (withPbuffer) EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                 else EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, ctx)
        if (!ok) throw RuntimeException("eglMakeCurrent failed")
    }

    private fun build(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("program link failed: $log")
        }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("shader compile failed: $log")
        }
        return s
    }

    private fun check(where: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) throw RuntimeException("GL error 0x" + Integer.toHexString(e) + " at " + where)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_OPENGL_ES3_BIT = 0x40

        // Мягкий чёрный (FS_BLEND): softplus(x − T) с шириной колена S, сдвинутый так, что
        // 0 → 0, и растянутый так, что 1 → 1. Выше ~3T это просто «минус T», ниже — плавный
        // спуск к нулю: шум матрицы, накопленный максимумом по кадрам, уходит в чёрный, а
        // настоящие тени остаются. Подобрано на ночных роликах 2026-10-18 (Samsung 4K ×4).
        const val BLACK_T = 3f / 255f
        const val BLACK_S = 2f / 255f
        val BLACK_SP0 = BLACK_S * ln(1f + exp(-BLACK_T / BLACK_S))
        val BLACK_SCALE = 1f / ((1f - BLACK_T) + BLACK_S * ln(1f + exp(-(1f - BLACK_T) / BLACK_S)) - BLACK_SP0)

        /** База тон-маппинга — на уровне мипмапа, где короткая сторона кадра не больше этого. */
        const val BASE_SIDE = 360
        /**
         * Радиус фильтра базы — доля короткой стороны кадра (4K: 60 точек, 1080p: 30).
         * Масштаб «областей», которые поднимаются как целое: меньше — картинка плоская,
         * больше — тёмное у самого диска не поднимается.
         */
        const val BASE_RADIUS = 0.0278f

        // Шейдеры без ведущих пробелов: часть драйверов капризничает к отступу перед #.
        private const val VS_EXT =
            "attribute vec2 aPos;\n" +
            "uniform mat4 uSt;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vUV = (uSt * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n"

        private const val FS_EXT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform samplerExternalOES uTex;\n" +
            "varying vec2 vUV;\n" +
            "void main() { gl_FragColor = texture2D(uTex, vUV); }\n"

        // Кадр декодера → яркость BT.709 (слой R8 кольца).
        private const val FS_EXT_Y =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform samplerExternalOES uTex;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vec3 c = texture2D(uTex, vUV).rgb;\n" +
            "  gl_FragColor = vec4(dot(c, vec3(0.2126, 0.7152, 0.0722)), 0.0, 0.0, 1.0);\n" +
            "}\n"

        // Кадр декодера → цветоразность BT.709 со сдвигом 0.5 (слой RG8 вдвое меньше).
        private const val FS_EXT_UV =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform samplerExternalOES uTex;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vec3 c = texture2D(uTex, vUV).rgb;\n" +
            "  float y = dot(c, vec3(0.2126, 0.7152, 0.0722));\n" +
            "  gl_FragColor = vec4((c.b - y) / 1.8556 + 0.5, (c.r - y) / 1.5748 + 0.5, 0.0, 1.0);\n" +
            "}\n"

        private const val VS_2D =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vUV = aPos * 0.5 + 0.5;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n"

        private const val FS_2D =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D uTex;\n" +
            "varying vec2 vUV;\n" +
            "void main() { gl_FragColor = texture2D(uTex, vUV); }\n"

        private const val VS_300 =
            "#version 300 es\n" +
            "in vec2 aPos;\n" +
            "out vec2 vUV;\n" +
            "void main() {\n" +
            "  vUV = aPos * 0.5 + 0.5;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n"

        // Склейка — то же, что PovRender.Run в скрипте: у каждого из 12 окон поканальный
        // максимум (lighten) его кадров, результат — среднее окон с весами. Каждый кадр
        // читается один раз и раздаётся всем окнам, куда попадает.
        // Чёрный — мягкий (uBlack: T, S, сдвиг, растяжение — см. BLACK_T) и по результату.
        // Прежде здесь была жёсткая поканальная отсечка 16/255 по каждому кадру, и она стирала
        // настоящие тени: OES-текстура уже растянута драйвером из TV-диапазона в полный, так
        // что 16/255 — это не «ниже чёрного», а сцена. Ночью под порог уходило до трёх
        // четвертей человека в кадре, а срезанные порознь каналы красили края теней в жёлтый
        // и красный. Носок монотонный, поэтому max(носок(x)) = носок(max(x)) — по кадрам его
        // делать незачем.
        // uNight: в альфу — лог-яркость L = log2 линейной яркости самого яркого канала
        // (γ 2.2), снизу не ниже 10/255; мипмапы результата усредняют её для базы
        // тон-маппинга (FS_BOX, FS_TONE). RGBA16F хранит L как есть, со знаком.
        private const val FS_BLEND =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "precision highp sampler2DArray;\n" +
            "uniform sampler2DArray uY;\n" +
            "uniform sampler2DArray uUV;\n" +
            "uniform int uCap;\n" +
            "uniform int uJ0;\n" +
            "uniform int uN;\n" +
            "uniform int uNight;\n" +
            "uniform vec4 uBlack;\n" +
            "uniform int uLo[12];\n" +
            "uniform int uHi[12];\n" +
            "uniform float uW[12];\n" +
            "in vec2 vUV;\n" +
            "out vec4 oColor;\n" +
            "void main() {\n" +
            "  vec3 acc[12];\n" +
            "  for (int k = 0; k < 12; k++) acc[k] = vec3(0.0);\n" +
            "  for (int i = 0; i < uN; i++) {\n" +
            "    int layer = (uJ0 + i) % uCap;\n" +
            "    float yy = texture(uY, vec3(vUV, float(layer))).r;\n" +
            "    vec2 cc = texture(uUV, vec3(vUV, float(layer))).rg - 0.5;\n" +
            "    vec3 c = clamp(vec3(yy + 1.5748 * cc.y, yy - 0.1873 * cc.x - 0.4681 * cc.y, yy + 1.8556 * cc.x), 0.0, 1.0);\n" +
            "    for (int k = 0; k < 12; k++) {\n" +
            "      if (uW[k] <= 0.0 || i < uLo[k] || i > uHi[k]) continue;\n" +
            "      acc[k] = max(acc[k], c);\n" +
            "    }\n" +
            "  }\n" +
            "  vec3 res = vec3(0.0);\n" +
            "  float ws = 0.0;\n" +
            "  for (int k = 0; k < 12; k++) {\n" +
            "    if (uW[k] <= 0.0) continue;\n" +
            "    res += uW[k] * acc[k];\n" +
            "    ws += uW[k];\n" +
            "  }\n" +
            "  res /= max(ws, 1e-6);\n" +
            "  vec3 z = (res - uBlack.x) / uBlack.y;\n" +
            "  vec3 sp = uBlack.y * (max(z, 0.0) + log(1.0 + exp(-abs(z))));\n" +
            "  res = clamp((sp - uBlack.z) * uBlack.w, 0.0, 1.0);\n" +
            "  float a = 1.0;\n" +
            "  if (uNight == 1) a = max(2.2 * log2(max(max(res.r, max(res.g, res.b)), 1e-4)), -10.2793);\n" +
            "  oColor = vec4(res, a);\n" +
            "}\n"

        // Прямоугольный фильтр базы тон-маппинга вдоль uDir, радиус uR, края — повтором
        // крайней точки. uMode 0: из альфы уровня uLod результата — среднее L и L² (L
        // сдвинута на +5, чтобы дисперсия E[L²] − E[L]² не терялась в половинной точности
        // RG16F); 1 — среднее rg как есть; 2 — среднее (E[L], E[L²]), из него коэффициенты
        // guided filter a = σ²/(σ² + ε), b = (1 − a)·E[L] без сдвига; ε = 0.5 стопа².
        private const val FS_BOX =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform highp sampler2D uSrc;\n" +
            "uniform int uMode;\n" +
            "uniform int uLod;\n" +
            "uniform ivec2 uDir;\n" +
            "uniform int uR;\n" +
            "in vec2 vUV;\n" +
            "out vec4 oColor;\n" +
            "void main() {\n" +
            "  ivec2 p = ivec2(gl_FragCoord.xy);\n" +
            "  ivec2 hi = textureSize(uSrc, uLod) - 1;\n" +
            "  vec2 s = vec2(0.0);\n" +
            "  for (int d = -uR; d <= uR; d++) {\n" +
            "    vec4 t = texelFetch(uSrc, clamp(p + uDir * d, ivec2(0), hi), uLod);\n" +
            "    float l = t.a + 5.0;\n" +
            "    s += (uMode == 0) ? vec2(l, l * l) : t.rg;\n" +
            "  }\n" +
            "  s /= float(2 * uR + 1);\n" +
            "  if (uMode == 2) {\n" +
            "    float v = max(s.y - s.x * s.x, 0.0);\n" +
            "    float a = v / (v + 0.5);\n" +
            "    float m = s.x - 5.0;\n" +
            "    s = vec2(a, m - a * m);\n" +
            "  }\n" +
            "  oColor = vec4(s, 0.0, 1.0);\n" +
            "}\n"

        // Ночная обработка — локальный тон-маппинг, как night_tm.py (ночные ролики
        // 2026-10-18), без сглаживания теней: оно по полному кадру стоило бы дороже самой
        // склейки, а разница видна лишь в 4K один к одному.
        //  - база q = a·L + b (uAB — билинейно с уровня базы, L — своя у пикселя): края
        //    резче ε остаются краями, и тёмное у самого диска не получает ореола;
        //  - усиление в линейном свете 2^((c − 1)·q), c = 0.27: от области в q стопов ниже
        //    белого остаётся c·q — тьма поднимается, белое (q = 0) остаётся белым; в
        //    γ-кодировке это степень 1/2.2, отсюда множитель (c − 1)/2.2 = −0.3318;
        //  - мягкое плечо выше 0.82 — яркая точка в тёмном окружении не выбивается в белое;
        //  - приглушение цвета в самом тёмном (до 80 % при нуле, ноль от 100/255): цвет там
        //    недостоверен и после подъёма кричит;
        //  - статичный TPDF-дизеринг ±1 младший разряд перед 8 битами кодировщика: поднятые
        //    в разы тени иначе идут полосами. Узор неподвижен — кодировщику не на что тратить
        //    битрейт.
        private const val FS_TONE =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform highp sampler2D uRes;\n" +
            "uniform highp sampler2D uAB;\n" +
            "in vec2 vUV;\n" +
            "out vec4 oColor;\n" +
            "float hash(uvec2 p) {\n" +
            "  uint h = p.x * 1664525u + p.y * 1013904223u + 12345u;\n" +
            "  h ^= h >> 16; h *= 0x7feb352du; h ^= h >> 15; h *= 0x846ca68bu; h ^= h >> 16;\n" +
            "  return float(h >> 8) * (1.0 / 16777216.0);\n" +
            "}\n" +
            "void main() {\n" +
            "  vec4 t = textureLod(uRes, vUV, 0.0);\n" +
            "  vec3 f = t.rgb;\n" +
            "  vec2 ab = texture(uAB, vUV).rg;\n" +
            "  float q = ab.x * t.a + ab.y;\n" +
            "  float g = exp2(-0.331818 * min(q, 0.0));\n" +
            "  float m = max(f.r, max(f.g, f.b));\n" +
            "  float x = m * g;\n" +
            "  float y = x > 0.82 ? 0.82 + 0.18 * (1.0 - exp(-(x - 0.82) / 0.18)) : x;\n" +
            "  vec3 o = f * (g * (x > 1e-6 ? y / x : 1.0));\n" +
            "  float lum = dot(o, vec3(0.2126, 0.7152, 0.0722));\n" +
            "  float k = 1.0 - clamp(m * 2.55, 0.0, 1.0);\n" +
            "  o = lum + (o - lum) * (1.0 - 0.8 * k * k);\n" +
            "  uvec2 px = uvec2(gl_FragCoord.xy);\n" +
            "  o += (hash(px) + hash(px + uvec2(7919u, 104729u)) - 1.0) / 255.0;\n" +
            "  oColor = vec4(clamp(o, 0.0, 1.0), 1.0);\n" +
            "}\n"
    }
}
