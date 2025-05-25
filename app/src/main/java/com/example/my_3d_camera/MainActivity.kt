package com.example.my_3d_camera

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import android.media.MediaRecorder

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val CAMERA_PERMISSION_REQUEST = 100
    private var cameraManager: CameraManager? = null
    private var logicalCameraId: String? = null
    private var availableCameras: List<CameraInfo> = emptyList()
    private var dualCameraInfo: DualCamera? = null
    private var cameraDevice: CameraDevice? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var captureSession: CameraCaptureSession? = null

    // Добавляем переменные для управления кропом
    private var minCrop: Float = 1.0f
    private var maxCrop: Float = 3.0f
    private var cropSupported: Boolean = true

    // Переменные для записи видео
    private var isRecording = false
    private var wideMediaRecorder: MediaRecorder? = null
    private var mainMediaRecorder: MediaRecorder? = null
    private var wideRecordingSurface: Surface? = null
    private var mainRecordingSurface: Surface? = null
    private var wideVideoOutputFile: String? = null
    private var mainVideoOutputFile: String? = null
    
    // SBS видео процессор
    private lateinit var sbsProcessor: SBSVideoProcessor

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        private const val PREFS_NAME = "CameraCropPrefs"
        private const val WIDE_CROP_KEY = "wide_crop"
    }

    data class CameraInfo(
        val id: String,
        val facing: Int,
        val focalLength: Float,
        val description: String,
        val isLogical: Boolean = false,
        val physicalIds: Set<String> = emptySet()
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
        var cropValue: Float = 1.0f,
        var isTextureAvailable: Boolean = false,
        var characteristics: CameraCharacteristics? = null,
        private var cameraDevice: CameraDevice? = null,
        private val cameraHandler: Handler,
        private val executor: Executor
    ) {
        companion object {
            // Получение строкового представления типа камеры
            fun getDescription(type: CameraType): String {
                return when (type) {
                    CameraType.WIDE -> "Широкоугольная"
                    CameraType.MAIN -> "Основная"
                }
            }
        }

        fun getOutputConfiguration(): OutputConfiguration? {
            if (surface == null || physicalId == null) return null

            val outputConfig = OutputConfiguration(surface!!)
            outputConfig.setPhysicalCameraId(physicalId!!)
            return outputConfig
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
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return
            val previewSizes = streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)
            val optimalSize = chooseOptimalSize(previewSizes, viewWidth, viewHeight)

            val matrix = android.graphics.Matrix()
            val rotation = windowManager.defaultDisplay.rotation

            val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            val bufferRect = android.graphics.RectF(
                0f,
                0f,
                optimalSize.height.toFloat(),
                optimalSize.width.toFloat()
            )
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

                // Применяем кроп только для широкоугольной камеры
                if (type == CameraType.WIDE) {
                    // Применяем дополнительное масштабирование для кропа
                    matrix.postScale(cropValue, cropValue, centerX, centerY)
                }
            } else if (Surface.ROTATION_180 == rotation) {
                matrix.postRotate(180f, centerX, centerY)
                
                // Применяем кроп только для широкоугольной камеры
                if (type == CameraType.WIDE) {
                    // Применяем дополнительное масштабирование для кропа
                    matrix.postScale(cropValue, cropValue, centerX, centerY)
                }
            } else {
                // Для случая без поворота
                if (type == CameraType.WIDE) {
                    // Применяем дополнительное масштабирование для кропа
                    matrix.postScale(cropValue, cropValue, centerX, centerY)
                }
            }

            textureView.setTransform(matrix)
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

        fun setupTextureListener(onTexturesReady: () -> Unit): TextureView.SurfaceTextureListener {
            return object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    texture: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    Log.d(
                        TAG,
                        "onSurfaceTextureAvailable: ${getDescription(type)}, width=$width, height=$height"
                    )
                    isTextureAvailable = true
                    texture.setDefaultBufferSize(width, height)
                    onTexturesReady()
                }

                override fun onSurfaceTextureSizeChanged(
                    texture: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    Log.d(
                        TAG,
                        "onSurfaceTextureSizeChanged: ${getDescription(type)}, width=$width, height=$height"
                    )
                    if (characteristics != null) {
                        val streamConfigurationMap = characteristics?.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                        ) ?: return
                        val previewSizes =
                            streamConfigurationMap.getOutputSizes(SurfaceTexture::class.java)
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

        fun close() {
            try {
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

        // Инициализируем SBS процессор
        sbsProcessor = SBSVideoProcessor(this)

        // Устанавливаем соотношение сторон для превью
        binding.previewsContainer.post {
            val width = binding.previewsContainer.width
            val params = binding.previewsContainer.layoutParams
            params.height = width / 2 // соотношение 2:1
            binding.previewsContainer.layoutParams = params
        }

        // Восстанавливаем настройки кропа
        restoreCropSettings()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Настраиваем кнопку записи видео
        binding.captureButton.setBackgroundColor(getColor(android.R.color.holo_green_dark))
        binding.captureButton.setOnClickListener {
            toggleVideoRecording()
        }

        // Настраиваем кнопку создания SBS
        binding.sbsButton.setOnClickListener {
            showSBSCreationDialog()
        }

        // Проверяем разрешения перед инициализацией камеры
        if (!allPermissionsGranted()) {
            requestPermissions()
        } else {
            initializeCamera()
        }
    }

    private fun requestPermissions() {
        when {
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // Показываем диалог с объяснением, почему нужны разрешения
                AlertDialog.Builder(this)
                    .setTitle("Необходимы разрешения")
                    .setMessage("Для работы приложения требуется доступ к камере и микрофону для записи видео. Пожалуйста, предоставьте необходимые разрешения.")
                    .setPositiveButton("OK") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            REQUIRED_PERMISSIONS,
                            CAMERA_PERMISSION_REQUEST
                        )
                    }
                    .setNegativeButton("Отмена") { _, _ ->
                        Toast.makeText(
                            this,
                            "Без разрешений приложение не может работать",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    .create()
                    .show()
            }
            else -> {
                // Запрашиваем разрешения
                ActivityCompat.requestPermissions(
                    this,
                    REQUIRED_PERMISSIONS,
                    CAMERA_PERMISSION_REQUEST
                )
            }
        }
    }

    private fun initializeCamera() {
        setupAvailableCameras()
        setupTextureViewListeners()
        setupCropControls()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (allPermissionsGranted()) {
                // Разрешения получены, инициализируем камеру
                initializeCamera()
            } else {
                // Проверяем, можно ли показать диалог повторно
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
                    shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    requestPermissions()
                } else {
                    // Пользователь отказал в разрешениях и выбрал "Больше не спрашивать"
                    AlertDialog.Builder(this)
                        .setTitle("Разрешения не получены")
                        .setMessage("Для работы приложения необходимы разрешения. Пожалуйста, предоставьте их в настройках приложения.")
                        .setPositiveButton("Открыть настройки") { _, _ ->
                            // Открываем настройки приложения
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                            finish()
                        }
                        .setNegativeButton("Закрыть") { _, _ ->
                            finish()
                        }
                        .create()
                        .show()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    // Создаем слушатель ползунка кропа для широкоугольной камеры
    private fun createCropSeekBarListener(): SeekBar.OnSeekBarChangeListener {
        var isDragging = false

        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && cropSupported) {
                    // Масштабируем прогресс от 0-100 к minCrop-maxCrop
                    val newCrop = minCrop + (progress / 100f) * (maxCrop - minCrop)
                    wideCameraInstance.cropValue = newCrop
                    
                    // Обновляем текст кропа
                    binding.wideCropValue.text = String.format("%.1fx", newCrop)

                    // Применяем кроп через трансформацию
                    wideCameraInstance.configureTransform(windowManager)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isDragging = false
                // Применяем финальный кроп
                wideCameraInstance.configureTransform(windowManager)
            }
        }
    }

    // Настройка ползунка для кропа
    private fun setupCropControls() {
        // Настраиваем ползунок для широкоугольной камеры
        binding.wideCropSeekBar.setOnSeekBarChangeListener(createCropSeekBarListener())

        // Устанавливаем начальную позицию ползунка
        if (cropSupported) {
            binding.wideCropSeekBar.progress = ((wideCameraInstance.cropValue - minCrop) / (maxCrop - minCrop) * 100).toInt()
            binding.wideCropValue.text = String.format("%.1fx", wideCameraInstance.cropValue)
            // Применяем начальную трансформацию
            wideCameraInstance.configureTransform(windowManager)
        }
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

    override fun onDestroy() {
        super.onDestroy()
        
        // Останавливаем запись если активна
        if (isRecording) {
            stopVideoRecording()
        }
        
        // Освобождаем MediaRecorder'ы
        wideMediaRecorder?.release()
        mainMediaRecorder?.release()
        wideMediaRecorder = null
        mainMediaRecorder = null
        
        executor.shutdown()
        cameraThread.quitSafely()

        // Используем новый метод закрытия камер
        wideCameraInstance.close()
        mainCameraInstance.close()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Добавляем метод для сохранения настроек кропа
    private fun saveCropSettings() {
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putFloat(WIDE_CROP_KEY, wideCameraInstance.cropValue)
        editor.apply()
        Log.d(TAG, "Сохранены настройки кропа: широкоугольная=${wideCameraInstance.cropValue}x")
    }

    // Добавляем метод для восстановления настроек кропа
    private fun restoreCropSettings() {
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        wideCameraInstance.cropValue = sharedPreferences.getFloat(WIDE_CROP_KEY, 1.0f)
        Log.d(TAG, "Восстановлены настройки кропа: широкоугольная=${wideCameraInstance.cropValue}x")
    }

    override fun onPause() {
        super.onPause()
        // Сохраняем настройки кропа при закрытии приложения
        saveCropSettings()
    }

    // Методы для записи видео
    private fun toggleVideoRecording() {
        if (isRecording) {
            stopVideoRecording()
        } else {
            startVideoRecording()
        }
    }

    private fun startVideoRecording() {
        if (isRecording) {
            Log.d(TAG, "Запись уже в процессе")
            return
        }

        try {
            // Подготавливаем MediaRecorder'ы для обеих камер
            setupMediaRecorders()
            
            // Пересоздаем сессию камеры с поверхностями для записи
            recreateSessionForRecording()
            
            // Запускаем запись на обеих камерах
            wideMediaRecorder?.start()
            mainMediaRecorder?.start()
            isRecording = true
            
            // Обновляем UI
            binding.captureButton.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            
            Log.d(TAG, "Запись видео начата с обеих камер")
            Toast.makeText(this, "Запись 3D видео начата", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при начале записи видео", e)
            Toast.makeText(this, "Ошибка записи: ${e.message}", Toast.LENGTH_SHORT).show()
            resetRecordingState()
        }
    }

    private fun stopVideoRecording() {
        if (!isRecording) {
            Log.d(TAG, "Запись не активна")
            return
        }

        try {
            // Останавливаем запись на обеих камерах
            wideMediaRecorder?.stop()
            mainMediaRecorder?.stop()
            wideMediaRecorder?.reset()
            mainMediaRecorder?.reset()
            
            isRecording = false
            
            // Обновляем UI
            binding.captureButton.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            
            Log.d(TAG, "Запись видео остановлена")
            Log.d(TAG, "  Широкоугольная: $wideVideoOutputFile")
            Log.d(TAG, "  Основная: $mainVideoOutputFile")
            
            // Проверяем и сохраняем оба файла
            var savedCount = 0
            
            // Проверяем широкоугольное видео
            val wideFile = java.io.File(wideVideoOutputFile ?: "")
            if (wideFile.exists()) {
                Log.d(TAG, "Широкоугольное видео: ${wideFile.absolutePath}, размер: ${wideFile.length()} байт")
                addVideoToGallery(wideFile)
                savedCount++
            } else {
                Log.e(TAG, "Широкоугольное видео не найдено: ${wideFile.absolutePath}")
            }
            
            // Проверяем основное видео
            val mainFile = java.io.File(mainVideoOutputFile ?: "")
            if (mainFile.exists()) {
                Log.d(TAG, "Основное видео: ${mainFile.absolutePath}, размер: ${mainFile.length()} байт")
                addVideoToGallery(mainFile)
                savedCount++
            } else {
                Log.e(TAG, "Основное видео не найдено: ${mainFile.absolutePath}")
            }
            
            if (savedCount > 0) {
                Toast.makeText(this, "Сохранено $savedCount 3D видео в галерею", Toast.LENGTH_LONG).show()
                
                // Если оба видео сохранены, создаем SBS версию
                if (savedCount == 2 && wideFile.exists() && mainFile.exists()) {
                    sbsProcessor.createSBSVideo(
                        wideFile.absolutePath, 
                        mainFile.absolutePath,
                        object : SBSVideoProcessor.SBSProcessingCallback {
                            override fun onProcessingStarted() {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Создание SBS видео...", Toast.LENGTH_SHORT).show()
                                }
                            }
                            
                            override fun onProcessingProgress(progress: Int) {
                                runOnUiThread {
                                    // Можно добавить ProgressBar если нужно
                                    Log.d(TAG, "Прогресс SBS: $progress%")
                                }
                            }
                            
                            override fun onProcessingCompleted(outputFile: java.io.File) {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "SBS видео создано! Проверьте галерею", Toast.LENGTH_LONG).show()
                                }
                            }
                            
                            override fun onProcessingFailed(error: String) {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Ошибка создания SBS видео: $error", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            } else {
                Toast.makeText(this, "Ошибка: видео не созданы", Toast.LENGTH_SHORT).show()
            }
            
            // Возвращаемся к обычному предпросмотру
            recreateSessionForPreview()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при остановке записи видео", e)
            Toast.makeText(this, "Ошибка остановки записи: ${e.message}", Toast.LENGTH_SHORT).show()
            resetRecordingState()
        }
    }
    
    private fun addVideoToGallery(videoFile: java.io.File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    videoFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                
                Log.d(TAG, "Видео добавлено в галерею: $uri")
            } else {
                Log.e(TAG, "Не удалось создать URI для галереи")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка добавления видео в галерею", e)
        }
    }
    
    private fun showSBSCreationDialog() {
        val videoPairs = sbsProcessor.findVideoPairs()
        
        if (videoPairs.isEmpty()) {
            Toast.makeText(this, "Нет парных видео для создания SBS", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Создаем диалог выбора
        val pairNames = videoPairs.map { it.getDisplayName() }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Выберите видео для создания SBS")
            .setItems(pairNames) { _, which ->
                val selectedPair = videoPairs[which]
                if (selectedPair.isValid()) {
                    sbsProcessor.createSBSVideo(
                        selectedPair.wideVideo.absolutePath,
                        selectedPair.mainVideo.absolutePath,
                        object : SBSVideoProcessor.SBSProcessingCallback {
                            override fun onProcessingStarted() {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Создание SBS видео...", Toast.LENGTH_SHORT).show()
                                }
                            }
                            
                            override fun onProcessingProgress(progress: Int) {
                                runOnUiThread {
                                    // Можно добавить ProgressBar если нужно
                                    Log.d(TAG, "Прогресс SBS: $progress%")
                                }
                            }
                            
                            override fun onProcessingCompleted(outputFile: java.io.File) {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "SBS видео создано! Проверьте галерею", Toast.LENGTH_LONG).show()
                                }
                            }
                            
                            override fun onProcessingFailed(error: String) {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Ошибка создания SBS видео: $error", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                } else {
                    Toast.makeText(this, "Выбранные файлы недоступны", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setupMediaRecorders() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        wideVideoOutputFile = "$moviesDir/3D_Video_Wide_$timestamp.mp4"
        mainVideoOutputFile = "$moviesDir/3D_Video_Main_$timestamp.mp4"
        
        Log.d(TAG, "Настройка MediaRecorder'ов:")
        Log.d(TAG, "  Папка Movies: $moviesDir")
        Log.d(TAG, "  Широкоугольная: $wideVideoOutputFile")
        Log.d(TAG, "  Основная: $mainVideoOutputFile")
        
        // Создаем папку если не существует
        moviesDir?.mkdirs()
        
        // Получаем поддерживаемые размеры видео
        val characteristics = cameraManager?.getCameraCharacteristics(logicalCameraId!!)
        val streamConfigurationMap = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSizes = streamConfigurationMap?.getOutputSizes(MediaRecorder::class.java)
        
        Log.d(TAG, "  Доступные размеры видео: ${videoSizes?.joinToString { "${it.width}x${it.height}" }}")
        
        // Выбираем максимальный размер для сохранения полного угла обзора
        val preferredSize = videoSizes?.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720) // fallback
        
        // Получаем ориентацию дисплея для правильного поворота видео
        val rotation = windowManager.defaultDisplay.rotation
        val orientationHint = when (rotation) {
            Surface.ROTATION_0 -> 90
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
        
        Log.d(TAG, "  Выбранный размер видео: ${preferredSize.width}x${preferredSize.height}")
        Log.d(TAG, "  Ориентация дисплея: $rotation, поворот видео: $orientationHint°")
        
        // Настраиваем MediaRecorder для широкоугольной камеры
        wideMediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(wideVideoOutputFile)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(preferredSize.width, preferredSize.height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(12_000_000) // Увеличиваем битрейт для высокого разрешения
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(64000)
            setOrientationHint(orientationHint) // Исправляем поворот видео
            prepare()
        }
        
        // Настраиваем MediaRecorder для основной камеры
        mainMediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            // Аудио только в одном файле, чтобы избежать конфликтов
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(mainVideoOutputFile)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(preferredSize.width, preferredSize.height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(12_000_000) // Увеличиваем битрейт для высокого разрешения
            setOrientationHint(orientationHint) // Исправляем поворот видео
            prepare()
        }
        
        wideRecordingSurface = wideMediaRecorder?.surface
        mainRecordingSurface = mainMediaRecorder?.surface
        Log.d(TAG, "Оба MediaRecorder'а настроены успешно")
    }

    private fun resetRecordingState() {
        isRecording = false
        binding.captureButton.setBackgroundColor(getColor(android.R.color.holo_green_dark))
        
        wideMediaRecorder?.reset()
        mainMediaRecorder?.reset()
    }

    private fun recreateSessionForRecording() {
        try {
            captureSession?.close()
            
            if (dualCameraInfo != null && wideRecordingSurface != null && mainRecordingSurface != null) {
                // Создаем конфигурацию выходов для предпросмотра и записи
                val outputs = mutableListOf<OutputConfiguration>()
                
                // Добавляем поверхности для предпросмотра
                outputs.add(OutputConfiguration(wideCameraInstance.surface!!).apply {
                    setPhysicalCameraId(wideCameraInstance.physicalId!!)
                })
                outputs.add(OutputConfiguration(mainCameraInstance.surface!!).apply {
                    setPhysicalCameraId(mainCameraInstance.physicalId!!)
                })
                
                // Добавляем поверхности для записи видео с каждой камеры
                outputs.add(OutputConfiguration(wideRecordingSurface!!).apply {
                    setPhysicalCameraId(wideCameraInstance.physicalId!!)
                })
                outputs.add(OutputConfiguration(mainRecordingSurface!!).apply {
                    setPhysicalCameraId(mainCameraInstance.physicalId!!)
                })
                
                // Создаем конфигурацию сессии
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    createRecordingSessionCallback()
                )
                
                cameraDevice?.createCaptureSession(sessionConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка пересоздания сессии для записи", e)
        }
    }
    
    private fun recreateSessionForPreview() {
        try {
            captureSession?.close()
            
            if (dualCameraInfo != null) {
                // Создаем конфигурацию выходов только для предпросмотра
                val outputs = mutableListOf<OutputConfiguration>()
                
                outputs.add(OutputConfiguration(wideCameraInstance.surface!!).apply {
                    setPhysicalCameraId(wideCameraInstance.physicalId!!)
                })
                outputs.add(OutputConfiguration(mainCameraInstance.surface!!).apply {
                    setPhysicalCameraId(mainCameraInstance.physicalId!!)
                })
                
                // Создаем конфигурацию сессии
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    createPreviewSessionCallback()
                )
                
                cameraDevice?.createCaptureSession(sessionConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка пересоздания сессии для предпросмотра", e)
        }
    }

    private fun createRecordingSessionCallback(): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "Сессия камеры для записи настроена")
                
                try {
                    // Создаем запрос для записи видео
                    previewRequestBuilder = session.device.createCaptureRequest(
                        CameraDevice.TEMPLATE_RECORD,
                        setOf(wideCameraInstance.physicalId, mainCameraInstance.physicalId)
                    ).apply {
                        // Добавляем поверхности для предпросмотра
                        addTarget(wideCameraInstance.surface!!)
                        addTarget(mainCameraInstance.surface!!)
                        
                        // Добавляем поверхности для записи
                        addTarget(wideRecordingSurface!!)
                        addTarget(mainRecordingSurface!!)
                        
                        // Настройки для видео
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        
                        // Отключаем стабилизацию для синхронности между камерами
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        
                        // Отключаем оптическую стабилизацию если доступна
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                        }
                    }
                    
                    captureSession = session
                    session.setRepeatingRequest(
                        previewRequestBuilder!!.build(),
                        null,
                        cameraHandler
                    )
                    
                    Log.d(TAG, "Запрос для записи видео установлен")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка настройки записи видео: ${e.message}")
                }
            }
            
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Ошибка настройки сессии для записи")
            }
        }
    }
    
    private fun createPreviewSessionCallback(): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "Сессия камеры для предпросмотра настроена")
                
                try {
                    // Создаем обычный запрос для предпросмотра
                    previewRequestBuilder = session.device.createCaptureRequest(
                        CameraDevice.TEMPLATE_PREVIEW,
                        setOf(wideCameraInstance.physicalId, mainCameraInstance.physicalId)
                    ).apply {
                        addTarget(wideCameraInstance.surface!!)
                        addTarget(mainCameraInstance.surface!!)
                        
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        
                        // Отключаем стабилизацию для синхронности между камерами
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                        
                        // Отключаем оптическую стабилизацию если доступна
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                        }
                    }
                    
                    captureSession = session
                    session.setRepeatingRequest(
                        previewRequestBuilder!!.build(),
                        null,
                        cameraHandler
                    )
                    
                    wideCameraInstance.configureTransform(windowManager)
                    Log.d(TAG, "Предпросмотр восстановлен")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка настройки предпросмотра: ${e.message}")
                }
            }
            
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Ошибка настройки сессии для предпросмотра")
            }
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
                val focalLengths =
                    characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focalLength = focalLengths?.get(0) ?: 0f
                val physicalCameras = characteristics?.physicalCameraIds ?: emptySet()

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
                    description = "Камера $id ($facingStr, ${
                        String.format(
                            "%.1f",
                            focalLength
                        )
                    }мм)",
                    isLogical = isLogical,
                    physicalIds = physicalCameras
                )

                Log.d(
                    TAG, "Камера: ${cameraInfo.description}, Логическая: $isLogical, " +
                            "Физические ID: ${physicalCameras.joinToString(", ")}, " +
                            "Фокусное расстояние: $focalLength"
                )
                cameraInfo
            }

            // Найдем пару камер с наибольшей разницей фокусных расстояний
            dualCameraInfo = findDualCamera()

            if (dualCameraInfo != null) {
                Log.d(
                    TAG, "Найдена пара камер: Логическая=${dualCameraInfo!!.logicalId}, " +
                            "Широкоугольная=${dualCameraInfo!!.widePhysicalId}, " +
                            "Основная=${dualCameraInfo!!.mainPhysicalId}"
                )
            } else {
                Log.d(TAG, "Не найдена подходящая пара камер")
            }

            Log.d(
                TAG,
                "Найдены камеры: ${availableCameras.map { "${it.description} (${it.physicalIds})" }}"
            )
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
                val focalLengths =
                    characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focalLength = focalLengths?.get(0) ?: 0f
                Pair(physicalId, focalLength)
            }

            // Сортируем по фокусному расстоянию
            val sortedCameras = physicalCamerasWithFocalLengths.sortedBy { it.second }

            if (sortedCameras.size >= 2) {
                // Берем две камеры с наименьшим фокусным расстоянием
                val wideCamera = sortedCameras[0]
                val mainCamera = sortedCameras[1]

                Log.d(
                    TAG, "Для логической камеры ${logicalCamera.id} выбраны: " +
                            "Широкоугольная=${wideCamera.first} (${wideCamera.second}мм), " +
                            "Основная=${mainCamera.first} (${mainCamera.second}мм)"
                )

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

                val logicalCharacteristics =
                    cameraManager?.getCameraCharacteristics(logicalCameraId!!)

                wideCameraInstance.characteristics = logicalCharacteristics
                mainCameraInstance.characteristics = logicalCharacteristics

                Log.d(TAG, "Открываем логическую камеру: $logicalCameraId")
                Log.d(
                    TAG,
                    "Физические ID: wide=${wideCameraInstance.physicalId}, main=${mainCameraInstance.physicalId}"
                )

                // Подготавливаем поверхности заранее
                wideCameraInstance.prepareSurface()
                mainCameraInstance.prepareSurface()

                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    // Создаем конфигурацию выходов только для предпросмотра
                    val outputs = mutableListOf<OutputConfiguration>()
                    
                    // Добавляем поверхности для предпросмотра
                    outputs.add(OutputConfiguration(wideCameraInstance.surface!!).apply {
                        setPhysicalCameraId(wideCameraInstance.physicalId!!)
                    })
                    outputs.add(OutputConfiguration(mainCameraInstance.surface!!).apply {
                        setPhysicalCameraId(mainCameraInstance.physicalId!!)
                    })

                    // Создаем конфигурацию сессии
                    val sessionConfig = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        executor,
                        createPreviewSessionCallback()
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
