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
 * Камеры отдают кадры в две SurfaceTexture. Кадры двух камер приходят в произвольном
 * порядке и с разбросом до нескольких кадров, поэтому каждый кадр сразу забирается
 * у камеры (иначе ей не хватит буферов и она начнёт пропускать кадры) и рисуется
 * уже обрезанным в кольцевой буфер «глаза». Из этих буферов собираются пары кадров
 * с одинаковым временем съёмки и рисуются в превью, в энкодер и в фото.
 */
class StereoRenderer(private val rig: StereoRig, private val listener: Listener) {

    interface Listener {
        /** Вызывается раз в секунду с потока рендерера. */
        fun onStats(stats: Stats)
    }

    data class Stats(
        /** Сколько пар кадров в секунду собрано. */
        val fps: Float,
        /** Наибольшая разница времени съёмки внутри пары. */
        val maxPairSkewMs: Float,
        /** Кадры, выброшенные без пары. */
        val droppedFrames: Int,
        /** Сколько кадров за секунду пришло от каждой камеры. */
        val wideFrames: Int,
        val mainFrames: Int,
    ) {
        /** Одна из камер перестала отдавать кадры (так бывает при перегреве телефона). */
        val cameraStalled: Boolean get() = (wideFrames == 0) != (mainFrames == 0)
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

    /** Обрезанный кадр одной камеры, ждущий пару. */
    private class EyeFrame(val texture: Int, val framebuffer: Int) {
        var timestamp = 0L
        var valid = false
    }

    /** Камера: её SurfaceTexture и кольцевой буфер обрезанных кадров. */
    private inner class EyeStream(val isWide: Boolean) {
        val oesTexture = GlUtil.createExternalTexture()
        val surfaceTexture = SurfaceTexture(oesTexture).apply {
            setOnFrameAvailableListener({ onFrameAvailable(this@EyeStream) }, handler)
        }
        val surface = Surface(surfaceTexture)
        val stMatrix = FloatArray(16)
        var frames: List<EyeFrame> = emptyList()

        fun allocate(width: Int, height: Int) {
            releaseFrames()
            frames = List(RING_SIZE) {
                val tex = GlUtil.createTexture2D(width, height)
                EyeFrame(tex, GlUtil.createFramebuffer(tex))
            }
        }

        /** Слот для нового кадра: свободный, а если таких нет — самый старый. */
        fun freeSlot(): EyeFrame {
            frames.firstOrNull { !it.valid }?.let { return it }
            statsDropped++
            return frames.minBy { it.timestamp }
        }

        fun releaseFrames() {
            for (frame in frames) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(frame.framebuffer), 0)
                GLES20.glDeleteTextures(1, intArrayOf(frame.texture), 0)
            }
            frames = emptyList()
        }

        fun release() {
            releaseFrames()
            surface.release()
            surfaceTexture.release()
            GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
        }
    }

    private val thread = HandlerThread("StereoGL").apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private lateinit var idleSurface: EGLSurface
    private lateinit var cameraProgram: CameraProgram
    private lateinit var composeProgram: ComposeProgram
    private lateinit var quad: FloatBuffer
    private lateinit var wide: EyeStream
    private lateinit var main: EyeStream

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
    private var statsDropped = 0
    private var statsWideFrames = 0
    private var statsMainFrames = 0
    private var released = false

    /** Инициализирует GL и возвращает поверхности для камер (wide, main). */
    fun start(): Pair<Surface, Surface> {
        runSync {
            egl = EglCore()
            idleSurface = egl.createPbufferSurface(1, 1)
            egl.makeCurrent(idleSurface)
            cameraProgram = CameraProgram()
            composeProgram = ComposeProgram()
            // x, y (clip space), u, v (координаты кадра, v вниз)
            val data = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f,
            )
            quad = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(data).apply { position(0) }
            wide = EyeStream(isWide = true)
            main = EyeStream(isWide = false)
        }
        return wide.surface to main.surface
    }

    /** Задаёт размеры потоков камер. Итоговый кадр = два портретных кадра основной камеры рядом. */
    fun setStreamSizes(wideSize: Size, mainSize: Size) = runSync {
        wide.surfaceTexture.setDefaultBufferSize(wideSize.width, wideSize.height)
        main.surfaceTexture.setDefaultBufferSize(mainSize.width, mainSize.height)
        val rotated = rig.main.sensorOrientation % 180 != 0
        val eyeWidth = if (rotated) mainSize.height else mainSize.width
        val eyeHeight = if (rotated) mainSize.width else mainSize.height
        wide.allocate(eyeWidth, eyeHeight)
        main.allocate(eyeWidth, eyeHeight)
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
            egl.makeCurrent(idleSurface)
            previewSurface?.let { egl.releaseSurface(it) }
            recordSurface?.let { egl.releaseSurface(it) }
            previewSurface = null
            recordSurface = null
            wide.release()
            main.release()
            cameraProgram.release()
            composeProgram.release()
            egl.releaseSurface(idleSurface)
            egl.release()
        }
        thread.quitSafely()
    }

    // ---------- Приём кадров ----------

    private fun onFrameAvailable(stream: EyeStream) {
        if (released) return
        try {
            egl.makeCurrent(idleSurface)
            // Забираем кадр сразу, чтобы буфер вернулся камере
            stream.surfaceTexture.updateTexImage()
            if (stream.isWide) statsWideFrames++ else statsMainFrames++
            stream.surfaceTexture.getTransformMatrix(stream.stMatrix)
            if (stream.frames.isEmpty()) return

            val slot = stream.freeSlot()
            renderEye(stream, slot)
            slot.timestamp = stream.surfaceTexture.timestamp
            slot.valid = true
            drawReadyPairs()
            reportStats()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки кадра", e)
        }
    }

    /** Рисует кадр камеры с кропом и совмещением в текстуру глаза. */
    private fun renderEye(stream: EyeStream, target: EyeFrame) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        val size = outputSize
        GLES20.glViewport(0, 0, size.width / 2, size.height)
        if (stream.isWide) {
            // Широкоугольный кадр увеличиваем до поля зрения основной камеры и совмещаем
            // оптические центры: точка (u, v) основного кадра берётся из pw + (u - pm) * s
            val cal = calibration
            val s = 1f / (rig.wideCropFactor * cal.scale)
            val ou = rig.wide.principalU - rig.main.principalU * s
            val ov = rig.wide.principalV - rig.main.principalV * s + cal.verticalShift
            cameraProgram.draw(quad, stream.oesTexture, stream.stMatrix, s, ou, s, ov)
        } else {
            cameraProgram.draw(quad, stream.oesTexture, stream.stMatrix, 1f, 0f, 1f, 0f)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Ищет пары кадров с одинаковым временем съёмки и рисует их по порядку.
     * Кадры старше нарисованной пары уже не найдут себе пару — их выбрасываем.
     */
    private fun drawReadyPairs() {
        while (true) {
            var bestWide: EyeFrame? = null
            var bestMain: EyeFrame? = null
            for (w in wide.frames) {
                if (!w.valid) continue
                val m = main.frames.firstOrNull { it.valid && abs(it.timestamp - w.timestamp) <= PAIR_TOLERANCE_NS }
                    ?: continue
                if (bestWide == null || w.timestamp < bestWide.timestamp) {
                    bestWide = w
                    bestMain = m
                }
            }
            if (bestWide == null || bestMain == null) return

            val pairTime = minOf(bestWide.timestamp, bestMain.timestamp)
            for (frame in wide.frames + main.frames) {
                if (frame.valid && frame !== bestWide && frame !== bestMain && frame.timestamp < pairTime) {
                    frame.valid = false
                    statsDropped++
                }
            }
            drawPair(bestWide, bestMain)
            bestWide.valid = false
            bestMain.valid = false
        }
    }

    private fun drawPair(wideFrame: EyeFrame, mainFrame: EyeFrame) {
        val wideLeft = rig.wideIsLeft != calibration.swapEyes
        val left = if (wideLeft) wideFrame.texture else mainFrame.texture
        val right = if (wideLeft) mainFrame.texture else wideFrame.texture
        val size = outputSize

        recordSurface?.let { surface ->
            egl.makeCurrent(surface)
            compose(left, right, size.width, size.height, flipY = false)
            egl.setPresentationTime(surface, toMonotonicNs(mainFrame.timestamp))
            egl.swapBuffers(surface)
        }

        pendingPhoto?.let { callback ->
            pendingPhoto = null
            egl.makeCurrent(idleSurface)
            callback(renderOffscreen(left, right, size.width, size.height), size.width, size.height)
        }

        previewSurface?.let { surface ->
            egl.makeCurrent(surface)
            val (w, h) = egl.querySize(surface)
            compose(left, right, w, h, flipY = false)
            egl.swapBuffers(surface)
        }

        egl.makeCurrent(idleSurface)
        statsFrames++
        statsMaxSkewNs = maxOf(statsMaxSkewNs, abs(wideFrame.timestamp - mainFrame.timestamp))
    }

    private fun compose(left: Int, right: Int, width: Int, height: Int, flipY: Boolean) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val half = width / 2
        GLES20.glViewport(0, 0, half, height)
        composeProgram.draw(quad, left, flipY)
        GLES20.glViewport(half, 0, width - half, height)
        composeProgram.draw(quad, right, flipY)
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

    private fun renderOffscreen(left: Int, right: Int, width: Int, height: Int): ByteBuffer {
        val tex = GlUtil.createTexture2D(width, height)
        val fbo = GlUtil.createFramebuffer(tex)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        // Рисуем перевёрнутым, чтобы glReadPixels вернул строки сверху вниз
        compose(left, right, width, height, flipY = true)
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GlUtil.checkError("glReadPixels")
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
        pixels.rewind()
        return pixels
    }

    /** Раз в секунду отправляет статистику. Вызывается на каждом кадре камеры, даже если пар нет. */
    private fun reportStats() {
        val now = SystemClock.elapsedRealtime()
        if (statsStart == 0L) statsStart = now
        val elapsed = now - statsStart
        if (elapsed >= 1000) {
            listener.onStats(Stats(
                fps = statsFrames * 1000f / elapsed,
                maxPairSkewMs = statsMaxSkewNs / 1_000_000f,
                droppedFrames = statsDropped,
                wideFrames = statsWideFrames,
                mainFrames = statsMainFrames,
            ))
            statsStart = now
            statsFrames = 0
            statsMaxSkewNs = 0
            statsDropped = 0
            statsWideFrames = 0
            statsMainFrames = 0
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

    // ---------- Шейдеры ----------

    /** Рисует кадр камеры (OES-текстура) с кропом в портретных координатах. */
    private class CameraProgram {
        private val program = GlUtil.createProgram(
            """
            attribute vec4 aPosition;
            attribute vec2 aUv;
            uniform mat4 uStMatrix;
            uniform vec4 uMap;
            varying vec2 vTex;
            void main() {
                gl_Position = aPosition;
                // Координаты в портретном кадре камеры (v вниз) с учётом кропа и сдвига
                vec2 p = vec2(uMap.x * aUv.x + uMap.y, uMap.z * aUv.y + uMap.w);
                // Матрица SurfaceTexture уже учитывает поворот сенсора и ждёт t снизу вверх
                vTex = (uStMatrix * vec4(p.x, 1.0 - p.y, 0.0, 1.0)).xy;
            }
            """,
            """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTex;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTex);
            }
            """
        )
        private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        private val aUv = GLES20.glGetAttribLocation(program, "aUv")
        private val uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        private val uMap = GLES20.glGetUniformLocation(program, "uMap")
        private val uTexture = GLES20.glGetUniformLocation(program, "uTexture")

        fun draw(quad: FloatBuffer, texture: Int, stMatrix: FloatArray, su: Float, ou: Float, sv: Float, ov: Float) {
            GLES20.glUseProgram(program)
            bindQuad(quad, aPosition, aUv)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
            GLES20.glUniform4f(uMap, su, ou, sv, ov)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() = GLES20.glDeleteProgram(program)
    }

    /** Рисует готовую текстуру глаза в половину кадра. */
    private class ComposeProgram {
        private val program = GlUtil.createProgram(
            """
            attribute vec4 aPosition;
            attribute vec2 aUv;
            uniform float uFlipY;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPosition.x, aPosition.y * uFlipY, 0.0, 1.0);
                vTex = vec2(aUv.x, 1.0 - aUv.y);
            }
            """,
            """
            precision highp float;
            varying vec2 vTex;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTex);
            }
            """
        )
        private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        private val aUv = GLES20.glGetAttribLocation(program, "aUv")
        private val uFlipY = GLES20.glGetUniformLocation(program, "uFlipY")
        private val uTexture = GLES20.glGetUniformLocation(program, "uTexture")

        fun draw(quad: FloatBuffer, texture: Int, flipY: Boolean) {
            GLES20.glUseProgram(program)
            bindQuad(quad, aPosition, aUv)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniform1f(uFlipY, if (flipY) -1f else 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() = GLES20.glDeleteProgram(program)
    }

    companion object {
        private const val TAG = "StereoRenderer"
        /** Кадры с разницей меньше половины кадра при 30 fps считаем одной парой. */
        private const val PAIR_TOLERANCE_NS = 15_000_000L
        /** Сколько обрезанных кадров каждой камеры можно держать в ожидании пары (~130 мс). */
        private const val RING_SIZE = 5

        private fun bindQuad(quad: FloatBuffer, aPosition: Int, aUv: Int) {
            quad.position(0)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPosition)
            quad.position(2)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aUv)
        }
    }
}
