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
import kotlin.math.max

/**
 * GLES 3.0 для рендера POV-видео: кольцо последних кадров — слои текстурных массивов,
 * склейка окна — один проход шейдера по этим слоям, результат — в поверхность
 * видеокодировщика.
 *
 * Кольцо хранит кадры в YUV 4:2:0 (BT.709): яркость — R8 в полном разрешении, цвет —
 * RG8 в половинном. 1.5 байта на пиксель вместо 4 у RGBA — так в память видеокарты
 * помещается кольцо в разрешении исходника, хоть 4K. Результат кодируется в тот же
 * 4:2:0, так что потерь нет; шейдер склейки переводит кадры обратно в RGB до отсечки
 * шума и максимума по каналам.
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
    private val uMode: Int
    private val uLut: Int
    private val uBlack: Int
    private val uLo: Int
    private val uHi: Int
    private val uW: Int

    // ---- текстуры ----
    var w = 0; private set
    var h = 0; private set
    var cap = 0; private set
    private var uvW = 0
    private var uvH = 0
    private var ringY = 0
    private var ringUV = 0
    private var resTex = 0
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
        uMode = GLES20.glGetUniformLocation(progBlend, "uMode")
        uLut = GLES20.glGetUniformLocation(progBlend, "uLut")
        uBlack = GLES20.glGetUniformLocation(progBlend, "uBlack")
        uLo = GLES20.glGetUniformLocation(progBlend, "uLo")
        uHi = GLES20.glGetUniformLocation(progBlend, "uHi")
        uW = GLES20.glGetUniformLocation(progBlend, "uW")
        GLES20.glUseProgram(progBlend)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progBlend, "uY"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progBlend, "uUV"), 1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        check("init")
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
     * размер и попробует снова).
     */
    fun allocate(w: Int, h: Int, cap: Int): Boolean {
        freeTextures()
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* сброс прежних ошибок */ }
        val t = IntArray(3)
        GLES20.glGenTextures(3, t, 0)
        ringY = t[0]; ringUV = t[1]; resTex = t[2]
        val cw = (w + 1) / 2; val chh = (h + 1) / 2
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ringY)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_R8, w, h, cap)
        texParams(GLES30.GL_TEXTURE_2D_ARRAY)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ringUV)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_RG8, cw, chh, cap)
        texParams(GLES30.GL_TEXTURE_2D_ARRAY)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resTex)
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
        texParams(GLES20.GL_TEXTURE_2D)
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) { freeTextures(); return false }
        this.w = w; this.h = h; this.cap = cap; uvW = cw; uvH = chh
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
     * (относительно первого кадра) с весами [wt].
     * mode: 0 lighten (поканальный максимум), 1 average, 2 screen. [black] — порог
     * отсечки шума (0..1) или < 0 — без отсечки.
     */
    fun blend(j0: Int, n: Int, lo: IntArray, hi: IntArray, wt: FloatArray, mode: Int, black: Float) {
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
        GLES20.glUniform1i(uMode, mode)
        GLES20.glUniform1i(uLut, if (black >= 0) 1 else 0)
        GLES20.glUniform1f(uBlack, if (black >= 0) black else 0f)
        GLES20.glUniform1iv(uLo, 12, lo, 0)
        GLES20.glUniform1iv(uHi, 12, hi, 0)
        GLES20.glUniform1fv(uW, 12, wt, 0)
        drawQuad(aPosBlend)
    }

    /** Результат → поверхность кодировщика с меткой времени [ptsNs]. */
    fun present(outW: Int, outH: Int, ptsNs: Long) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glUseProgram(progPresent)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resTex)
        drawQuad(aPosPresent)
        EGLExt.eglPresentationTimeANDROID(dpy, win, ptsNs)
        if (!EGL14.eglSwapBuffers(dpy, win)) throw RuntimeException("eglSwapBuffers failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
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
        val t = IntArray(3)
        var n = 0
        if (ringY != 0) t[n++] = ringY
        if (ringUV != 0) t[n++] = ringUV
        if (resTex != 0) t[n++] = resTex
        if (n > 0) runCatching { GLES20.glDeleteTextures(n, t, 0) }
        ringY = 0; ringUV = 0; resTex = 0
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

        // Шейдеры без ведущих пробелов: часть драйверов капризничает к отступу перед #.
        const val VS_EXT =
            "attribute vec2 aPos;\n" +
            "uniform mat4 uSt;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vUV = (uSt * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n"

        const val FS_EXT =
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
        const val FS_EXT_Y =
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
        const val FS_EXT_UV =
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

        const val VS_2D =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vUV = aPos * 0.5 + 0.5;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n"

        const val FS_2D =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D uTex;\n" +
            "varying vec2 vUV;\n" +
            "void main() { gl_FragColor = texture2D(uTex, vUV); }\n"

        const val VS_300 =
            "#version 300 es\n" +
            "in vec2 aPos;\n" +
            "out vec2 vUV;\n" +
            "void main() {\n" +
            "  vUV = aPos * 0.5 + 0.5;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n"

        // Склейка — то же, что PovRender.Run в скрипте: у каждого из 12 окон своя склейка
        // кадров (максимум / среднее / screen), результат — среднее окон с весами.
        // Отсечка шума — до склейки: всё, что не выше порога, считается чёрным.
        const val FS_BLEND =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "precision highp sampler2DArray;\n" +
            "uniform sampler2DArray uY;\n" +
            "uniform sampler2DArray uUV;\n" +
            "uniform int uCap;\n" +
            "uniform int uJ0;\n" +
            "uniform int uN;\n" +
            "uniform int uMode;\n" +
            "uniform int uLut;\n" +
            "uniform float uBlack;\n" +
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
            "    if (uLut == 1) c *= vec3(greaterThan(c, vec3(uBlack)));\n" +
            "    for (int k = 0; k < 12; k++) {\n" +
            "      if (uW[k] <= 0.0 || i < uLo[k] || i > uHi[k]) continue;\n" +
            "      if (uMode == 1) acc[k] += c;\n" +
            "      else if (uMode == 2) acc[k] = vec3(1.0) - (vec3(1.0) - acc[k]) * (vec3(1.0) - c);\n" +
            "      else acc[k] = max(acc[k], c);\n" +
            "    }\n" +
            "  }\n" +
            "  vec3 res = vec3(0.0);\n" +
            "  float ws = 0.0;\n" +
            "  for (int k = 0; k < 12; k++) {\n" +
            "    if (uW[k] <= 0.0) continue;\n" +
            "    vec3 v = acc[k];\n" +
            "    if (uMode == 1) v /= float(uHi[k] - uLo[k] + 1);\n" +
            "    res += uW[k] * v;\n" +
            "    ws += uW[k];\n" +
            "  }\n" +
            "  oColor = vec4(res / max(ws, 1e-6), 1.0);\n" +
            "}\n"
    }
}
