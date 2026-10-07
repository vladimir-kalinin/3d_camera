package com.example.my_3d_camera.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import com.example.my_3d_camera.camera.StereoRig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Собирает кадр Side-by-Side из двух камер на GPU в реальном времени.
 *
 * Кадры камер приходят в две SurfaceTexture. Когда пришла новая пара кадров,
 * рендерер рисует SBS в превью, в поверхность энкодера (если идёт запись)
 * и во внеэкранный буфер (если запрошено фото).
 */
class StereoRenderer(private val rig: StereoRig, private val listener: Listener) {

    interface Listener {
        /** Вызывается раз в секунду с потока рендерера. */
        fun onStats(fps: Float, maxPairSkewMs: Float)
    }

    /** Ручная подстройка поверх автоматической калибровки. */
    data class Calibration(
        /** Дополнительный масштаб широкоугольной картинки (1.0 = как в калибровке). */
        val scale: Float = 1f,
        /** Сдвиг широкоугольной картинки по вертикали, в долях высоты кадра. */
        val verticalShift: Float = 0f,
        /** Поменять глаза местами. */
        val swapEyes: Boolean = false,
    )

    private val thread = HandlerThread("StereoGL").apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private lateinit var idleSurface: EGLSurface
    private var program = 0
    private var aPosition = 0
    private var aUv = 0
    private var uStMatrix = 0
    private var uMap = 0
    private var uFlipY = 0
    private var uTexture = 0
    private lateinit var quad: FloatBuffer

    private var wideTexId = 0
    private var mainTexId = 0
    private lateinit var wideTexture: SurfaceTexture
    private lateinit var mainTexture: SurfaceTexture
    private lateinit var wideSurface: Surface
    private lateinit var mainSurface: Surface
    private val wideMatrix = FloatArray(16)
    private val mainMatrix = FloatArray(16)
    private var wideTimestamp = 0L
    private var mainTimestamp = 0L
    private var wideFresh = false
    private var mainFresh = false

    private var previewSurface: EGLSurface? = null
    private var recordSurface: EGLSurface? = null
    private var lastRecordPtsNs = 0L
    private var pendingPhoto: ((ByteBuffer, Int, Int) -> Unit)? = null

    @Volatile var calibration = Calibration()

    /** Размер итогового кадра SBS (ширина обоих глаз вместе). */
    @Volatile var outputSize = Size(0, 0)
        private set

    private var statsStart = 0L
    private var statsFrames = 0
    private var statsMaxSkewNs = 0L
    private var released = false

    /** Инициализирует GL и возвращает поверхности для камер (wide, main). */
    fun start(): Pair<Surface, Surface> {
        runSync {
            egl = EglCore()
            idleSurface = egl.createPbufferSurface(1, 1)
            egl.makeCurrent(idleSurface)
            initGl()
        }
        return wideSurface to mainSurface
    }

    /** Задаёт размеры потоков камер. Итоговый кадр = два портретных кадра основной камеры рядом. */
    fun setStreamSizes(wideSize: Size, mainSize: Size) {
        wideTexture.setDefaultBufferSize(wideSize.width, wideSize.height)
        mainTexture.setDefaultBufferSize(mainSize.width, mainSize.height)
        val rotated = rig.main.sensorOrientation % 180 != 0
        val eyeWidth = if (rotated) mainSize.height else mainSize.width
        val eyeHeight = if (rotated) mainSize.width else mainSize.height
        outputSize = Size(eyeWidth * 2, eyeHeight)
    }

    fun setPreviewSurface(surface: Surface?) = runSync {
        previewSurface?.let {
            egl.makeCurrent(idleSurface)
            egl.releaseSurface(it)
        }
        previewSurface = surface?.let { egl.createWindowSurface(it) }
    }

    fun startRecording(surface: Surface) = runSync {
        recordSurface = egl.createWindowSurface(surface)
        lastRecordPtsNs = 0L
    }

    fun stopRecording() = runSync {
        recordSurface?.let {
            egl.makeCurrent(idleSurface)
            egl.releaseSurface(it)
        }
        recordSurface = null
    }

    /**
     * Снимает следующий кадр SBS в полном разрешении.
     * Колбэк вызывается на потоке рендерера с пикселями RGBA сверху вниз.
     */
    fun capturePhoto(callback: (pixels: ByteBuffer, width: Int, height: Int) -> Unit) {
        handler.post { pendingPhoto = callback }
    }

    fun release() {
        runSync {
            released = true
            previewSurface?.let { egl.releaseSurface(it) }
            recordSurface?.let { egl.releaseSurface(it) }
            previewSurface = null
            recordSurface = null
            wideSurface.release()
            mainSurface.release()
            wideTexture.release()
            mainTexture.release()
            GLES20.glDeleteTextures(2, intArrayOf(wideTexId, mainTexId), 0)
            GLES20.glDeleteProgram(program)
            egl.releaseSurface(idleSurface)
            egl.release()
        }
        thread.quitSafely()
    }

    private fun initGl() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        uMap = GLES20.glGetUniformLocation(program, "uMap")
        uFlipY = GLES20.glGetUniformLocation(program, "uFlipY")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")

        // x, y (clip space), u, v (координаты кадра, v вниз)
        val data = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
        quad = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(data).apply { position(0) }

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        for (id in ids) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        wideTexId = ids[0]
        mainTexId = ids[1]
        GlUtil.checkError("init textures")

        wideTexture = SurfaceTexture(wideTexId).apply { setOnFrameAvailableListener({ onFrame(it) }, handler) }
        mainTexture = SurfaceTexture(mainTexId).apply { setOnFrameAvailableListener({ onFrame(it) }, handler) }
        wideSurface = Surface(wideTexture)
        mainSurface = Surface(mainTexture)
    }

    private fun onFrame(texture: SurfaceTexture) {
        if (released) return
        try {
            egl.makeCurrent(idleSurface)
            texture.updateTexImage()
            if (texture === wideTexture) {
                wideTexture.getTransformMatrix(wideMatrix)
                wideTimestamp = wideTexture.timestamp
                wideFresh = true
            } else {
                mainTexture.getTransformMatrix(mainMatrix)
                mainTimestamp = mainTexture.timestamp
                mainFresh = true
            }
            // Кадры обеих камер приходят из одного запроса — рисуем, когда пришла пара
            if (wideFresh && mainFresh) {
                wideFresh = false
                mainFresh = false
                drawFrame()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка рендеринга кадра", e)
        }
    }

    private fun drawFrame() {
        val skew = abs(wideTimestamp - mainTimestamp)
        val size = outputSize

        recordSurface?.let { surface ->
            egl.makeCurrent(surface)
            drawSbs(size.width, size.height, flipY = false)
            egl.setPresentationTime(surface, toMonotonicNs(mainTimestamp))
            egl.swapBuffers(surface)
        }

        pendingPhoto?.let { callback ->
            pendingPhoto = null
            egl.makeCurrent(idleSurface)
            val pixels = renderOffscreen(size.width, size.height)
            callback(pixels, size.width, size.height)
        }

        previewSurface?.let { surface ->
            egl.makeCurrent(surface)
            val (w, h) = egl.querySize(surface)
            drawSbs(w, h, flipY = false)
            egl.swapBuffers(surface)
        }

        updateStats(skew)
    }

    /** Время кадра камеры (может быть в базе elapsedRealtime) → System.nanoTime(), как ждёт MediaRecorder. */
    private fun toMonotonicNs(cameraNs: Long): Long {
        var pts = if (rig.timestampSourceRealtime) {
            cameraNs - (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
        } else {
            cameraNs
        }
        if (pts <= lastRecordPtsNs) pts = lastRecordPtsNs + 1_000
        lastRecordPtsNs = pts
        return pts
    }

    private fun renderOffscreen(width: Int, height: Int): ByteBuffer {
        val fbo = IntArray(1)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex[0], 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Framebuffer не готов"
        }

        // Рисуем перевёрнутым, чтобы glReadPixels вернул строки сверху вниз
        drawSbs(width, height, flipY = true)
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GlUtil.checkError("glReadPixels")

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fbo, 0)
        GLES20.glDeleteTextures(1, tex, 0)
        pixels.rewind()
        return pixels
    }

    private fun drawSbs(width: Int, height: Int, flipY: Boolean) {
        val cal = calibration
        val wideLeft = rig.wideIsLeft != cal.swapEyes
        val half = width / 2

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uFlipY, if (flipY) -1f else 1f)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)

        GLES20.glViewport(0, 0, half, height)
        if (wideLeft) drawWide(cal) else drawMain()
        GLES20.glViewport(half, 0, width - half, height)
        if (wideLeft) drawMain() else drawWide(cal)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    private fun drawMain() {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mainTexId)
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, mainMatrix, 0)
        GLES20.glUniform4f(uMap, 1f, 0f, 1f, 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Широкоугольный кадр увеличиваем так, чтобы поле зрения совпало с основной камерой,
     * и совмещаем оптические центры: точка (u, v) основного кадра берётся из
     * (pw + (u - pm) * s) широкоугольного.
     */
    private fun drawWide(cal: Calibration) {
        val s = 1f / (rig.wideCropFactor * cal.scale)
        val ou = rig.wide.principalU - rig.main.principalU * s
        val ov = rig.wide.principalV - rig.main.principalV * s + cal.verticalShift
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, wideTexId)
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, wideMatrix, 0)
        GLES20.glUniform4f(uMap, s, ou, s, ov)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun updateStats(skewNs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (statsStart == 0L) statsStart = now
        statsFrames++
        statsMaxSkewNs = maxOf(statsMaxSkewNs, skewNs)
        val elapsed = now - statsStart
        if (elapsed >= 1000) {
            listener.onStats(statsFrames * 1000f / elapsed, statsMaxSkewNs / 1_000_000f)
            statsStart = now
            statsFrames = 0
            statsMaxSkewNs = 0
        }
    }

    private fun runSync(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) {
            block()
            return
        }
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(3, TimeUnit.SECONDS)) { "Поток рендерера не ответил" }
        error?.let { throw it }
    }

    companion object {
        private const val TAG = "StereoRenderer"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aUv;
            uniform mat4 uStMatrix;
            uniform vec4 uMap;
            uniform float uFlipY;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPosition.x, aPosition.y * uFlipY, 0.0, 1.0);
                // Координаты в портретном кадре камеры (v вниз) с учётом кропа и сдвига
                vec2 p = vec2(uMap.x * aUv.x + uMap.y, uMap.z * aUv.y + uMap.w);
                // Матрица SurfaceTexture уже учитывает поворот сенсора и ждёт t снизу вверх
                vTex = (uStMatrix * vec4(p.x, 1.0 - p.y, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTex;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTex);
            }
        """
    }
}
