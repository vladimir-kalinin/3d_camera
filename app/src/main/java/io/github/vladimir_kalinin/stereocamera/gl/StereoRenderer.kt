package io.github.vladimir_kalinin.stereocamera.gl

import android.graphics.RectF
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
import io.github.vladimir_kalinin.stereocamera.camera.StereoRig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

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
        /** Сдвиг широкоугольной картинки по вертикали, в долях высоты полного кадра сенсора. */
        val verticalShift: Float = 0f,
        /**
         * Горизонтальное смещение глаз друг относительно друга, в долях ширины полного кадра сенсора
         * (у потока 16:9 кадр уже, и в его координатах сдвиг больше, см. [StereoRig.Camera.portraitVisible]).
         * Модули камер стоят не идеально параллельно, и далёкие предметы без подстройки
         * двоятся. Плюс отодвигает сцену назад (содержимое глаз расходится к внешним краям).
         */
        val horizontalShift: Float = 0f,
        /** Поменять глаза местами. */
        val swapEyes: Boolean = false,
    ) {
        /** Сдвиг широкоугольной картинки по u: её содержимое уходит к внешнему краю глаза. */
        fun wideShiftU(rig: StereoRig): Float =
            if (rig.wideIsLeft != swapEyes) horizontalShift else -horizontalShift
    }

    /** Рамка глаза: где лежит кадр, скруглённое окно и радиус его углов, в пикселях. */
    class FrameShape(val image: RectF, val window: RectF, val radius: Float) {
        fun scaled(sx: Float, sy: Float) = FrameShape(
            RectF(image.left * sx, image.top * sy, image.right * sx, image.bottom * sy),
            RectF(window.left * sx, window.top * sy, window.right * sx, window.bottom * sy),
            radius * sx,
        )
    }

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

        fun allocate(width: Int, height: Int, count: Int) {
            releaseFrames()
            frames = List(count) {
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
    /** Рамка в видео задаётся на старте записи: размер кадра энкодера уже не поменять. */
    private var recordFrame = false
    private var pendingPhoto: ((ByteBuffer, Int, Int) -> Unit)? = null

    // Подгонка цвета широкоугольной под основную: замер по уменьшенным кадрам глаз
    private var measureTexture = 0
    private var measureFramebuffer = 0
    private val measurePixels = ByteBuffer.allocateDirect(MEASURE_W * 2 * MEASURE_H * 4).order(ByteOrder.nativeOrder())
    private val colorMatch = ColorMatch()
    private var pairsSinceMeasure = 0
    /** Текущие коэффициенты (R, G, B) для широкоугольного глаза. */
    private val wideGain = floatArrayOf(1f, 1f, 1f)

    @Volatile var calibration = Calibration()

    /** Превью: глаза наложены друг на друга полупрозрачно (как у обычной камеры) вместо SBS. */
    @Volatile var overlayPreview = false

    /** Рамка со скруглёнными углами вокруг каждого глаза в превью, фото и видео. */
    @Volatile var eyeFrame = false

    /** Сдвиг рамки по глубине, см. [frameShape]. */
    @Volatile var frameShift = 0f

    /** Размер итогового кадра SBS (ширина обоих глаз вместе). */
    @Volatile var outputSize = Size(0, 0)
        private set

    /** Размер превью, видео и фото из GL-кадра: с рамкой к каждому глазу добавляются поля. */
    fun framedOutputSize(frame: Boolean = eyeFrame): Size {
        val size = outputSize
        if (!frame || size.width == 0) return size
        val eye = framedSize(size.width / 2, size.height)
        return Size(eye.width * 2, eye.height)
    }
    /** Оптические центры камер в портретных координатах их потоков. */
    private var widePrincipal = rig.wide.principalU to rig.wide.principalV
    private var mainPrincipal = rig.main.principalU to rig.main.principalV
    /** Доли полного кадра камер, видные в их потоках (для пересчёта ручных сдвигов). */
    private var wideVisible = 1f to 1f
    private var mainVisible = 1f to 1f
    /** Кадры двух камер с разницей меньше половины кадра считаем одной парой. */
    private var pairToleranceNs = 1_000_000_000L / 30 / 2
    /** Замер цвета ~7 раз в секунду при любой частоте кадров. */
    private var measureEveryPairs = MEASURE_EVERY_PAIRS

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
            measureTexture = GlUtil.createTexture2D(MEASURE_W * 2, MEASURE_H)
            measureFramebuffer = GlUtil.createFramebuffer(measureTexture)
        }
        return wide.surface to main.surface
    }

    /** Задаёт размеры потоков камер. Итоговый кадр = два портретных кадра основной камеры рядом. */
    fun setStreamSizes(wideSize: Size, mainSize: Size, frameRate: Int) = runSync {
        wide.surfaceTexture.setDefaultBufferSize(wideSize.width, wideSize.height)
        main.surfaceTexture.setDefaultBufferSize(mainSize.width, mainSize.height)
        val rotated = rig.main.sensorOrientation % 180 != 0
        val eyeWidth = if (rotated) mainSize.height else mainSize.width
        val eyeHeight = if (rotated) mainSize.width else mainSize.height
        // Буфер держит одинаковое время ожидания пары (~130 мс) при любой частоте кадров
        val ringSize = maxOf(RING_SIZE_MIN, (RING_WAIT_NS * frameRate / 1_000_000_000L).toInt() + 1)
        wide.allocate(eyeWidth, eyeHeight, ringSize)
        main.allocate(eyeWidth, eyeHeight, ringSize)
        outputSize = Size(eyeWidth * 2, eyeHeight)
        // Поток 16:9 — кроп сенсора, оптические центры в его координатах другие, чем у 4:3
        widePrincipal = rig.wide.principalIn(wideSize)
        mainPrincipal = rig.main.principalIn(mainSize)
        wideVisible = rig.wide.portraitVisible(wideSize)
        mainVisible = rig.main.portraitVisible(mainSize)
        pairToleranceNs = 1_000_000_000L / frameRate / 2
        measureEveryPairs = maxOf(1, MEASURE_EVERY_PAIRS * frameRate / 30)
    }

    fun setPreviewSurface(surface: Surface?) = runSync {
        previewSurface?.let {
            egl.makeCurrent(idleSurface)
            egl.releaseSurface(it)
        }
        previewSurface = surface?.let { egl.createWindowSurface(it) }
    }

    /** [frame] — с рамкой, тогда поверхность энкодера размером [framedOutputSize]. */
    fun startRecording(surface: Surface, frame: Boolean) = runSync {
        recordFrame = frame
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
            GLES20.glDeleteFramebuffers(1, intArrayOf(measureFramebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(measureTexture), 0)
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
            val ou = widePrincipal.first - mainPrincipal.first * s + cal.wideShiftU(rig) / wideVisible.first
            val ov = widePrincipal.second - mainPrincipal.second * s + cal.verticalShift / wideVisible.second
            cameraProgram.draw(quad, stream.oesTexture, stream.stMatrix, s, ou, s, ov, wideGain)
        } else {
            cameraProgram.draw(quad, stream.oesTexture, stream.stMatrix, 1f, 0f, 1f, 0f, NO_GAIN)
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
                val m = main.frames.firstOrNull { it.valid && abs(it.timestamp - w.timestamp) < pairToleranceNs }
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
        recordSurface?.let { surface ->
            egl.makeCurrent(surface)
            val size = framedOutputSize(recordFrame)
            compose(left, right, size.width, size.height, flipY = false, recordFrame)
            egl.setPresentationTime(surface, toMonotonicNs(mainFrame.timestamp))
            egl.swapBuffers(surface)
        }

        pendingPhoto?.let { callback ->
            pendingPhoto = null
            egl.makeCurrent(idleSurface)
            val photo = framedOutputSize()
            callback(renderOffscreen(left, right, photo.width, photo.height), photo.width, photo.height)
        }

        previewSurface?.let { surface ->
            egl.makeCurrent(surface)
            val (w, h) = egl.querySize(surface)
            val frame = eyeFrame
            if (overlayPreview) overlay(left, right, w, h, frame) else compose(left, right, w, h, flipY = false, frame)
            egl.swapBuffers(surface)
        }

        egl.makeCurrent(idleSurface)
        if (++pairsSinceMeasure >= measureEveryPairs) {
            pairsSinceMeasure = 0
            updateColorMatch(wideFrame.texture, mainFrame.texture)
        }
        statsFrames++
        statsMaxSkewNs = maxOf(statsMaxSkewNs, abs(wideFrame.timestamp - mainFrame.timestamp))
    }

    /**
     * Сравнивает цвет глаз по уменьшенным кадрам и плавно подстраивает коэффициенты.
     * Замеряется уже подправленный широкоугольный кадр, поэтому коэффициент
     * домножается на остаточную разницу, пока она не сойдётся к единице.
     */
    private fun updateColorMatch(wideTexture: Int, mainTexture: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, measureFramebuffer)
        GLES20.glViewport(0, 0, MEASURE_W, MEASURE_H)
        composeProgram.draw(quad, mainTexture, flipY = false, MEASURE_W, MEASURE_H, frame = null)
        GLES20.glViewport(MEASURE_W, 0, MEASURE_W, MEASURE_H)
        composeProgram.draw(quad, wideTexture, flipY = false, MEASURE_W, MEASURE_H, frame = null)
        measurePixels.rewind()
        GLES20.glReadPixels(0, 0, MEASURE_W * 2, MEASURE_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, measurePixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        colorMatch.reset()
        val rowBytes = MEASURE_W * 2 * 4
        for (y in 0 until MEASURE_H) {
            for (x in 0 until MEASURE_W) {
                val m = y * rowBytes + x * 4
                val w = m + MEASURE_W * 4
                colorMatch.add(
                    measurePixels.get(m).toInt() and 0xFF, measurePixels.get(m + 1).toInt() and 0xFF,
                    measurePixels.get(m + 2).toInt() and 0xFF,
                    measurePixels.get(w).toInt() and 0xFF, measurePixels.get(w + 1).toInt() and 0xFF,
                    measurePixels.get(w + 2).toInt() and 0xFF,
                )
            }
        }
        val residual = colorMatch.gains() ?: return
        for (c in 0..2) {
            wideGain[c] = (wideGain[c] * residual[c].pow(GAIN_SMOOTHING)).coerceIn(MIN_GAMMA_GAIN, MAX_GAMMA_GAIN)
        }
    }

    private fun compose(left: Int, right: Int, width: Int, height: Int, flipY: Boolean, frame: Boolean) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val half = width / 2
        GLES20.glViewport(0, 0, half, height)
        composeProgram.draw(quad, left, flipY, half, height, eyeFrameShape(frame, half, height, leftEye = true))
        GLES20.glViewport(half, 0, width - half, height)
        composeProgram.draw(quad, right, flipY, width - half, height, eyeFrameShape(frame, width - half, height, leftEye = false))
    }

    /** Правый глаз поверх левого с прозрачностью 50%. */
    private fun overlay(left: Int, right: Int, width: Int, height: Int, frame: Boolean) {
        GLES20.glViewport(0, 0, width, height)
        composeProgram.draw(quad, left, flipY = false, width, height, eyeFrameShape(frame, width, height, leftEye = true))
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendColor(0f, 0f, 0f, OVERLAY_ALPHA)
        GLES20.glBlendFunc(GLES20.GL_CONSTANT_ALPHA, GLES20.GL_ONE_MINUS_CONSTANT_ALPHA)
        composeProgram.draw(quad, right, flipY = false, width, height, eyeFrameShape(frame, width, height, leftEye = false))
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Рамка глаза в пикселях области [width]×[height]: та же, что у полноразмерного глаза, в масштабе. */
    private fun eyeFrameShape(frame: Boolean, width: Int, height: Int, leftEye: Boolean): FrameShape? {
        if (!frame) return null
        val eyeWidth = outputSize.width / 2
        // Сдвиг рамки — в долях полного кадра, как у фото: у потока 16:9 глаз уже
        val shape = frameShape(eyeWidth, outputSize.height, leftEye, frameShift / mainVisible.first)
        val framed = framedSize(eyeWidth, outputSize.height)
        return shape.scaled(width.toFloat() / framed.width, height.toFloat() / framed.height)
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
        compose(left, right, width, height, flipY = true, frame = eyeFrame)
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
            uniform vec3 uGain;
            void main() {
                vec4 color = texture2D(uTexture, vTex);
                gl_FragColor = vec4(color.rgb * uGain, color.a);
            }
            """
        )
        private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        private val aUv = GLES20.glGetAttribLocation(program, "aUv")
        private val uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        private val uMap = GLES20.glGetUniformLocation(program, "uMap")
        private val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        private val uGain = GLES20.glGetUniformLocation(program, "uGain")

        fun draw(
            quad: FloatBuffer, texture: Int, stMatrix: FloatArray,
            su: Float, ou: Float, sv: Float, ov: Float, gain: FloatArray,
        ) {
            GLES20.glUseProgram(program)
            bindQuad(quad, aPosition, aUv)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
            GLES20.glUniform4f(uMap, su, ou, sv, ov)
            GLES20.glUniform3f(uGain, gain[0], gain[1], gain[2])
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() = GLES20.glDeleteProgram(program)
    }

    /**
     * Рисует готовую текстуру глаза в половину кадра.
     * С рамкой кадр рисуется в [FrameShape.image], а всё вне скруглённого окна — чёрное.
     */
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
            // Всё в пикселях области глаза: её размер, место кадра (x, y, ширина, высота),
            // центр, половина сторон и радиус окна (0 — без рамки).
            // Рамка симметрична по вертикали, поэтому направление оси y не важно
            uniform vec2 uSize;
            uniform vec4 uImage;
            uniform vec2 uCenter;
            uniform vec2 uHalf;
            uniform float uRadius;
            void main() {
                vec2 p = vTex * uSize;
                vec4 color = texture2D(uTexture, (p - uImage.xy) / uImage.zw);
                if (uRadius > 0.0) {
                    // Расстояние до скруглённого прямоугольника, сглаживание в 1 пиксель
                    vec2 q = abs(p - uCenter) - (uHalf - uRadius);
                    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
                    color.rgb *= clamp(0.5 - d, 0.0, 1.0);
                }
                gl_FragColor = color;
            }
            """
        )
        private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        private val aUv = GLES20.glGetAttribLocation(program, "aUv")
        private val uFlipY = GLES20.glGetUniformLocation(program, "uFlipY")
        private val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        private val uSize = GLES20.glGetUniformLocation(program, "uSize")
        private val uImage = GLES20.glGetUniformLocation(program, "uImage")
        private val uCenter = GLES20.glGetUniformLocation(program, "uCenter")
        private val uHalf = GLES20.glGetUniformLocation(program, "uHalf")
        private val uRadius = GLES20.glGetUniformLocation(program, "uRadius")

        fun draw(quad: FloatBuffer, texture: Int, flipY: Boolean, width: Int, height: Int, frame: FrameShape?) {
            GLES20.glUseProgram(program)
            bindQuad(quad, aPosition, aUv)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniform1f(uFlipY, if (flipY) -1f else 1f)
            GLES20.glUniform2f(uSize, width.toFloat(), height.toFloat())
            if (frame != null) {
                val image = frame.image
                val window = frame.window
                GLES20.glUniform4f(uImage, image.left, image.top, image.width(), image.height())
                GLES20.glUniform2f(uCenter, window.centerX(), window.centerY())
                GLES20.glUniform2f(uHalf, window.width() / 2, window.height() / 2)
                GLES20.glUniform1f(uRadius, frame.radius)
            } else {
                GLES20.glUniform4f(uImage, 0f, 0f, width.toFloat(), height.toFloat())
                GLES20.glUniform1f(uRadius, 0f)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() = GLES20.glDeleteProgram(program)
    }

    companion object {
        private const val TAG = "StereoRenderer"
        /** Рамка глаза: поля вокруг кадра и радиус углов в долях ширины кадра (общие с StillComposer). */
        const val FRAME_MARGIN = 0.035f
        const val FRAME_RADIUS = 0.06f

        /** Поля рамки вокруг кадра глаза в пикселях; кратны 8, чтобы размеры кадра видео остались кратны 16. */
        fun framePadding(eyeWidth: Int): Int = (FRAME_MARGIN * eyeWidth / 8).roundToInt() * 8

        /** Размер глаза вместе с полями рамки. */
        fun framedSize(eyeWidth: Int, eyeHeight: Int): Size {
            val padding = framePadding(eyeWidth)
            return Size(eyeWidth + 2 * padding, eyeHeight + 2 * padding)
        }

        /**
         * Рамка глаза в пикселях глаза с полями ([framedSize]): кадр лежит внутри полей целиком,
         * окно совпадает с ним и перекрывает только скругления углов.
         * «Плавающее окно»: сдвиг [shift] в долях ширины кадра закрывает внешний край —
         * у левого глаза левый, у правого правый, — и рамка выходит вперёд, к зрителю.
         * Левый/правый — половины SBS-кадра, как его смотрят в очках.
         */
        fun frameShape(eyeWidth: Int, eyeHeight: Int, leftEye: Boolean, shift: Float): FrameShape {
            val padding = framePadding(eyeWidth).toFloat()
            val image = RectF(padding, padding, padding + eyeWidth, padding + eyeHeight)
            val inset = shift * eyeWidth
            val window = RectF(image).apply { if (leftEye) left += inset else right -= inset }
            return FrameShape(image, window, FRAME_RADIUS * eyeWidth)
        }
        /** Сколько времени обрезанные кадры каждой камеры могут ждать пару. */
        private const val RING_WAIT_NS = 130_000_000L
        private const val RING_SIZE_MIN = 5

        /** Размер уменьшенного кадра глаза для сравнения цвета (портрет 3:4). */
        private const val MEASURE_W = 48
        private const val MEASURE_H = 64
        /** Замер цвета раз в столько пар при 30 fps (~7 раз в секунду). */
        private const val MEASURE_EVERY_PAIRS = 4
        /** Какую долю остаточной разницы убирать за один замер. */
        private const val GAIN_SMOOTHING = 0.3f
        private val MIN_GAMMA_GAIN = ColorMatch.MIN_GAIN.pow(1f / 2.2f)
        private val MAX_GAMMA_GAIN = ColorMatch.MAX_GAIN.pow(1f / 2.2f)
        private val NO_GAIN = floatArrayOf(1f, 1f, 1f)
        private const val OVERLAY_ALPHA = 0.5f

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
