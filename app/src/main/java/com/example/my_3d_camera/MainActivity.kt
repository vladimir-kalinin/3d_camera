package com.example.my_3d_camera

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
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
import android.widget.Switch
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
import android.media.ImageReader
import com.arthenica.ffmpegkit.FFmpegKit

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

    // Переменные для съемки фото
    private var wideImageReader: ImageReader? = null
    private var mainImageReader: ImageReader? = null
    private var pendingWideBitmap: Bitmap? = null
    private var pendingMainBitmap: Bitmap? = null
    @Volatile
    private var isTakingPhoto: Boolean = false

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
        private const val SBS_ORDER_KEY = "sbs_wide_on_left"
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

        /** Устанавливает размер буфера превью в optimal size под текущий размер view. Вызывать в openCamera после prepareSurface. */
        fun preparePreviewBuffer(): Boolean {
            val tv = textureView ?: return false
            val ch = characteristics ?: return false
            val w = tv.width
            val h = tv.height
            if (w <= 0 || h <= 0) return false
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return false
            val optimal = chooseOptimalSize(sizes, w, h)
            setBufferSize(optimal.width, optimal.height)
            Log.d(TAG, "preparePreviewBuffer ${getDescription(type)}: view=${w}x${h}, buffer=${optimal.width}x${optimal.height}")
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

        fun setupTextureListener(
            onTexturesReady: () -> Unit,
            onTransformNeeded: (() -> Unit)? = null
        ): TextureView.SurfaceTextureListener {
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
                        onTransformNeeded?.invoke()
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

        // Устанавливаем соотношение сторон для превью
        binding.previewsContainer.post {
            val width = binding.previewsContainer.width
            val params = binding.previewsContainer.layoutParams
            // Две портретные камеры 3:4 рядом → общий контейнер 2:3
            // Каждое превью ≈ (width/2) x (width*2/3) → аспект 3:4, как у итогового фото
            params.height = (width * 2) / 3
            binding.previewsContainer.layoutParams = params
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Настраиваем кнопку записи видео
        binding.captureButton.setBackgroundColor(getColor(android.R.color.holo_green_dark))
        binding.captureButton.setOnClickListener {
            toggleVideoRecording()
        }
        // Кнопка для съемки 3D фото
        binding.photoButton.setOnClickListener {
            takeSbsPhoto()
        }
        
        // Настраиваем переключатель порядка SBS
        setupSBSOrderSwitch()
        
        // Настраиваем кнопку отмены обработки
        binding.cancelProcessingButton.setOnClickListener {
            // Пока что просто скрываем индикатор
            showProcessingIndicator(false)
            Toast.makeText(this, "Обработка отменена", Toast.LENGTH_SHORT).show()
        }
        

        
        // Восстанавливаем настройки после инициализации UI
        restoreSettings()

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
                    binding.wideCropValue.text = String.format("%.3fx", newCrop)

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
    
    // Настройка переключателя порядка SBS
    private fun setupSBSOrderSwitch() {
        binding.sbsOrderSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Обновляем описание в зависимости от состояния переключателя
            val description = if (isChecked) {
                "Включено: Широкоугольная слева, Основная справа"
            } else {
                "Выключено: Основная слева, Широкоугольная справа"
            }
            
            binding.sbsOrderDescription.text = description
            Log.d(TAG, "Порядок SBS изменен: $description")
        }
    }

    // Устанавливаем TextureView Listeners
    private fun setupTextureViewListeners() {
        // Инициализируем сущности камер
        wideCameraInstance.textureView = binding.viewFinderWide
        mainCameraInstance.textureView = binding.viewFinder

        val onTransformNeeded = {
            runOnUiThread {
                wideCameraInstance.configureTransform(windowManager)
                mainCameraInstance.configureTransform(windowManager)
            }
        }
        wideCameraInstance.textureView?.surfaceTextureListener =
            wideCameraInstance.setupTextureListener({ checkAndOpenCamera() }, onTransformNeeded)
        mainCameraInstance.textureView?.surfaceTextureListener =
            mainCameraInstance.setupTextureListener({ checkAndOpenCamera() }, onTransformNeeded)
    }

    private fun checkAndOpenCamera() {
        if (areAllTexturesAvailable()) {
            // Откладываем открытие камеры на следующий layout pass, чтобы контейнер
            // успел применить высоту 2:3 и оба видоискателя получили финальные размеры
            binding.previewsContainer.post { openCamera() }
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

        wideImageReader?.close()
        mainImageReader?.close()
        wideImageReader = null
        mainImageReader = null
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Добавляем метод для сохранения настроек
    private fun saveSettings() {
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putFloat(WIDE_CROP_KEY, wideCameraInstance.cropValue)
        editor.putBoolean(SBS_ORDER_KEY, binding.sbsOrderSwitch.isChecked)
        editor.apply()
        Log.d(TAG, "Сохранены настройки: кроп=${wideCameraInstance.cropValue}x, SBS порядок=${binding.sbsOrderSwitch.isChecked}")
    }

    // Добавляем метод для восстановления настроек
    private fun restoreSettings() {
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        wideCameraInstance.cropValue = sharedPreferences.getFloat(WIDE_CROP_KEY, 1.0f)
        val sbsOrder = sharedPreferences.getBoolean(SBS_ORDER_KEY, true)
        
        // Восстанавливаем состояние переключателя после инициализации UI
        binding.sbsOrderSwitch.isChecked = sbsOrder
        
        Log.d(TAG, "Восстановлены настройки: кроп=${wideCameraInstance.cropValue}x, SBS порядок=$sbsOrder")
    }

    override fun onPause() {
        super.onPause()
        // Сохраняем настройки при закрытии приложения
        saveSettings()
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
            
            // Проверяем существование файлов
            val wideFile = java.io.File(wideVideoOutputFile ?: "")
            val mainFile = java.io.File(mainVideoOutputFile ?: "")
            
            if (wideFile.exists() && mainFile.exists()) {
                Log.d(TAG, "Оба видео файла найдены")
                Log.d(TAG, "  Широкоугольное: ${wideFile.absolutePath}, размер: ${wideFile.length()} байт")
                Log.d(TAG, "  Основное: ${mainFile.absolutePath}, размер: ${mainFile.length()} байт")
                
                // Сначала сохраняем исходные видео в галерею
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                Log.d(TAG, "Сохраняем исходные видео в галерею перед сшиванием...")
                saveSourceVideosToGallery(wideFile, mainFile, timestamp)
                Toast.makeText(this, "Исходные видео сохранены в галерею!", Toast.LENGTH_SHORT).show()
                
                Log.d(TAG, "Начинаем сшивание в 3D SBS")
                // Показываем индикатор прогресса
                showProcessingIndicator(true)
                
                // Запускаем сшивание в фоновом потоке
                Thread {
                    stitchVideosToSBS(wideFile, mainFile)
                }.start()
                
            } else {
                Log.e(TAG, "Один или оба видео файла не найдены")
                if (!wideFile.exists()) {
                    Log.e(TAG, "Широкоугольное видео не найдено: ${wideFile.absolutePath}")
                }
                if (!mainFile.exists()) {
                    Log.e(TAG, "Основное видео не найдено: ${mainFile.absolutePath}")
                }
                
                runOnUiThread {
                    Toast.makeText(this, "Ошибка: видео файлы не созданы", Toast.LENGTH_SHORT).show()
                }
            }
            
            // Возвращаемся к обычному предпросмотру
            recreateSessionForPreview()
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при остановке записи видео", e)
            Toast.makeText(this, "Ошибка остановки записи: ${e.message}", Toast.LENGTH_SHORT).show()
            resetRecordingState()
        }
    }
    
    private fun stitchVideosToSBS(wideFile: java.io.File, mainFile: java.io.File) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val outputFile = java.io.File(moviesDir, "3D_SBS_Video_$timestamp.mp4")
            
            // Проверяем настройку порядка камер
            val wideOnLeft = binding.sbsOrderSwitch.isChecked
            
            val leftFile: java.io.File
            val rightFile: java.io.File
            val leftDescription: String
            val rightDescription: String
            
            if (wideOnLeft) {
                leftFile = wideFile
                rightFile = mainFile
                leftDescription = "широкоугольное"
                rightDescription = "основное"
            } else {
                leftFile = mainFile
                rightFile = wideFile
                leftDescription = "основное"
                rightDescription = "широкоугольное"
            }
            
            Log.d(TAG, "Начинаем сшивание видео в SBS формат")
            Log.d(TAG, "  Левое ($leftDescription): ${leftFile.absolutePath}")
            Log.d(TAG, "  Правое ($rightDescription): ${rightFile.absolutePath}")
            Log.d(TAG, "  Выходной файл: ${outputFile.absolutePath}")
            
            // FFmpeg команда для создания Side-by-Side видео
            // Используем совместимый H.264 кодек
            val ffmpegCommand = "-i \"${leftFile.absolutePath}\" -i \"${rightFile.absolutePath}\" -filter_complex \"[0:v][1:v]hstack\" -c:v hevc_mediacodec -b:v 50M -maxrate 20M -bufsize 25M -y \"${outputFile.absolutePath}\""

            Log.d(TAG, "FFmpeg команда: $ffmpegCommand")
            
            // Выполняем FFmpeg команду
            val session = FFmpegKit.execute(ffmpegCommand)
            val returnCode = session.returnCode
            val output = session.output
            val failStackTrace = session.failStackTrace
            
            Log.d(TAG, "FFmpeg завершен с кодом: $returnCode")
            
            runOnUiThread {
                // Скрываем индикатор прогресса
                showProcessingIndicator(false)
                
                if (returnCode.isValueSuccess) {
                    Log.d(TAG, "Сшивание видео успешно завершено")
                    Log.d(TAG, "Размер итогового файла: ${outputFile.length()} байт")
                    
                    // Добавляем сшитое видео в галерею
                    addVideoToGallery(outputFile, "3D_SBS_Video_$timestamp.mp4")
                    
                    // Удаляем исходные файлы для экономии места (теперь они сохранены в галерее)
                    cleanupSourceFiles(wideFile, mainFile)
                    
                    Toast.makeText(this@MainActivity, "3D SBS видео сохранено в галерею!", Toast.LENGTH_LONG).show()
                } else {
                    Log.e(TAG, "Ошибка сшивания видео")
                    Log.e(TAG, "FFmpeg output: $output")
                    if (failStackTrace != null) {
                        Log.e(TAG, "FFmpeg error: $failStackTrace")
                    }
                    
                    Toast.makeText(this@MainActivity, "Ошибка создания 3D видео", Toast.LENGTH_SHORT).show()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Исключение при сшивании видео", e)
            runOnUiThread {
                showProcessingIndicator(false)
                Toast.makeText(this@MainActivity, "Ошибка обработки видео: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupImageReaders() {
        if (logicalCameraId == null) return
        try {
            // Пытаемся подобрать общий размер JPEG, который поддерживают обе физические камеры
            val dual = dualCameraInfo
            val size: Size = if (dual != null) {
                val wideChars = cameraManager?.getCameraCharacteristics(dual.widePhysicalId)
                val mainChars = cameraManager?.getCameraCharacteristics(dual.mainPhysicalId)

                val wideMap = wideChars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val mainMap = mainChars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                val wideJpegSizes = wideMap?.getOutputSizes(ImageFormat.JPEG)?.toSet() ?: emptySet()
                val mainJpegSizes = mainMap?.getOutputSizes(ImageFormat.JPEG)?.toSet() ?: emptySet()

                val commonSizes = wideJpegSizes.intersect(mainJpegSizes)

                val chosen = commonSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: wideJpegSizes.minByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: mainJpegSizes.minByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: Size(1920, 1080)

                Log.d(
                    TAG,
                    "ImageReaders common JPEG size: ${chosen.width}x${chosen.height}, " +
                            "wideCount=${wideJpegSizes.size}, mainCount=${mainJpegSizes.size}"
                )
                chosen
            } else {
                val characteristics = cameraManager?.getCameraCharacteristics(logicalCameraId!!)
                val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
                val fallback = jpegSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(1920, 1080)
                Log.d(TAG, "ImageReaders JPEG size (no dual info): ${fallback.width}x${fallback.height}")
                fallback
            }

            wideImageReader?.close()
            mainImageReader?.close()

            wideImageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            mainImageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

            wideImageReader?.setOnImageAvailableListener({ reader ->
                onWideImageAvailable(reader)
            }, cameraHandler)

            mainImageReader?.setOnImageAvailableListener({ reader ->
                onMainImageAvailable(reader)
            }, cameraHandler)

            Log.d(TAG, "ImageReader настроены: ${size.width}x${size.height}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка настройки ImageReader", e)
        }
    }

    private fun takeSbsPhoto() {
        if (cameraDevice == null || captureSession == null) {
            Toast.makeText(this, "Камера не готова", Toast.LENGTH_SHORT).show()
            return
        }
        if (wideImageReader == null || mainImageReader == null) {
            setupImageReaders()
        }
        if (wideImageReader == null || mainImageReader == null) {
            Toast.makeText(this, "Невозможно настроить фото-выходы", Toast.LENGTH_SHORT).show()
            return
        }
        if (isTakingPhoto) {
            Toast.makeText(this, "Фото уже создается", Toast.LENGTH_SHORT).show()
            return
        }

        isTakingPhoto = true
        pendingWideBitmap = null
        pendingMainBitmap = null

        runOnUiThread {
            binding.processingText.text = "Создание 3D SBS фото..."
            showProcessingIndicator(true)
        }

        try {
            val requestBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE,
                setOf(wideCameraInstance.physicalId, mainCameraInstance.physicalId)
            ).apply {
                wideImageReader?.surface?.let { addTarget(it) }
                mainImageReader?.surface?.let { addTarget(it) }
                // Возвращаем автофокус для фото
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                // Отключаем стабилизацию, чтобы FOV не "гулял" от кадра к кадру
                set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    )
                }
            }

            captureSession?.capture(
                requestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {},
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при создании фото", e)
            runOnUiThread {
                showProcessingIndicator(false)
                Toast.makeText(this, "Ошибка фото: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            isTakingPhoto = false
        }
    }

    private fun onWideImageAvailable(reader: ImageReader) {
        try {
            val image = reader.acquireNextImage() ?: return
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            Log.d(TAG, "Wide JPEG bitmap size: ${decoded.width}x${decoded.height}")
            image.close()
            pendingWideBitmap = decoded
            tryFinishSbsPhoto()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения изображения с широкой камеры", e)
        }
    }

    private fun onMainImageAvailable(reader: ImageReader) {
        try {
            val image = reader.acquireNextImage() ?: return
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            Log.d(TAG, "Main JPEG bitmap size: ${decoded.width}x${decoded.height}")
            image.close()
            pendingMainBitmap = decoded
            tryFinishSbsPhoto()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения изображения с основной камеры", e)
        }
    }

    private fun tryFinishSbsPhoto() {
        val wideBitmap = pendingWideBitmap
        val mainBitmap = pendingMainBitmap
        if (wideBitmap == null || mainBitmap == null) return

        pendingWideBitmap = null
        pendingMainBitmap = null

        cameraHandler.post {
            try {
                val rotationDegrees = getPhotoRotationDegrees()

                val rotatedWide = rotateBitmap(wideBitmap, rotationDegrees)
                val rotatedMain = rotateBitmap(mainBitmap, rotationDegrees)

                // Применяем кроп для широкоугольного снимка в соответствии с ползунком
                val cropFactor = wideCameraInstance.cropValue
                val wideForUse = if (cropFactor > 1.0f) {
                    cropBitmapCenter(rotatedWide, cropFactor)
                } else {
                    rotatedWide
                }

                val wideOnLeft = binding.sbsOrderSwitch.isChecked

                val leftBitmap = if (wideOnLeft) wideForUse else rotatedMain
                val rightBitmap = if (wideOnLeft) rotatedMain else wideForUse

                Log.d(
                    TAG,
                    "SBS photo original bitmaps: left=${leftBitmap.width}x${leftBitmap.height}, " +
                            "right=${rightBitmap.width}x${rightBitmap.height}, wideOnLeft=$wideOnLeft"
                )

                val targetHeight = max(leftBitmap.height, rightBitmap.height)

                fun scaleToHeight(src: Bitmap): Bitmap {
                    val scale = targetHeight.toFloat() / src.height
                    val width = (src.width * scale).toInt()
                    return Bitmap.createScaledBitmap(src, width, targetHeight, true)
                }

                var leftScaled = scaleToHeight(leftBitmap)
                var rightScaled = scaleToHeight(rightBitmap)

                Log.d(
                    TAG,
                    "SBS photo scaled bitmaps (before width match): left=${leftScaled.width}x${leftScaled.height}, " +
                            "right=${rightScaled.width}x${rightScaled.height}, targetHeight=$targetHeight"
                )

                // Приводим обе половины к одинаковой ширине масштабированием, без дополнительного кропа
                val targetWidth = max(leftScaled.width, rightScaled.width)

                if (leftScaled.width != targetWidth) {
                    leftScaled = Bitmap.createScaledBitmap(leftScaled, targetWidth, targetHeight, true)
                }
                if (rightScaled.width != targetWidth) {
                    rightScaled = Bitmap.createScaledBitmap(rightScaled, targetWidth, targetHeight, true)
                }

                Log.d(
                    TAG,
                    "SBS photo scaled bitmaps (after width match): left=${leftScaled.width}x${leftScaled.height}, " +
                            "right=${rightScaled.width}x${rightScaled.height}, targetHeight=$targetHeight"
                )

                val resultWidth = leftScaled.width + rightScaled.width
                val resultBitmap = Bitmap.createBitmap(resultWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(resultBitmap)
                canvas.drawBitmap(leftScaled, 0f, 0f, null)
                canvas.drawBitmap(rightScaled, leftScaled.width.toFloat(), 0f, null)

                saveSbsPhotoToGallery(resultBitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при создании SBS фото", e)
                runOnUiThread {
                    showProcessingIndicator(false)
                    Toast.makeText(this, "Ошибка обработки фото: ${e.message}", Toast.LENGTH_SHORT).show()
                    isTakingPhoto = false
                }
            }
        }
    }

    private fun getPhotoRotationDegrees(): Int {
        val rotation = windowManager.defaultDisplay.rotation
        return when (rotation) {
            Surface.ROTATION_0 -> 90
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun cropBitmapCenter(src: Bitmap, cropFactor: Float): Bitmap {
        if (cropFactor <= 1.0f) return src

        val factor = cropFactor.coerceAtMost(5.0f) // защитимся от слишком больших значений
        val newWidth = (src.width / factor).toInt().coerceAtLeast(1)
        val newHeight = (src.height / factor).toInt().coerceAtLeast(1)

        val left = ((src.width - newWidth) / 2f).toInt().coerceAtLeast(0)
        val top = ((src.height - newHeight) / 2f).toInt().coerceAtLeast(0)

        val safeWidth = min(newWidth, src.width - left)
        val safeHeight = min(newHeight, src.height - top)

        Log.d(
            TAG,
            "Crop wide bitmap center: src=${src.width}x${src.height}, factor=$factor, " +
                    "rect=($left,$top,$safeWidth,$safeHeight)"
        )

        return Bitmap.createBitmap(src, left, top, safeWidth, safeHeight)
    }

    private fun saveSbsPhotoToGallery(bitmap: Bitmap) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = "3D_SBS_Photo_$timestamp.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                runOnUiThread {
                    showProcessingIndicator(false)
                    Toast.makeText(this, "3D SBS фото сохранено в галерею!", Toast.LENGTH_LONG).show()
                    isTakingPhoto = false
                }
            } else {
                runOnUiThread {
                    showProcessingIndicator(false)
                    Toast.makeText(this, "Ошибка сохранения фото", Toast.LENGTH_SHORT).show()
                    isTakingPhoto = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения SBS фото", e)
            runOnUiThread {
                showProcessingIndicator(false)
                Toast.makeText(this, "Ошибка сохранения фото: ${e.message}", Toast.LENGTH_SHORT).show()
                isTakingPhoto = false
            }
        }
    }


    private fun addVideoToGallery(videoFile: java.io.File, displayName: String = videoFile.name) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
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
    
    private fun saveSourceVideosToGallery(wideFile: java.io.File, mainFile: java.io.File, timestamp: String) {
        try {
            Log.d(TAG, "Сохраняем исходные видео в галерею...")
            
            // Сохраняем широкоугольное видео
            val wideDisplayName = "3D_Wide_Camera_$timestamp.mp4"
            addVideoToGallery(wideFile, wideDisplayName)
            Log.d(TAG, "Широкоугольное видео сохранено в галерею: $wideDisplayName")
            
            // Сохраняем видео с основной камеры
            val mainDisplayName = "3D_Main_Camera_$timestamp.mp4"
            addVideoToGallery(mainFile, mainDisplayName)
            Log.d(TAG, "Видео с основной камеры сохранено в галерею: $mainDisplayName")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения исходных видео в галерею", e)
        }
    }
    
    private fun cleanupSourceFiles(wideFile: java.io.File, mainFile: java.io.File) {
        try {
            if (wideFile.exists() && wideFile.delete()) {
                Log.d(TAG, "Удален исходный файл широкоугольной камеры")
            }
            if (mainFile.exists() && mainFile.delete()) {
                Log.d(TAG, "Удален исходный файл основной камеры")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка удаления исходных файлов", e)
        }
    }
    
    private fun showProcessingIndicator(show: Boolean) {
        binding.processingContainer.visibility = if (show) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        
        // Блокируем/разблокируем кнопку записи во время обработки
        binding.captureButton.isEnabled = !show
        
        Log.d(TAG, if (show) "Показан индикатор обработки" else "Скрыт индикатор обработки")
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
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(preferredSize.width, preferredSize.height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(20_000_000) // Увеличиваем битрейт для высокого разрешения
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
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            setVideoSize(preferredSize.width, preferredSize.height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(20_000_000) // Увеличиваем битрейт для высокого разрешения
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

                // Добавляем поверхности для фото
                wideImageReader?.surface?.let { surface ->
                    outputs.add(OutputConfiguration(surface).apply {
                        setPhysicalCameraId(wideCameraInstance.physicalId!!)
                    })
                }
                mainImageReader?.surface?.let { surface ->
                    outputs.add(OutputConfiguration(surface).apply {
                        setPhysicalCameraId(mainCameraInstance.physicalId!!)
                    })
                }
                
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
                if (wideImageReader == null || mainImageReader == null) {
                    setupImageReaders()
                }
                // Создаем конфигурацию выходов только для предпросмотра
                val outputs = mutableListOf<OutputConfiguration>()
                
                outputs.add(OutputConfiguration(wideCameraInstance.surface!!).apply {
                    setPhysicalCameraId(wideCameraInstance.physicalId!!)
                })
                outputs.add(OutputConfiguration(mainCameraInstance.surface!!).apply {
                    setPhysicalCameraId(mainCameraInstance.physicalId!!)
                })

                // Добавляем поверхности для фото
                wideImageReader?.surface?.let { surface ->
                    outputs.add(OutputConfiguration(surface).apply {
                        setPhysicalCameraId(wideCameraInstance.physicalId!!)
                    })
                }
                mainImageReader?.surface?.let { surface ->
                    outputs.add(OutputConfiguration(surface).apply {
                        setPhysicalCameraId(mainCameraInstance.physicalId!!)
                    })
                }
                
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
                        
                        // Настройки для видео: возвращаем непрерывный автофокус
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
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
                    mainCameraInstance.configureTransform(windowManager)
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
                        
                        // Непрерывный автофокус для предпросмотра
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
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
                    mainCameraInstance.configureTransform(windowManager)
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
                wideCameraInstance.preparePreviewBuffer()
                mainCameraInstance.preparePreviewBuffer()

                if (wideImageReader == null || mainImageReader == null) {
                    setupImageReaders()
                }

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

                    // Добавляем поверхности для фото
                    wideImageReader?.surface?.let { surface ->
                        outputs.add(OutputConfiguration(surface).apply {
                            setPhysicalCameraId(wideCameraInstance.physicalId!!)
                        })
                    }
                    mainImageReader?.surface?.let { surface ->
                        outputs.add(OutputConfiguration(surface).apply {
                            setPhysicalCameraId(mainCameraInstance.physicalId!!)
                        })
                    }

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
