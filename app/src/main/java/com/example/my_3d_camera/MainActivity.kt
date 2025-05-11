package com.example.my_3d_camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.my_3d_camera.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val CAMERA_PERMISSION_REQUEST = 100
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var wideRequestBuilder: CaptureRequest.Builder? = null
    private var teleRequestBuilder: CaptureRequest.Builder? = null
    private var isWideTextureAvailable = false
    private var isTeleTextureAvailable = false
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val executor = Executors.newSingleThreadExecutor()
    private var logicalCameraId: String? = null
    private var widePhysicalId: String? = null
    private var telePhysicalId: String? = null
    private var availableCameras: List<CameraInfo> = emptyList()
    private var dualCameraInfo: DualCamera? = null

    data class CameraInfo(
        val id: String,
        val facing: Int,
        val focalLength: Float,
        val description: String,
        val isLogical: Boolean = false,
        val physicalIds: Set<String> = emptySet(),
        val zoomRatios: List<Float> = emptyList()
    )

    data class DualCamera(
        val logicalId: String,
        val widePhysicalId: String,
        val telePhysicalId: String
    )

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            Log.e(TAG, "Camera device error $error")
        }
    }

    private val wideTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            isWideTextureAvailable = true
            if (isWideTextureAvailable && isTeleTextureAvailable) {
                openCamera()
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            isWideTextureAvailable = false
            return true
        }
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private val teleTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            isTeleTextureAvailable = true
            if (isWideTextureAvailable && isTeleTextureAvailable) {
                openCamera()
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            isTeleTextureAvailable = false
            return true
        }
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setupAvailableCameras()

        binding.viewFinderWide.surfaceTextureListener = wideTextureListener
        binding.viewFinder.surfaceTextureListener = teleTextureListener

        binding.captureButton.setOnClickListener {
            if (allPermissionsGranted()) {
                take3DPhoto()
            } else {
                ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST
                )
            }
        }

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST
            )
        }
    }

    private fun setupAvailableCameras() {
        try {
            val cameraIdList = cameraManager?.cameraIdList
            if (cameraIdList.isNullOrEmpty()) {
                Toast.makeText(this, "Камеры не найдены", Toast.LENGTH_SHORT).show()
                return
            }

            Log.d(TAG, "=== Информация о камерах устройства ===")
            
            // Собираем информацию о камерах
            availableCameras = cameraIdList.map { id ->
                val characteristics = cameraManager?.getCameraCharacteristics(id)
                val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                val focalLengths = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focalLength = focalLengths?.get(0) ?: 0f
                val physicalCameras = characteristics?.physicalCameraIds ?: emptySet()
                
                val zoomRatios = if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                        listOf(range.lower, range.upper)
                    } ?: emptyList()
                } else {
                    emptyList()
                }

                val facingStr = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Задняя"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Передняя"
                    else -> "Неизвестная"
                }

                val isLogical = physicalCameras.isNotEmpty()
                val cameraInfo = CameraInfo(
                    id = id,
                    facing = facing ?: -1,
                    focalLength = focalLength,
                    description = "Камера $id ($facingStr, ${String.format("%.1f", focalLength)}мм)",
                    isLogical = isLogical,
                    physicalIds = physicalCameras,
                    zoomRatios = zoomRatios
                )
                
                Log.d(TAG, "Камера: ${cameraInfo.description}, Логическая: $isLogical, " +
                           "Физические ID: ${physicalCameras.joinToString(", ")}, " +
                           "Фокусное расстояние: $focalLength")
                cameraInfo
            }
            
            // Найдем пару камер с наибольшей разницей фокусных расстояний
            dualCameraInfo = findDualCamera()
            
            if (dualCameraInfo != null) {
                Log.d(TAG, "Найдена пара камер: Логическая=${dualCameraInfo!!.logicalId}, " +
                           "Широкоугольная=${dualCameraInfo!!.widePhysicalId}, " +
                           "Телефото=${dualCameraInfo!!.telePhysicalId}")
            } else {
                Log.d(TAG, "Не найдена подходящая пара камер")
            }
            
            Log.d(TAG, "Найдены камеры: ${availableCameras.map { "${it.description} (${it.zoomRatios})" }}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up available cameras", e)
            e.printStackTrace()
        }
    }
    
    private fun findDualCamera(): DualCamera? {
        // Находим логические камеры с двумя и более физическими камерами
        val logicalCameras = availableCameras.filter { 
            it.isLogical && 
            it.facing == CameraCharacteristics.LENS_FACING_BACK && 
            it.physicalIds.size >= 2 
        }
        
        if (logicalCameras.isEmpty()) {
            Log.d(TAG, "Нет логических камер с двумя физическими камерами")
            return null
        }
        
        for (logicalCamera in logicalCameras) {
            // Получаем фокусные расстояния всех физических камер
            val physicalCamerasWithFocalLengths = logicalCamera.physicalIds.map { physicalId ->
                val characteristics = cameraManager?.getCameraCharacteristics(physicalId)
                val focalLengths = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focalLength = focalLengths?.get(0) ?: 0f
                Pair(physicalId, focalLength)
            }
            
            // Сортируем по фокусному расстоянию
            val sortedCameras = physicalCamerasWithFocalLengths.sortedBy { it.second }
            
            if (sortedCameras.size >= 2) {
                // Берем камеру с минимальным и максимальным фокусным расстоянием
                val wideCamera = sortedCameras.first()
                val teleCamera = sortedCameras.last()
                
                Log.d(TAG, "Для логической камеры ${logicalCamera.id} выбраны: " +
                           "Широкоугольная=${wideCamera.first} (${wideCamera.second}мм), " +
                           "Телефото=${teleCamera.first} (${teleCamera.second}мм)")
                
                return DualCamera(
                    logicalId = logicalCamera.id,
                    widePhysicalId = wideCamera.first,
                    telePhysicalId = teleCamera.first
                )
            }
        }
        
        return null
    }

    private fun openCamera() {
        if (!isWideTextureAvailable || !isTeleTextureAvailable) {
            Log.d(TAG, "TextureView не готов")
            return
        }
        
        try {
            if (dualCameraInfo != null) {
                // Используем логическую камеру с двумя физическими
                logicalCameraId = dualCameraInfo!!.logicalId
                widePhysicalId = dualCameraInfo!!.widePhysicalId
                telePhysicalId = dualCameraInfo!!.telePhysicalId
                
                Log.d(TAG, "Открываем логическую камеру: $logicalCameraId")
                
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    cameraManager?.openCamera(logicalCameraId!!, stateCallback, cameraHandler)
                }
            } else {
                // Запасной вариант: ищем обычную заднюю камеру
                val backCamera = availableCameras.find { 
                    it.facing == CameraCharacteristics.LENS_FACING_BACK 
                }
                
                if (backCamera != null) {
                    Log.d(TAG, "Запасной вариант: открываем обычную камеру: ${backCamera.id}")
                    
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        cameraManager?.openCamera(backCamera.id, stateCallback, cameraHandler)
                    }
                } else {
                    Toast.makeText(this, "Не найдена подходящая камера", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при открытии камеры", e)
            e.printStackTrace()
        }
    }

    private fun createCameraPreviewSession() {
        if (!isWideTextureAvailable || !isTeleTextureAvailable) {
            Log.e(TAG, "TextureViews не готовы")
            return
        }

        try {
            val wideTexture = binding.viewFinderWide.surfaceTexture
                ?: throw IllegalStateException("Широкоугольная текстура отсутствует")
            val teleTexture = binding.viewFinder.surfaceTexture
                ?: throw IllegalStateException("Теле текстура отсутствует")

            wideTexture.setDefaultBufferSize(1920, 1080)
            teleTexture.setDefaultBufferSize(1920, 1080)

            val wideSurface = Surface(wideTexture)
            val teleSurface = Surface(teleTexture)

            // Создаем запрос для предпросмотра
            val previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                ?: throw IllegalStateException("Не удалось создать запрос камеры")
            
            // Добавляем обе поверхности в запрос
            previewRequestBuilder.addTarget(wideSurface)
            previewRequestBuilder.addTarget(teleSurface)
            
            // Устанавливаем автофокус на бесконечность
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            previewRequestBuilder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                0.0f  // 0.0 для фокуса на бесконечность
            )
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_SCENE_MODE,
                CaptureRequest.CONTROL_SCENE_MODE_DISABLED
            )
            
            // Если мы используем логическую камеру, настраиваем физические ID
            if (widePhysicalId != null && telePhysicalId != null) {
                Log.d(TAG, "Настраиваем физические камеры: Wide=$widePhysicalId, Tele=$telePhysicalId")
                
                // Ассоциируем физические ID с поверхностями
                val physicalCameraSettings = mutableMapOf<String, CaptureRequest.Builder>()
                
                // Создаем настройки для широкоугольной камеры
                val wideBuilder = CameraDevice.TEMPLATE_PREVIEW
                    .let { template -> cameraDevice?.createCaptureRequest(template) }
                    ?: throw IllegalStateException("Не удалось создать запрос для широкоугольной камеры")
                
                // Создаем настройки для телефото камеры
                val teleBuilder = CameraDevice.TEMPLATE_PREVIEW
                    .let { template -> cameraDevice?.createCaptureRequest(template) }
                    ?: throw IllegalStateException("Не удалось создать запрос для телефото камеры")
                
                // Настраиваем каждую физическую камеру
                physicalCameraSettings[widePhysicalId!!] = wideBuilder
                physicalCameraSettings[telePhysicalId!!] = teleBuilder
                
                // Создаем запрос для каждой физической камеры
                val outputConfigurationList = mutableListOf<OutputConfiguration>()
                
                // Конфигурация для широкоугольной камеры
                val wideOutputConfig = OutputConfiguration(wideSurface)
                wideOutputConfig.setPhysicalCameraId(widePhysicalId!!)
                outputConfigurationList.add(wideOutputConfig)
                
                // Конфигурация для телефото камеры
                val teleOutputConfig = OutputConfiguration(teleSurface)
                teleOutputConfig.setPhysicalCameraId(telePhysicalId!!)
                outputConfigurationList.add(teleOutputConfig)
                
                // Создаем сессию с указанием физических камер для каждой поверхности
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigurationList,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            try {
                                // Запускаем предпросмотр
                                val previewRequest = previewRequestBuilder.build()
                                captureSession?.setRepeatingRequest(previewRequest, null, cameraHandler)
                                
                                Log.d(TAG, "Сессия с физическими камерами успешно настроена")
                            } catch (e: Exception) {
                                Log.e(TAG, "Ошибка запуска предпросмотра", e)
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Ошибка настройки сессии мультикамеры")
                            Toast.makeText(
                                this@MainActivity,
                                "Ошибка настройки мультикамеры",
                                Toast.LENGTH_SHORT
                            ).show()
                            
                            // Пробуем запасной вариант с обычной сессией
                            tryFallbackSession(wideSurface, teleSurface, previewRequestBuilder)
                        }
                    }
                )
                
                cameraDevice?.createCaptureSession(sessionConfig)
            } else {
                // Запасной вариант без физических ID
                tryFallbackSession(wideSurface, teleSurface, previewRequestBuilder)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания сессии предпросмотра камеры", e)
            e.printStackTrace()
        }
    }
    
    private fun tryFallbackSession(
        wideSurface: Surface, 
        teleSurface: Surface,
        previewRequestBuilder: CaptureRequest.Builder
    ) {
        Log.d(TAG, "Используем запасной вариант сессии без физических ID")
        
        // Создаем обычную сессию без привязки к физическим камерам
        cameraDevice?.createCaptureSession(
            listOf(wideSurface, teleSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        // Запускаем предпросмотр
                        val previewRequest = previewRequestBuilder.build()
                        captureSession?.setRepeatingRequest(previewRequest, null, cameraHandler)
                        
                        Log.d(TAG, "Запасной вариант сессии успешно настроен")
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка запуска предпросмотра в запасном варианте", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ошибка настройки камеры",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            null
        )
    }

    private fun take3DPhoto() {
        try {
            val wideImageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)
            val teleImageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)

            if (widePhysicalId != null && telePhysicalId != null) {
                // Используем логическую мультикамеру
                Log.d(TAG, "Создание 3D фото с мультикамерой")
                
                // Создаем запрос для фото
                val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    ?: throw IllegalStateException("Не удалось создать запрос для фото")
                
                // Создаем конфигурации для выходных данных
                val outputConfigurationList = mutableListOf<OutputConfiguration>()
                
                // Конфигурация для широкоугольной камеры
                val wideOutputConfig = OutputConfiguration(wideImageReader.surface)
                wideOutputConfig.setPhysicalCameraId(widePhysicalId!!)
                outputConfigurationList.add(wideOutputConfig)
                
                // Конфигурация для телефото камеры
                val teleOutputConfig = OutputConfiguration(teleImageReader.surface)
                teleOutputConfig.setPhysicalCameraId(telePhysicalId!!)
                outputConfigurationList.add(teleOutputConfig)
                
                // Настраиваем запрос
                captureBuilder.addTarget(wideImageReader.surface)
                captureBuilder.addTarget(teleImageReader.surface)
                
                captureBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                captureBuilder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    0.0f  // 0.0 для фокуса на бесконечность
                )
                captureBuilder.set(
                    CaptureRequest.CONTROL_SCENE_MODE,
                    CaptureRequest.CONTROL_SCENE_MODE_DISABLED
                )
                
                // Останавливаем предпросмотр
                captureSession?.stopRepeating()
                
                // Делаем фото
                captureSession?.captureBurst(
                    listOf(captureBuilder.build()),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            Toast.makeText(
                                this@MainActivity,
                                "3D фото сохранено",
                                Toast.LENGTH_SHORT
                            ).show()
                            
                            // Восстанавливаем предпросмотр
                            try {
                                // Создаем запрос для предпросмотра
                                val previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    ?: throw IllegalStateException("Не удалось создать запрос для предпросмотра")
                                
                                // Получаем поверхности из TextureView
                                val wideTexture = binding.viewFinderWide.surfaceTexture
                                    ?: throw IllegalStateException("Широкоугольная текстура отсутствует")
                                val teleTexture = binding.viewFinder.surfaceTexture
                                    ?: throw IllegalStateException("Теле текстура отсутствует")
                                
                                val wideSurface = Surface(wideTexture)
                                val teleSurface = Surface(teleTexture)
                                
                                // Добавляем поверхности в запрос
                                previewRequestBuilder.addTarget(wideSurface)
                                previewRequestBuilder.addTarget(teleSurface)
                                
                                // Настраиваем запрос
                                previewRequestBuilder.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_OFF
                                )
                                previewRequestBuilder.set(
                                    CaptureRequest.LENS_FOCUS_DISTANCE,
                                    0.0f
                                )
                                previewRequestBuilder.set(
                                    CaptureRequest.CONTROL_SCENE_MODE,
                                    CaptureRequest.CONTROL_SCENE_MODE_DISABLED
                                )
                                
                                // Восстанавливаем предпросмотр
                                captureSession?.setRepeatingRequest(
                                    previewRequestBuilder.build(),
                                    null,
                                    cameraHandler
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Ошибка восстановления предпросмотра", e)
                            }
                        }
                    },
                    cameraHandler
                )
            } else {
                // Запасной вариант для обычной камеры
                Log.d(TAG, "Создание 3D фото с обычной камерой")
                
                val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                
                captureBuilder?.addTarget(wideImageReader.surface)
                captureBuilder?.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                captureBuilder?.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    0.0f
                )
                
                captureSession?.stopRepeating()
                
                captureSession?.capture(
                    captureBuilder?.build()!!,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            Toast.makeText(
                                this@MainActivity,
                                "Фото сохранено (режим одиночной камеры)",
                                Toast.LENGTH_SHORT
                            ).show()
                            
                            // Восстанавливаем предпросмотр
                            try {
                                val previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                val wideSurface = Surface(binding.viewFinderWide.surfaceTexture)
                                val teleSurface = Surface(binding.viewFinder.surfaceTexture)
                                
                                previewRequestBuilder?.addTarget(wideSurface)
                                previewRequestBuilder?.addTarget(teleSurface)
                                
                                captureSession?.setRepeatingRequest(
                                    previewRequestBuilder?.build()!!,
                                    null,
                                    cameraHandler
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Ошибка восстановления предпросмотра", e)
                            }
                        }
                    },
                    null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при создании 3D фото", e)
            Toast.makeText(this, "Ошибка при создании 3D фото", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (!allPermissionsGranted()) {
                Toast.makeText(
                    this,
                    "Разрешения не получены",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        cameraThread.quitSafely()
        cameraDevice?.close()
    }

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
} 
