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

    // Переменные для съемки фото
    private var isCapturing = false

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_MEDIA_LOCATION
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

        // Настраиваем кнопку съемки
        binding.captureButton.setOnClickListener {
            capturePhoto()
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
            shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
            shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) -> {
                // Показываем диалог с объяснением, почему нужны разрешения
                AlertDialog.Builder(this)
                    .setTitle("Необходимы разрешения")
                    .setMessage("Для работы приложения требуется доступ к камере и хранилищу. Пожалуйста, предоставьте необходимые разрешения.")
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
                    shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) ||
                    shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
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

    // Методы для съемки фото (простой подход через TextureView)
    private fun capturePhoto() {
        if (isCapturing) {
            Log.d(TAG, "Съемка уже в процессе")
            return
        }

        isCapturing = true
        binding.captureButton.isEnabled = false

        try {
            // Захватываем кадры из TextureView обеих камер
            val wideBitmap = wideCameraInstance.textureView?.getBitmap()
            val mainBitmap = mainCameraInstance.textureView?.getBitmap()

            if (wideBitmap != null && mainBitmap != null) {
                Log.d(TAG, "Захвачены изображения: wide=${wideBitmap.width}x${wideBitmap.height}, main=${mainBitmap.width}x${mainBitmap.height}")
                
                // Сохраняем изображения в фоновом потоке
                executor.execute {
                    // Применяем кроп к широкоугольной камере
                    val processedWideBitmap = applyCropToBitmap(wideBitmap, wideCameraInstance.cropValue)
                    
                    // Создаем SBS изображение
                    val sbsBitmap = createSideBySideBitmap(processedWideBitmap, mainBitmap)
                    
                    if (sbsBitmap != null) {
                        // Сохраняем SBS изображение
                        saveSbsBitmap(sbsBitmap)
                        
                        // Освобождаем память
                        processedWideBitmap.recycle()
                        sbsBitmap.recycle()
                        
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "3D фото сохранено в галерею", Toast.LENGTH_SHORT).show()
                            resetCaptureState()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Ошибка создания 3D фото", Toast.LENGTH_SHORT).show()
                            resetCaptureState()
                        }
                    }
                }
            } else {
                Log.e(TAG, "Не удалось захватить изображения из TextureView")
                Toast.makeText(this, "Ошибка захвата изображения", Toast.LENGTH_SHORT).show()
                resetCaptureState()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при съемке фото", e)
            Toast.makeText(this, "Ошибка съемки: ${e.message}", Toast.LENGTH_SHORT).show()
            resetCaptureState()
        }
    }



    private fun applyCropToBitmap(originalBitmap: Bitmap, cropFactor: Float): Bitmap {
        try {
            val originalWidth = originalBitmap.width
            val originalHeight = originalBitmap.height
            
            // Вычисляем размеры обрезанной области
            val croppedWidth = (originalWidth / cropFactor).toInt()
            val croppedHeight = (originalHeight / cropFactor).toInt()
            
            // Вычисляем смещение для центрирования
            val offsetX = (originalWidth - croppedWidth) / 2
            val offsetY = (originalHeight - croppedHeight) / 2
            
            Log.d(TAG, "Применяем кроп ${cropFactor}x: ${originalWidth}x${originalHeight} -> ${croppedWidth}x${croppedHeight}")
            
            // Создаем обрезанный bitmap
            val croppedBitmap = Bitmap.createBitmap(
                originalBitmap,
                offsetX,
                offsetY,
                croppedWidth,
                croppedHeight
            )
            
            return croppedBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка применения кропа: ${e.message}", e)
            return originalBitmap
        }
    }

    private fun createSideBySideBitmap(leftBitmap: Bitmap, rightBitmap: Bitmap): Bitmap? {
        try {
            // Определяем размеры для SBS изображения
            val targetHeight = min(leftBitmap.height, rightBitmap.height)
            val targetWidth = min(leftBitmap.width, rightBitmap.width)
            
            // Создаем SBS изображение (ширина удваивается)
            val sbsWidth = targetWidth * 2
            val sbsHeight = targetHeight
            
            Log.d(TAG, "Создаем SBS изображение: ${sbsWidth}x${sbsHeight}")
            
            val sbsBitmap = Bitmap.createBitmap(sbsWidth, sbsHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(sbsBitmap)
            
            // Масштабируем и размещаем левое изображение (широкоугольная камера)
            val leftScaledBitmap = Bitmap.createScaledBitmap(leftBitmap, targetWidth, targetHeight, true)
            canvas.drawBitmap(leftScaledBitmap, 0f, 0f, null)
            
            // Масштабируем и размещаем правое изображение (основная камера)
            val rightScaledBitmap = Bitmap.createScaledBitmap(rightBitmap, targetWidth, targetHeight, true)
            canvas.drawBitmap(rightScaledBitmap, targetWidth.toFloat(), 0f, null)
            
            // Освобождаем временные bitmap
            if (leftScaledBitmap != leftBitmap) leftScaledBitmap.recycle()
            if (rightScaledBitmap != rightBitmap) rightScaledBitmap.recycle()
            
            Log.d(TAG, "SBS изображение создано успешно")
            return sbsBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания SBS изображения: ${e.message}", e)
            return null
        }
    }

    private fun saveSbsBitmap(sbsBitmap: Bitmap) {
        try {
            // Конвертируем Bitmap в JPEG байты
            val outputStream = java.io.ByteArrayOutputStream()
            sbsBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            val imageBytes = outputStream.toByteArray()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "3D_Camera_SBS_$timestamp.jpg"

            Log.d(TAG, "Сохраняем SBS изображение: ${sbsBitmap.width}x${sbsBitmap.height}")

            // Сохраняем в галерею
            saveImageToGallery(imageBytes, filename, "SBS")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения SBS изображения", e)
        }
    }







    private fun resetCaptureState() {
        isCapturing = false
        binding.captureButton.isEnabled = true
    }

    private fun saveImageToGallery(imageBytes: ByteArray, filename: String, cameraType: String) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/3D_Camera")
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            uri?.let {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(imageBytes)
                    outputStream.flush()
                }
                Log.d(TAG, "Изображение $cameraType сохранено в галерею: $filename")
            } ?: run {
                Log.e(TAG, "Не удалось создать URI для сохранения $cameraType")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения в галерею для $cameraType: ${e.message}", e)
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
                                    previewRequestBuilder = session.device.createCaptureRequest(
                                        CameraDevice.TEMPLATE_RECORD,
                                        setOf(wideCameraInstance.physicalId, mainCameraInstance.physicalId)
                                    ).apply {
                                        // Добавляем обе поверхности как цели
                                        addTarget(wideCameraInstance.surface!!)
                                        addTarget(mainCameraInstance.surface!!)
                                        
                                        // Настраиваем базовые параметры
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                        
                                        // Отключаем стабилизацию
                                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                        
                                        // Устанавливаем минимальный зум для основной камеры
                                        mainCameraInstance.cropValue = minCrop
                                        set(CaptureRequest.CONTROL_ZOOM_RATIO, minCrop)
                                    }

                                    captureSession = session
                                    // Устанавливаем повторяющийся запрос
                                    session.setRepeatingRequest(
                                        previewRequestBuilder!!.build(),
                                        null,
                                        cameraHandler
                                    )
                                    wideCameraInstance.configureTransform(windowManager)
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
