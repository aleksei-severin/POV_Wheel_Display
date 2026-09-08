package com.povwheel.app.convert

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Оффскрин-приёмник кадров видеодекодера.
 *
 * Единственная задача — быстро вытащить кадр из [MediaCodec] и уменьшить его до
 * умеренного размера [outW]×[outH] (длинная сторона ~[READ_CAP]). Поворот ролика
 * и вписывание Crop/Fit делает CPU дальше по конвейеру (`Bitmaps.rotateDegrees`
 * + `Bitmaps.drawSquare`) — там та же проверенная геометрия, что у картинок и
 * GIF, и никаких сомнений в знаке поворота.
 *
 * Уменьшение — делением вдвое (каждый шаг ровно 2:1, `GL_LINEAR` = бокс-фильтр),
 * ровно как `Bitmaps.drawSquare` делает это на CPU: без площадного усреднения
 * периферия обода превратилась бы в лесенку ещё до полярной выборки.
 *
 * Всё на GLES 2.0 + расширении `GL_OES_EGL_image_external` — есть на любом
 * Android. Ядро EGL/SurfaceTexture — по мотивам Grafika (Apache 2.0, Google).
 *
 * ВАЖНО: все методы, кроме onFrameAvailable, вызываются из одного потока — того,
 * что создал объект (там становится текущим EGL-контекст).
 */
internal class GlVideoScaler(private val srcW: Int, private val srcH: Int) {

    /** Размер кадра после уменьшения; сюда же создаётся пул Bitmap у вызывающего. */
    val outW: Int
    val outH: Int

    init {
        val big = maxOf(srcW, srcH, 1)
        if (big > READ_CAP) {
            outW = maxOf(1, srcW * READ_CAP / big)
            outH = maxOf(1, srcH * READ_CAP / big)
        } else {
            outW = maxOf(1, srcW)
            outH = maxOf(1, srcH)
        }
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val oesTex: Int
    private val surfaceTexture: SurfaceTexture
    /** Сюда декодер отдаёт кадры (`codec.configure(fmt, surface, …)`). */
    val surface: Surface

    private val cbThread = HandlerThread("gl-frame").apply { start() }
    private val frameLock = Object()
    private var frameReady = false

    private val progExt: Int
    private val prog2d: Int
    private val aPosExt: Int
    private val aPos2d: Int
    private val uStExt: Int

    private val fbo = IntArray(2)
    private val tex = IntArray(2)
    private val texW = IntArray(2)
    private val texH = IntArray(2)
    private var fboChecked = false

    private val stMatrix = FloatArray(16)

    private val quad: FloatBuffer = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val pixBuf: ByteBuffer =
        ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())

    init {
        initEgl()
        oesTex = createExternalTex()
        surfaceTexture = SurfaceTexture(oesTex)
        surfaceTexture.setOnFrameAvailableListener({ _ ->
            synchronized(frameLock) { frameReady = true; frameLock.notifyAll() }
        }, Handler(cbThread.looper))
        surface = Surface(surfaceTexture)

        progExt = buildProgram(VERT_EXT, FRAG_EXT)
        prog2d = buildProgram(VERT_2D, FRAG_2D)
        aPosExt = GLES20.glGetAttribLocation(progExt, "aPos")
        uStExt = GLES20.glGetUniformLocation(progExt, "uSt")
        aPos2d = GLES20.glGetAttribLocation(prog2d, "aPos")

        GLES20.glGenFramebuffers(2, fbo, 0)
        GLES20.glGenTextures(2, tex, 0)
        checkGl("init")
    }

    /** Ждёт следующий кадр из декодера и забирает его во внешнюю текстуру. */
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

    /** Уменьшает текущий кадр в [dst] (ARGB_8888, ровно [outW]×[outH]). */
    fun renderInto(dst: Bitmap) {
        // --- пасс 1: OES → tex[0] в масштабе кадра (с кэпом), матрица SurfaceTexture ---
        // stMatrix несёт вертикальный флип видеокадра, поэтому дальше по
        // конвейеру переворотов Y нет: glReadPixels + copyPixelsFromBuffer дают
        // Bitmap правильной ориентации (тот же путь, что в Grafika).
        val cap = 2048
        var w = srcW
        var h = srcH
        val big = maxOf(w, h, 1)
        if (big > cap) {
            w = maxOf(1, w * cap / big)
            h = maxOf(1, h * cap / big)
        }
        ensureTex(0, w, h)
        bindFbo(0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(progExt)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES20.glUniformMatrix4fv(uStExt, 1, false, stMatrix, 0)
        drawQuad(aPosExt)

        // --- пасс 2: деление вдвое, пока не подберёмся к outW×outH ---
        var src = 0
        var cw = w
        var ch = h
        while (cw / 2 >= outW && ch / 2 >= outH && cw > 2 && ch > 2) {
            val d = 1 - src
            ensureTex(d, cw / 2, ch / 2)
            bindFbo(d, cw / 2, ch / 2)
            blit2d(tex[src])
            src = d
            cw /= 2
            ch /= 2
        }

        // --- пасс 3: последний шаг в pbuffer outW×outH + чтение ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        blit2d(tex[src])

        pixBuf.rewind()
        GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixBuf)
        checkGl("readPixels")
        pixBuf.rewind()
        dst.copyPixelsFromBuffer(pixBuf)
    }

    fun release() {
        runCatching { GLES20.glDeleteFramebuffers(2, fbo, 0) }
        runCatching { GLES20.glDeleteTextures(2, tex, 0) }
        runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0) }
        runCatching { GLES20.glDeleteProgram(progExt) }
        runCatching { GLES20.glDeleteProgram(prog2d) }
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            EGL14.eglReleaseThread()
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        runCatching { cbThread.quitSafely() }
    }

    // ------------------------------------------------------------------ детали

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("no EGL display")
        val v = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, v, 0, v, 1)) throw RuntimeException("eglInitialize failed")

        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val nCfg = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, nCfg, 0) || nCfg[0] == 0)
            throw RuntimeException("no matching EGL config")

        eglContext = EGL14.eglCreateContext(
            eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        eglSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, cfgs[0],
            intArrayOf(EGL14.EGL_WIDTH, outW, EGL14.EGL_HEIGHT, outH, EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreatePbufferSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
            throw RuntimeException("eglMakeCurrent failed")
    }

    private fun createExternalTex(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        return t[0]
    }

    private fun texParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun ensureTex(i: Int, w: Int, h: Int) {
        if (tex[i] != 0 && texW[i] == w && texH[i] == h) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        texParams(GLES20.GL_TEXTURE_2D)
        texW[i] = w
        texH[i] = h
    }

    private fun bindFbo(i: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0
        )
        if (!fboChecked) {
            fboChecked = true
            val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (st != GLES20.GL_FRAMEBUFFER_COMPLETE)
                throw RuntimeException("framebuffer incomplete: 0x${Integer.toHexString(st)}")
        }
        GLES20.glViewport(0, 0, w, h)
    }

    private fun blit2d(srcTex: Int) {
        GLES20.glUseProgram(prog2d)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        texParams(GLES20.GL_TEXTURE_2D)
        drawQuad(aPos2d)
    }

    private fun drawQuad(aPos: Int) {
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun buildProgram(vs: String, fs: String): Int {
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
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
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

    private fun checkGl(where: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR)
            throw RuntimeException("GL error 0x${Integer.toHexString(e)} at $where")
    }

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(a); position(0) }

    private companion object {
        // Длинная сторона обратного чтения. Дальше кадр идёт в drawSquare, тот
        // сам доводит до 400 делением вдвое — как и на старом пути. 1440 — баланс:
        // сглаживание обода почти как у полного 1080p, а Bitmap в пуле вчетверо
        // легче 4K-кадра (важно: их несколько в пуле плюс буфер под поворот).
        const val READ_CAP = 1440

        // Шейдеры плоской строкой без ведущих пробелов: у части драйверов
        // GLSL-препроцессор капризничает к отступу перед #extension / #.
        const val VERT_EXT =
            "attribute vec2 aPos;\n" +
            "uniform mat4 uSt;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vUV = (uSt * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n"

        const val FRAG_EXT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "varying vec2 vUV;\n" +
            "void main() { gl_FragColor = texture2D(uTex, vUV); }\n"

        const val VERT_2D =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vUV = aPos * 0.5 + 0.5;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n"

        const val FRAG_2D =
            "precision mediump float;\n" +
            "uniform sampler2D uTex;\n" +
            "varying vec2 vUV;\n" +
            "void main() { gl_FragColor = texture2D(uTex, vUV); }\n"
    }
}
