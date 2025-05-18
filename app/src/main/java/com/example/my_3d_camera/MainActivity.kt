package com.example.my_3d_camera

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.my_3d_camera.databinding.ActivityMainBinding
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val CAMERA_PERMISSION_REQUEST = 100
    private var cameraManager: CameraManager? = null
    private var logicalCameraId: String? = null
    private var availableCameras: List<CameraInfo> = emptyList()
    private var dualCameraInfo: DualCamera? = null
    private var cameraDevice: CameraDevice? = null
    
    // Добавляем переменные для управления зумом
    private var minZoom: Float = 1.0f
    private var maxZoom: Float = 5.0f
    private var zoomSupported: Boolean = false

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val PREFS_NAME = "CameraZoomPrefs"
    }

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
        val mainPhysicalId: String
    )

    // Класс, объединяющий сущности для каждой камеры
    data class CameraInstance(
        val type: CameraType,             
        var physicalId: String? = null,   
        var textureView: TextureView? = null,
        var surface: Surface? = null,     
        var requestBuilder: CaptureRequest.Builder? = null,
        var zoomValue: Float = 1.0f,      
        var isTextureAvailable: Boolean = false,
        var characteristics: CameraCharacteristics? = null,
        private var cameraDevice: CameraDevice? = null,
        private var captureSession: CameraCaptureSession? = null,
        private val cameraHandler: Handler,
        private val executor: Executor
    ) {
        // Вспомогательные методы для работы с камерой
        fun updateZoom(newZoom: Float): Boolean {
            if (requestBuilder == null || captureSession == null) return false
            
            // Проверяем, нужно ли обновлять зум
            val currentZoomValue = requestBuilder?.build()?.get(CaptureRequest.CONTROL_ZOOM_RATIO)
            if (currentZoomValue != null && Math.abs(currentZoomValue - newZoom) < 0.01f) {
                Log.d(TAG, "Пропускаем обновление зума для ${type.name} - значение не изменилось")
                return false
            }
            
            try {
                // Обновляем значение зума
                zoomValue = newZoom
                requestBuilder?.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomValue)
                
                // Применяем новый запрос
                captureSession?.setRepeatingRequest(
                    requestBuilder!!.build(),
                    null,
                    cameraHandler
                )
                
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при обновлении зума для ${type.name}: ${e.message}")
                return false
            }
        }

        fun getOutputConfiguration(): OutputConfiguration? {
            if (surface == null || physicalId == null) return null
            
            val outputConfig = OutputConfiguration(surface!!)
            outputConfig.setPhysicalCameraId(physicalId!!)
            return outputConfig
        }

        fun createAndConfigureRequestBuilder(cameraDevice: CameraDevice?, zoomSupported: Boolean): Boolean {
            try {
                Log.d(TAG, "Создаем builder для ${getDescription(type)}")
                val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    ?: return false
                
                requestBuilder = builder
                
                // Добавляем поверхность
                if (surface == null) {
                    Log.e(TAG, "Surface is null для ${getDescription(type)}")
                    return false
                }
                surface?.let { 
                    builder.addTarget(it)
                    Log.d(TAG, "Surface добавлен в builder для ${getDescription(type)}")
                }
                
                // Устанавливаем значение зума
                if (zoomSupported) {
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomValue)
                }
                
                // Настраиваем параметры камеры
                configureCameraControls()
                Log.d(TAG, "Builder успешно настроен для ${getDescription(type)}")
                
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при создании builder'а для ${type.name}: ${e.message}")
                e.printStackTrace()
                return false
            }
        }

        fun setBufferSize(width: Int, height: Int) {
            textureView?.surfaceTexture?.setDefaultBufferSize(width, height)
        }

        fun prepareSurface(): Boolean {
            val texture = textureView?.surfaceTexture
            if (texture == null) {
                Log.e(TAG, "SurfaceTexture is null для ${getDescription(type)}")
                return false
            }
            surface = Surface(texture)
            Log.d(TAG, "Surface создан для ${getDescription(type)}")
            return true
        }

        fun configureTransform(windowManager: WindowManager) {
            val textureView = this.textureView ?: return
            val viewWidth = textureView.width
            val viewHeight = textureView.height
            
            // Получаем размеры из характеристик камеры
            val streamConfigurationMap = characteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val previewSizes = streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)
            val optimalSize = chooseOptimalSize(previewSizes, viewWidth, viewHeight)
            
            val matrix = android.graphics.Matrix()
            val rotation = windowManager.defaultDisplay.rotation
            
            val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            val bufferRect = android.graphics.RectF(0f, 0f, optimalSize.height.toFloat(), optimalSize.width.toFloat())
            val centerX = viewRect.centerX()
            val centerY = viewRect.centerY()
            
            if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
                val scale = Math.max(
                    viewHeight.toFloat() / optimalSize.height,
                    viewWidth.toFloat() / optimalSize.width
                )
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            } else if (Surface.ROTATION_180 == rotation) {
                matrix.postRotate(180f, centerX, centerY)
            }
            
            textureView.setTransform(matrix)
        }

        private fun configureCameraControls() {
            requestBuilder?.apply {
                // Автофокус
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                
                // Экспозиция
                set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                
                try {
                    set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, 
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                } catch (e: Exception) {
                    Log.d(TAG, "Ошибка настройки PRECAPTURE_TRIGGER: ${e.message}")
                }
                
                // Оптимизация качества
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                
                try {
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                } catch (e: Exception) {
                    Log.d(TAG, "Видеостабилизация не поддерживается: ${e.message}")
                }
                
                try {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                } catch (e: Exception) {
                    Log.d(TAG, "Оптическая стабилизация не поддерживается: ${e.message}")
                }
                
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                
                try {
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                } catch (e: Exception) {
                    Log.d(TAG, "CAPTURE_INTENT не поддерживается: ${e.message}")
                }
                
                try {
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                } catch (e: Exception) {
                    Log.d(TAG, "EDGE_MODE не поддерживается: ${e.message}")
                }
                
                try {
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, 
                        CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
                } catch (e: Exception) {
                    Log.d(TAG, "CAPTURE_INTENT не поддерживается: ${e.message}")
                }
            }
        }

        private fun chooseOptimalSize(
            choices: Array<android.util.Size>,
            viewWidth: Int,
            viewHeight: Int
        ): android.util.Size {
            val targetRatio = viewWidth.toFloat() / viewHeight
            val targetHeight = viewHeight
            val targetWidth = viewWidth
            
            if (targetWidth <= 0 || targetHeight <= 0) {
                return choices.first()
            }
            
            return choices.sortedBy { 
                Math.abs(it.width.toFloat() / it.height - targetRatio) 
            }.filter {
                it.width <= targetWidth * 1.2 && it.height <= targetHeight * 1.2
            }.maxByOrNull { it.width * it.height } ?: choices.first()
        }

        companion object {
            private const val WIDE_ZOOM_KEY = "wide_zoom"
            private const val MAIN_ZOOM_KEY = "main_zoom"

            // Получение ключа для сохранения настроек
            fun getZoomPrefsKey(type: CameraType): String {
                return when (type) {
                    CameraType.WIDE -> WIDE_ZOOM_KEY
                    CameraType.MAIN -> MAIN_ZOOM_KEY
                }
            }
            
            // Получение строкового представления типа камеры
            fun getDescription(type: CameraType): String {
                return when (type) {
                    CameraType.WIDE -> "Широкоугольная"
                    CameraType.MAIN -> "Основная"
                }
            }
        }

        fun setupTextureListener(onTexturesReady: () -> Unit): TextureView.SurfaceTextureListener {
            return object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    Log.d(TAG, "onSurfaceTextureAvailable: ${getDescription(type)}, width=$width, height=$height")
                    isTextureAvailable = true
                    texture.setDefaultBufferSize(width, height)
                    onTexturesReady()
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    Log.d(TAG, "onSurfaceTextureSizeChanged: ${getDescription(type)}, width=$width, height=$height")
                    if (characteristics != null) {
                        val streamConfigurationMap = characteristics?.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
                        val previewSizes = streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)
                        val optimalSize = chooseOptimalSize(previewSizes, width, height)
                        setBufferSize(optimalSize.width, optimalSize.height)
                    }
                }
                
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    Log.d(TAG, "onSurfaceTextureDestroyed: ${getDescription(type)}")
                    isTextureAvailable = false
                    return true
                }
                
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                    // Слишком частое событие для логирования
                }
            }
        }

        fun setupOptimalPreviewSize(windowManager: WindowManager): Pair<Int, Int>? {
            val textureView = this.textureView ?: return null
            val characteristics = this.characteristics ?: return null
            
            val streamConfigurationMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
                
            val previewSizes = streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)
            val optimalSize = chooseOptimalSize(
                previewSizes,
                textureView.width,
                textureView.height
            )

            // Настраиваем трансформацию и размер буфера
            configureTransform(windowManager)
            setBufferSize(optimalSize.width, optimalSize.height)
            
            return Pair(optimalSize.width, optimalSize.height)
        }

        fun updateZoomText(textView: android.widget.TextView) {
            textView.text = String.format("%.1fx", zoomValue)
        }

        fun updateZoomProgress(seekBar: android.widget.SeekBar, minZoom: Float, maxZoom: Float) {
            val progress = ((zoomValue - minZoom) / (maxZoom - minZoom) * 100).toInt()
            seekBar.progress = progress.coerceIn(0, 100)
        }

        fun prepareTextureWithSize(width: Int = 0, height: Int = 0): Boolean {
            if (!prepareSurface()) {
                throw IllegalStateException("${getDescription(type)} текстура отсутствует")
            }
            
            if (width > 0 && height > 0) {
                setBufferSize(width, height)
            }
            
            return true
        }

        fun createCameraSession(
            onSessionConfigured: ((CameraCaptureSession) -> Unit)? = null
        ) {
            if (!isTextureAvailable) {
                Log.e(TAG, "${getDescription(type)} текстура не готова")
                return
            }

            try {
                val outputConfig = getOutputConfiguration() ?: return
                Log.d(TAG, "Создаем сессию для ${getDescription(type)}")
                
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "Сессия ${getDescription(type)} настроена")
                            captureSession = session
                            try {
                                requestBuilder?.build()?.let { request ->
                                    captureSession?.setRepeatingRequest(
                                        request,
                                        null,
                                        cameraHandler
                                    )
                                    Log.d(TAG, "Запущен предпросмотр для ${getDescription(type)}")
                                }
                                onSessionConfigured?.invoke(session)
                            } catch (e: Exception) {
                                Log.e(TAG, "Ошибка запуска предпросмотра для ${getDescription(type)}: ${e.message}")
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Ошибка настройки сессии для ${getDescription(type)}")
                        }
                    }
                )
                
                cameraDevice?.createCaptureSession(sessionConfig)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка создания сессии для ${getDescription(type)}: ${e.message}")
            }
        }

        fun getStateCallback(onCameraOpened: () -> Unit): CameraDevice.StateCallback {
            return object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Камера ${getDescription(type)} открыта")
                    cameraDevice = camera
                    createAndConfigureRequestBuilder(camera, true)
                    onCameraOpened()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.d(TAG, "Камера ${getDescription(type)} отключена")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Ошибка устройства камеры ${getDescription(type)}: $error")
                    camera.close()
                    cameraDevice = null
                }
            }
        }

        fun close() {
            try {
                captureSession?.close()
                captureSession = null
                cameraDevice?.close()
                cameraDevice = null
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка закрытия камеры ${getDescription(type)}: ${e.message}")
            }
        }

        // Добавляем метод для кэширования размеров превью
        private var lastOptimalWidth: Int = 0
        private var lastOptimalHeight: Int = 0

        fun getCachedPreviewSize(): Pair<Int, Int>? {
            return if (lastOptimalWidth > 0 && lastOptimalHeight > 0) {
                Pair(lastOptimalWidth, lastOptimalHeight)
            } else null
        }

        fun setCachedPreviewSize(width: Int, height: Int) {
            lastOptimalWidth = width
            lastOptimalHeight = height
        }
    }
    
    // Перечисление для типов камеры
    enum class CameraType {
        WIDE,  // Широкоугольная камера
        MAIN   // Основная камера
    }
    
    // Переменные для камер
    private val wideCameraInstance = CameraInstance(
        type = CameraType.WIDE,
        cameraHandler = cameraHandler,
        executor = executor
    )
    private val mainCameraInstance = CameraInstance(
        type = CameraType.MAIN,
        cameraHandler = cameraHandler,
        executor = executor
    )
    
    // Вспомогательный метод для получения экземпляра камеры по типу
    private fun getCameraInstance(type: CameraType): CameraInstance {
        return when (type) {
            CameraType.WIDE -> wideCameraInstance
            CameraType.MAIN -> mainCameraInstance
        }
    }
    
    // Вспомогательный метод для получения списка всех экземпляров камер
    private fun getAllCameraInstances(): List<CameraInstance> {
        return listOf(wideCameraInstance, mainCameraInstance)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Восстанавливаем настройки зума
        restoreZoomSettings()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setupAvailableCameras()
        
        // Настраиваем TextureView Listeners
        setupTextureViewListeners()
        
        // Настраиваем ползунки для зума
        setupZoomControls()

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST
            )
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save_zoom -> {
                saveZoomSettings()
                Toast.makeText(this, "Настройки зума сохранены", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_reset_zoom -> {
                resetZoomSettings()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    // Метод для сброса настроек зума к значениям по умолчанию
    private fun resetZoomSettings() {
        // Устанавливаем значения по умолчанию
        wideCameraInstance.zoomValue = minZoom
        mainCameraInstance.zoomValue = minZoom * 1.5f
        
        // Ограничиваем значения диапазоном
        mainCameraInstance.zoomValue = mainCameraInstance.zoomValue.coerceIn(minZoom, maxZoom)
        
        // Обновляем UI
        updateZoomUI()
        
        // Применяем зум к камерам
        if (zoomSupported) {
            wideCameraInstance.updateZoom(wideCameraInstance.zoomValue)
            mainCameraInstance.updateZoom(mainCameraInstance.zoomValue)
        }
        
        Toast.makeText(this, "Настройки зума сброшены", Toast.LENGTH_SHORT).show()
    }
    
    // Настройка ползунков для зума
    private fun setupZoomControls() {
        // Настраиваем ползунок для широкоугольной камеры
        binding.wideZoomSeekBar.setOnSeekBarChangeListener(
            createZoomSeekBarListener(CameraType.WIDE)
        )
        
        // Настраиваем ползунок для основной камеры
        binding.mainZoomSeekBar.setOnSeekBarChangeListener(
            createZoomSeekBarListener(CameraType.MAIN)
        )
        
        // Инициализируем текстовые поля со значениями зума
        updateZoomUI()
        
        // Устанавливаем начальные значения ползунков на основе сохраненных настроек
        if (zoomSupported) {
            updateZoomUI()
        }
    }
    
    // Обновляем позиции ползунков зума и текст
    private fun updateZoomUI() {
        wideCameraInstance.apply {
            updateZoomProgress(binding.wideZoomSeekBar, minZoom, maxZoom)
            updateZoomText(binding.wideZoomValue)
        }
        mainCameraInstance.apply {
            updateZoomProgress(binding.mainZoomSeekBar, minZoom, maxZoom)
            updateZoomText(binding.mainZoomValue)
        }
    }

    // Создаем универсальный слушатель ползунка зума для обоих типов камер
    private fun createZoomSeekBarListener(type: CameraType): SeekBar.OnSeekBarChangeListener {
        var isDragging = false
        
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && zoomSupported) {
                    // Масштабируем прогресс от 0-100 к minZoom-maxZoom
                    val newZoom = minZoom + (progress / 100f) * (maxZoom - minZoom)
                    val camera = getCameraInstance(type)
                    camera.zoomValue = newZoom
                    camera.updateZoomText(when(type) {
                        CameraType.WIDE -> binding.wideZoomValue
                        CameraType.MAIN -> binding.mainZoomValue
                    })
                    
                    // Применяем зум только если не в процессе перетаскивания
                    if (!isDragging) {
                        camera.updateZoom(newZoom)
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDragging = true
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDragging = false
                // Применяем зум только после завершения перетаскивания
                if (zoomSupported) {
                    val camera = getCameraInstance(type)
                    camera.updateZoom(camera.zoomValue)
                }
            }
        }
    }
    
    private fun showAboutDialog() {
        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setTitle("Моя 3D Камера")
        dialogBuilder.setMessage(
            "Приложение для создания 3D-фотографий с использованием двух камер\n\n" +
            "Версия: 0.3\n\n" +
            "Особенности:\n" +
            "- Непрерывный автофокус для обеих камер\n" +
            "- Независимое управление зумом\n\n" +
            "Текущие настройки зума:\n" +
            "Широкоугольная камера: ${String.format("%.1fx", wideCameraInstance.zoomValue)}\n" +
            "Основная камера: ${String.format("%.1fx", mainCameraInstance.zoomValue)}"
        )
        dialogBuilder.setPositiveButton("OK", null)
        dialogBuilder.show()
    }

    // Устанавливаем TextureView Listeners
    private fun setupTextureViewListeners() {
        // Инициализируем сущности камер
        wideCameraInstance.textureView = binding.viewFinderWide
        mainCameraInstance.textureView = binding.viewFinder
        
        // Настраиваем слушатели для обеих камер
        wideCameraInstance.textureView?.surfaceTextureListener = 
            wideCameraInstance.setupTextureListener { checkAndOpenCamera() }
        
        mainCameraInstance.textureView?.surfaceTextureListener = 
            mainCameraInstance.setupTextureListener { checkAndOpenCamera() }
    }
    
    private fun checkAndOpenCamera() {
        if (areAllTexturesAvailable()) {
            openCamera()
        }
    }
    
    
    
    // Проверяем, доступны ли все текстуры
    private fun areAllTexturesAvailable(): Boolean {
        return wideCameraInstance.isTextureAvailable && mainCameraInstance.isTextureAvailable
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
        
        // Используем новый метод закрытия камер
        wideCameraInstance.close()
        mainCameraInstance.close()
    }

    // Добавляем метод для сохранения настроек зума
    private fun saveZoomSettings() {
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        
        // Сохраняем настройки для каждой камеры
        editor.putFloat(CameraInstance.getZoomPrefsKey(CameraType.WIDE), wideCameraInstance.zoomValue)
        editor.putFloat(CameraInstance.getZoomPrefsKey(CameraType.MAIN), mainCameraInstance.zoomValue)
        
        editor.apply()
        Log.d(TAG, "Сохранены настройки зума: широкоугольная=${wideCameraInstance.zoomValue}x, основная=${mainCameraInstance.zoomValue}x")
    }
    
    // Добавляем метод для восстановления настроек зума
    private fun restoreZoomSettings() {
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Восстанавливаем настройки для каждой камеры
        wideCameraInstance.zoomValue = sharedPreferences.getFloat(CameraInstance.getZoomPrefsKey(CameraType.WIDE), 1.0f)
        mainCameraInstance.zoomValue = sharedPreferences.getFloat(CameraInstance.getZoomPrefsKey(CameraType.MAIN), 1.5f)
        
        Log.d(TAG, "Восстановлены настройки зума: широкоугольная=${wideCameraInstance.zoomValue}x, основная=${mainCameraInstance.zoomValue}x")
    }

    override fun onPause() {
        super.onPause()
        // Сохраняем настройки зума при закрытии приложения
        saveZoomSettings()
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
                
                // Получаем доступные значения зума
                val zoomRatios = if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                        // Проверяем поддержку зума и сохраняем его диапазон
                        if (range.lower < range.upper) {
                            zoomSupported = true
                            minZoom = range.lower
                            maxZoom = range.upper
                            Log.d(TAG, "Диапазон зума для камеры $id: ${range.lower} - ${range.upper}")
                        }
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
                           "Основная=${dualCameraInfo!!.mainPhysicalId}")
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
                // Берем две камеры с наименьшим фокусным расстоянием
                val wideCamera = sortedCameras[0]
                val mainCamera = sortedCameras[1]
                
                Log.d(TAG, "Для логической камеры ${logicalCamera.id} выбраны: " +
                           "Широкоугольная=${wideCamera.first} (${wideCamera.second}мм), " +
                           "Основная=${mainCamera.first} (${mainCamera.second}мм)")
                
                return DualCamera(
                    logicalId = logicalCamera.id,
                    widePhysicalId = wideCamera.first,
                    mainPhysicalId = mainCamera.first
                )
            }
        }
        
        return null
    }

    private fun openCamera() {
        if (!areAllTexturesAvailable()) {
            Log.d(TAG, "TextureView не готовы")
            return
        }
        
        try {
            if (dualCameraInfo != null) {
                logicalCameraId = dualCameraInfo!!.logicalId
                wideCameraInstance.physicalId = dualCameraInfo!!.widePhysicalId
                mainCameraInstance.physicalId = dualCameraInfo!!.mainPhysicalId
                
                val logicalCharacteristics = cameraManager?.getCameraCharacteristics(logicalCameraId!!)
                
                wideCameraInstance.characteristics = logicalCharacteristics
                mainCameraInstance.characteristics = logicalCharacteristics
                
                Log.d(TAG, "Открываем логическую камеру: $logicalCameraId")
                Log.d(TAG, "Физические ID: wide=${wideCameraInstance.physicalId}, main=${mainCameraInstance.physicalId}")
                
                // Подготавливаем поверхности заранее
                wideCameraInstance.prepareSurface()
                mainCameraInstance.prepareSurface()
                
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    // Создаем конфигурацию выходов для обеих камер
                    val outputs = listOf(
                        OutputConfiguration(wideCameraInstance.surface!!).apply {
                            setPhysicalCameraId(wideCameraInstance.physicalId!!)
                        },
                        OutputConfiguration(mainCameraInstance.surface!!).apply {
                            setPhysicalCameraId(mainCameraInstance.physicalId!!)
                        }
                    )

                    // Создаем конфигурацию сессии
                    val sessionConfig = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        executor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                Log.d(TAG, "Сессия камеры настроена")
                                
                                try {
                                    // Создаем единый запрос на предпросмотр
                                    val previewRequest = session.device.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW
                                    ).apply {
                                        // Добавляем обе поверхности как цели
                                        addTarget(wideCameraInstance.surface!!)
                                        addTarget(mainCameraInstance.surface!!)
                                    }.build()

                                    // Устанавливаем повторяющийся запрос
                                    session.setRepeatingRequest(previewRequest, null, cameraHandler)
                                    Log.d(TAG, "Предпросмотр запущен для обеих камер")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Ошибка настройки предпросмотра: ${e.message}")
                                    e.printStackTrace()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Ошибка настройки сессии камеры")
                            }
                        }
                    )

                    // Открываем логическую камеру
                    cameraManager?.openCamera(
                        logicalCameraId!!,
                        object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) {
                                Log.d(TAG, "Логическая камера открыта")
                                cameraDevice = camera
                                camera.createCaptureSession(sessionConfig)
                            }

                            override fun onDisconnected(camera: CameraDevice) {
                                Log.d(TAG, "Логическая камера отключена")
                                camera.close()
                                cameraDevice = null
                            }

                            override fun onError(camera: CameraDevice, error: Int) {
                                Log.e(TAG, "Ошибка логической камеры: $error")
                                camera.close()
                                cameraDevice = null
                            }
                        },
                        cameraHandler
                    )
                }
            } else {
                Toast.makeText(this, "Не найдена подходящая камера", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при открытии камеры", e)
            e.printStackTrace()
        }
    }
} 
