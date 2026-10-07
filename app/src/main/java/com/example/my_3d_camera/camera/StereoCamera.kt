package com.example.my_3d_camera.camera

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
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
        fun onStarted(wideSize: Size, mainSize: Size)
        fun onError(message: String)
    }

    private val thread = HandlerThread("StereoCamera").apply { start() }
    private val handler = Handler(thread.looper)
    // Если поток уже остановлен (close() во время открытия), выполняем колбэк сразу:
    // он увидит closed = true и закроет устройство, а не оставит его открытым
    private val executor = Executor { if (!handler.post(it)) it.run() }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
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
                        startSession(camera, rig.streamSizeCandidates(mode, wideSizeOverride))
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
                        listener.onError("Ошибка камеры ($error)")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось открыть камеру", e)
                listener.onError("Не удалось открыть камеру: ${e.message}")
            }
        }
    }

    private fun startSession(camera: CameraDevice, candidates: List<Pair<Size, Size>>) {
        val supported = candidates.firstOrNull { (wideSize, mainSize) -> isSupported(camera, wideSize, mainSize) }
        if (supported == null) {
            listener.onError("Камера не поддерживает одновременную работу двух потоков")
            return
        }
        val (wideSize, mainSize) = supported
        Log.i(TAG, "Потоки: wide=$wideSize, main=$mainSize")

        val (wideSurface, mainSurface) = listener.onStreamSizesChosen(wideSize, mainSize)
        val outputs = listOf(
            OutputConfiguration(wideSurface).apply { setPhysicalCameraId(rig.wide.id) },
            OutputConfiguration(mainSurface).apply { setPhysicalCameraId(rig.main.id) },
        )
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
                        s.setRepeatingRequest(buildRequest(camera, wideSurface, mainSurface), null, handler)
                        listener.onStarted(wideSize, mainSize)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось запустить поток кадров", e)
                        listener.onError("Не удалось запустить камеру: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "Сессия не настроилась для wide=$wideSize main=$mainSize, пробуем меньше")
                    val rest = candidates.dropWhile { it != supported }.drop(1)
                    if (!closed) startSession(camera, rest)
                }
            }
        )
        camera.createCaptureSession(config)
    }

    private fun isSupported(camera: CameraDevice, wideSize: Size, mainSize: Size): Boolean {
        val outputs = listOf(
            OutputConfiguration(wideSize, SurfaceTexture::class.java).apply { setPhysicalCameraId(rig.wide.id) },
            OutputConfiguration(mainSize, SurfaceTexture::class.java).apply { setPhysicalCameraId(rig.main.id) },
        )
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
        }
    }

    private fun buildRequest(camera: CameraDevice, wideSurface: Surface, mainSurface: Surface): CaptureRequest {
        val physicalIds = setOf(rig.wide.id, rig.main.id)
        return camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, physicalIds).apply {
            addTarget(wideSurface)
            addTarget(mainSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, rig.fpsRange)
            // Стабилизация смещает кадры камер независимо друг от друга и ломает стерео
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            // Коррекция дисторсии нужна, чтобы геометрия широкоугольной совпадала с основной
            set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
        }.build()
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
        }
        thread.quitSafely()
        thread.join(2000)
    }

    companion object {
        private const val TAG = "StereoCamera"
    }
}
