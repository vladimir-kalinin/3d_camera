package com.example.my_3d_camera.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Открывает логическую камеру и запускает одну сессию с двумя физическими потоками.
 * Сессия не пересоздаётся при записи видео: кадры всегда идут в GL-рендерер,
 * а тот сам решает, куда их рисовать (превью, запись, фото).
 *
 * В режиме фото в сессию дополнительно добавляются два JPEG-потока в полном разрешении:
 * по кнопке обе камеры снимают полноразмерный кадр одним запросом.
 */
class StereoCamera(
    private val manager: CameraManager,
    private val rig: StereoRig,
    private val listener: Listener,
    private val mode: CaptureMode,
    /** Принудительный размер потока широкоугольной камеры (для замеров). */
    private val wideSizeOverride: Size? = null,
) {
    interface Listener {
        /** Вызывается на потоке камеры. Нужно вернуть поверхности для широкоугольной и основной камер. */
        fun onStreamSizesChosen(wideSize: Size, mainSize: Size): Pair<Surface, Surface>
        fun onStarted(wideSize: Size, mainSize: Size, stillsEnabled: Boolean)
        fun onError(message: String)
    }

    /** Полноразмерные JPEG с обеих камер, снятые одним запросом. */
    class StillPair(val wideJpeg: ByteArray, val mainJpeg: ByteArray)

    /** Вариант конфигурации сессии: размеры потоков превью и нужны ли JPEG-потоки. */
    private data class SessionPlan(val wideSize: Size, val mainSize: Size, val withStills: Boolean)

    private val thread = HandlerThread("StereoCamera").apply { start() }
    private val handler = Handler(thread.looper)
    // Если поток уже остановлен (close() во время открытия), выполняем колбэк сразу:
    // он увидит closed = true и закроет устройство, а не оставит его открытым
    private val executor = Executor { if (!handler.post(it)) it.run() }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurfaces: Pair<Surface, Surface>? = null
    private var wideReader: ImageReader? = null
    private var mainReader: ImageReader? = null
    private var pendingStill: StillCapture? = null
    @Volatile
    private var closed = false

    @SuppressLint("MissingPermission") // разрешение проверяется в MainActivity
    fun open() {
        handler.post {
            try {
                manager.openCamera(rig.logicalId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (closed) {
                            camera.close()
                            return
                        }
                        device = camera
                        startSession(camera, sessionPlans())
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "Камера отключена")
                        camera.close()
                        device = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Ошибка камеры: $error")
                        camera.close()
                        device = null
                        listener.onError("Camera error ($error)")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось открыть камеру", e)
                listener.onError("Couldn't open the camera: ${e.message}")
            }
        }
    }

    /**
     * В режиме фото резкость берётся из полноразмерных JPEG, поэтому превью можно делать
     * лёгкими потоками (30 fps). Если HAL не примет JPEG-потоки — откатываемся на фото
     * из кадра превью с большим потоком широкоугольной камеры.
     */
    private fun sessionPlans(): List<SessionPlan> {
        val video = rig.streamSizeCandidates(CaptureMode.VIDEO, wideSizeOverride)
        if (mode == CaptureMode.VIDEO) return video.map { SessionPlan(it.first, it.second, false) }
        val photo = rig.streamSizeCandidates(CaptureMode.PHOTO, wideSizeOverride)
        val stills = if (rig.wide.jpegSize != null && rig.main.jpegSize != null) {
            video.map { SessionPlan(it.first, it.second, true) }
        } else {
            emptyList()
        }
        return stills + photo.map { SessionPlan(it.first, it.second, false) }
    }

    private fun startSession(camera: CameraDevice, plans: List<SessionPlan>) {
        val plan = plans.firstOrNull { isSupported(camera, it) }
        if (plan == null) {
            listener.onError("The camera doesn't support two concurrent streams")
            return
        }
        Log.i(TAG, "Потоки: wide=${plan.wideSize}, main=${plan.mainSize}, " +
                "JPEG=${if (plan.withStills) "${rig.wide.jpegSize} + ${rig.main.jpegSize}" else "нет"}")

        val surfaces = listener.onStreamSizesChosen(plan.wideSize, plan.mainSize)
        previewSurfaces = surfaces
        val outputs = mutableListOf(
            OutputConfiguration(surfaces.first).apply { setPhysicalCameraId(rig.wide.id) },
            OutputConfiguration(surfaces.second).apply { setPhysicalCameraId(rig.main.id) },
        )
        closeReaders()
        if (plan.withStills) {
            val wideJpeg = rig.wide.jpegSize!!
            val mainJpeg = rig.main.jpegSize!!
            wideReader = ImageReader.newInstance(wideJpeg.width, wideJpeg.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ onStillImage(it, isWide = true) }, handler)
            }
            mainReader = ImageReader.newInstance(mainJpeg.width, mainJpeg.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ onStillImage(it, isWide = false) }, handler)
            }
            outputs += stillOutput(wideReader!!.surface, rig.wide.id)
            outputs += stillOutput(mainReader!!.surface, rig.main.id)
        }

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) {
                        s.close()
                        return
                    }
                    session = s
                    try {
                        s.setRepeatingRequest(buildRequest(camera).build(), null, handler)
                        listener.onStarted(plan.wideSize, plan.mainSize, plan.withStills)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось запустить поток кадров", e)
                        listener.onError("Couldn't start the camera: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "Сессия не настроилась для $plan, пробуем следующий вариант")
                    val rest = plans.dropWhile { it != plan }.drop(1)
                    if (!closed) startSession(camera, rest)
                }
            }
        )
        camera.createCaptureSession(config)
    }

    private fun isSupported(camera: CameraDevice, plan: SessionPlan): Boolean {
        val outputs = mutableListOf(
            OutputConfiguration(plan.wideSize, SurfaceTexture::class.java).apply { setPhysicalCameraId(rig.wide.id) },
            OutputConfiguration(plan.mainSize, SurfaceTexture::class.java).apply { setPhysicalCameraId(rig.main.id) },
        )
        // Для проверки JPEG-потоков нужны настоящие поверхности — создаём временные ImageReader
        val probes = if (plan.withStills) {
            listOf(rig.wide.jpegSize!!, rig.main.jpegSize!!).map { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 1) }
        } else {
            emptyList()
        }
        probes.forEachIndexed { i, reader ->
            outputs += stillOutput(reader.surface, if (i == 0) rig.wide.id else rig.main.id)
        }
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = Unit
                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
            })
        return try {
            camera.isSessionConfigurationSupported(config)
        } catch (e: Exception) {
            // Проверка не поддерживается — узнаем по onConfigureFailed
            Log.w(TAG, "isSessionConfigurationSupported недоступен: ${e.message}")
            true
        } finally {
            probes.forEach { it.close() }
        }
    }

    /** JPEG-поток с пометкой «фото»: HAL может выбрать для него более качественную обработку. */
    private fun stillOutput(surface: Surface, physicalId: String) = OutputConfiguration(surface).apply {
        setPhysicalCameraId(physicalId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setStreamUseCase(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong())
        }
    }

    /**
     * Повторяющийся запрос (или основа снимка). В режиме фото — шаблон превью и плавающий fps:
     * видеопресет держит выдержку не длиннее 1/30 с, и в темноте AE поднимает ISO,
     * а шумодав съедает детали.
     */
    private fun buildRequest(camera: CameraDevice, template: Int = defaultTemplate()): CaptureRequest.Builder {
        val (wideSurface, mainSurface) = previewSurfaces!!
        val physicalIds = setOf(rig.wide.id, rig.main.id)
        val photo = mode == CaptureMode.PHOTO
        return camera.createCaptureRequest(template, physicalIds).apply {
            addTarget(wideSurface)
            addTarget(mainSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE,
                if (photo) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, if (photo) rig.photoFpsRange else rig.fpsRange)
            // Стабилизация смещает кадры камер независимо друг от друга и ломает стерео
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            // Коррекция дисторсии нужна, чтобы геометрия широкоугольной совпадала с основной
            set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
        }
    }

    private fun defaultTemplate() =
        if (mode == CaptureMode.PHOTO) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD

    // ---------- Полноразмерное фото ----------

    private inner class StillCapture(val callback: (StillPair?) -> Unit) {
        var wide: ByteArray? = null
        var main: ByteArray? = null
        val timeout = Runnable { finishStill(null) }
    }

    /**
     * Снимает полноразмерный кадр с обеих камер одним запросом.
     * Колбэк вызывается на потоке камеры; null — если снять не удалось.
     */
    fun captureStill(callback: (StillPair?) -> Unit) {
        handler.post {
            val camera = device
            val s = session
            val wr = wideReader
            val mr = mainReader
            if (camera == null || s == null || wr == null || mr == null || pendingStill != null) {
                callback(null)
                return@post
            }
            val still = StillCapture(callback)
            pendingStill = still
            try {
                val request = buildRequest(camera, CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(wr.surface)
                    addTarget(mr.surface)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    // Поворачиваем сами при сборке SBS, поэтому JPEG — в ориентации сенсора
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                }.build()
                s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        Log.e(TAG, "Снимок не удался: ${failure.reason}")
                        finishStill(null)
                    }
                }, handler)
                handler.postDelayed(still.timeout, STILL_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось сделать снимок", e)
                finishStill(null)
            }
        }
    }

    private fun onStillImage(reader: ImageReader, isWide: Boolean) {
        val image = reader.acquireNextImage() ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            image.close()
        }
        val still = pendingStill ?: return
        if (isWide) still.wide = bytes else still.main = bytes
        val wide = still.wide
        val main = still.main
        if (wide != null && main != null) finishStill(StillPair(wide, main))
    }

    private fun finishStill(result: StillPair?) {
        val still = pendingStill ?: return
        pendingStill = null
        handler.removeCallbacks(still.timeout)
        still.callback(result)
    }

    private fun closeReaders() {
        wideReader?.close()
        mainReader?.close()
        wideReader = null
        mainReader = null
    }

    /** Закрывает камеру и ждёт освобождения устройства. */
    fun close() {
        closed = true
        handler.post {
            try {
                session?.close()
                device?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка закрытия камеры", e)
            }
            session = null
            device = null
            finishStill(null)
            closeReaders()
        }
        thread.quitSafely()
        thread.join(2000)
    }

    companion object {
        private const val TAG = "StereoCamera"
        private const val STILL_TIMEOUT_MS = 5000L
    }
}
