package com.example.my_3d_camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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
    private var wideCameraId: String? = null
    private var teleCameraId: String? = null
    private var availableCameras: List<CameraInfo> = emptyList()
    private var selectedWidePhysicalId: String? = null
    private var selectedTelePhysicalId: String? = null

    data class CameraInfo(
        val id: String,
        val facing: Int,
        val focalLength: Float,
        val description: String,
        val isLogical: Boolean = false,
        val physicalIds: Set<String> = emptySet(),
        val zoomRatios: List<Float> = emptyList()
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

    private fun setupCameraSelection() {
        val logicalCameras = availableCameras.filter { it.isLogical }
        if (logicalCameras.isNotEmpty()) {
            showCameraSelectionDialog("Выберите камеру для левого глаза", logicalCameras) { selectedCamera ->
                wideCameraId = selectedCamera.id
                showCameraSelectionDialog("Выберите камеру для правого глаза", logicalCameras) { selectedCamera ->
                    teleCameraId = selectedCamera.id
                    if (binding.viewFinderWide.isAvailable) {
                        startWideCamera()
                    }
                    if (binding.viewFinder.isAvailable) {
                        startTeleCamera()
                    }
                }
            }
        }
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
            
            availableCameras = cameraIdList.map { id ->
                val characteristics = cameraManager?.getCameraCharacteristics(id)
                val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                val focalLengths = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focalLength = focalLengths?.get(0) ?: 0f
                val physicalCameras = characteristics?.physicalCameraIds ?: emptySet()
                
                // Получаем доступные значения зума
                val zoomRatios = if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                        listOf(range.lower, 1.0f, range.upper)
                    } ?: emptyList()
                } else {
                    emptyList()
                }
                
                val facingStr = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Задняя"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Передняя"
                    else -> "Неизвестная"
                }

                CameraInfo(
                    id = id,
                    facing = facing ?: -1,
                    focalLength = focalLength,
                    description = "Камера $id ($facingStr, ${String.format("%.1f", focalLength)}мм)",
                    isLogical = physicalCameras.isNotEmpty(),
                    physicalIds = physicalCameras,
                    zoomRatios = zoomRatios
                )
            }

            Log.d(TAG, "Найдены камеры: ${availableCameras.map { it.description }}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up available cameras", e)
            e.printStackTrace()
        }
    }

    private fun showCameraSelectionDialog(title: String, cameras: List<CameraInfo>, onCameraSelected: (CameraInfo) -> Unit) {
        // Этот метод больше не используется, но пока оставим его на случай,
        // если понадобится добавить ручной выбор камер в будущем
        val cameraNames = cameras.map { it.description }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(cameraNames) { _, which ->
                onCameraSelected(cameras[which])
            }
            .setCancelable(false)
            .show()
    }

    private fun openCamera() {
        try {
            // Находим заднюю камеру
            val backCamera = availableCameras.find { 
                it.facing == CameraCharacteristics.LENS_FACING_BACK && it.isLogical 
            }

            if (backCamera != null) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            e.printStackTrace()
        }
    }

    private fun startWideCamera() {
        try {
            wideCameraId?.let { id ->
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    cameraManager?.openCamera(id, stateCallback, cameraHandler)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting wide camera", e)
        }
    }

    private fun startTeleCamera() {
        try {
            teleCameraId?.let { id ->
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    cameraManager?.openCamera(id, stateCallback, cameraHandler)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tele camera", e)
        }
    }

    private fun createCameraPreviewSession() {
        if (!isWideTextureAvailable || !isTeleTextureAvailable) {
            Log.e(TAG, "Attempt to create session when TextureViews are not ready")
            return
        }

        try {
            val wideTexture = binding.viewFinderWide.surfaceTexture
                ?: throw IllegalStateException("Wide texture is null")
            val teleTexture = binding.viewFinder.surfaceTexture
                ?: throw IllegalStateException("Tele texture is null")

            wideTexture.setDefaultBufferSize(1920, 1080)
            teleTexture.setDefaultBufferSize(1920, 1080)

            val wideSurface = Surface(wideTexture)
            val teleSurface = Surface(teleTexture)

            // Создаем запросы для обоих preview с разными зумами
            wideRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            teleRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            wideRequestBuilder?.addTarget(wideSurface)
            teleRequestBuilder?.addTarget(teleSurface)

            // Устанавливаем разные зумы
            val backCamera = availableCameras.find { 
                it.facing == CameraCharacteristics.LENS_FACING_BACK && it.isLogical 
            }
            backCamera?.zoomRatios?.let { ratios ->
                if (ratios.isNotEmpty()) {
                    wideRequestBuilder?.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratios.first())
                    teleRequestBuilder?.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratios.last())
                }
            }

            wideRequestBuilder?.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            teleRequestBuilder?.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            // Создаем одну сессию с двумя поверхностями
            cameraDevice?.createCaptureSession(
                listOf(wideSurface, teleSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            // Запускаем оба preview
                            val wideRequest = wideRequestBuilder?.build()
                            val teleRequest = teleRequestBuilder?.build()
                            if (wideRequest != null && teleRequest != null) {
                                captureSession?.setRepeatingBurst(
                                    listOf(wideRequest, teleRequest),
                                    null,
                                    cameraHandler
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting camera preview", e)
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
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera preview session", e)
            e.printStackTrace()
        }
    }

    private fun showPhysicalCameraSelectionDialog(
        cameraInfo: CameraInfo,
        isWideCamera: Boolean,
        onPhysicalCameraSelected: (String?) -> Unit
    ) {
        if (cameraInfo.physicalIds.isEmpty()) {
            Toast.makeText(this, "У этой камеры нет дополнительных объективов", Toast.LENGTH_SHORT).show()
            return
        }

        val options = mutableListOf<Pair<String?, String>>()
        options.add(Pair(null, "Автоматический выбор"))
        
        cameraInfo.physicalIds.forEach { physicalId ->
            val characteristics = cameraManager?.getCameraCharacteristics(physicalId)
            val focalLength = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.get(0) ?: 0f
            val description = when {
                focalLength < 4.0f -> "Широкоугольный"
                focalLength > 8.0f -> "Телефото"
                else -> "Стандартный"
            }
            options.add(Pair(physicalId, "$description объектив (${String.format("%.1f", focalLength)}мм)"))
        }

        val items = options.map { it.second }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(if (isWideCamera) "Выберите объектив для левого кадра" else "Выберите объектив для правого кадра")
            .setItems(items) { _, which ->
                onPhysicalCameraSelected(options[which].first)
                if (isWideCamera) {
                    selectedWidePhysicalId = options[which].first
                    wideRequestBuilder?.let { builder ->
                        builder.get(CaptureRequest.CONTROL_ZOOM_RATIO)?.let { zoom ->
                            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                        }
                    }
                } else {
                    selectedTelePhysicalId = options[which].first
                    teleRequestBuilder?.let { builder ->
                        builder.get(CaptureRequest.CONTROL_ZOOM_RATIO)?.let { zoom ->
                            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                        }
                    }
                }
            }
            .show()
    }

    private fun take3DPhoto() {
        try {
            val wideCaptureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            val teleCaptureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

            val wideImageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)
            val teleImageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)

            wideCaptureBuilder?.addTarget(wideImageReader.surface)
            teleCaptureBuilder?.addTarget(teleImageReader.surface)

            // Копируем настройки зума из preview builders
            wideRequestBuilder?.let { builder ->
                wideCaptureBuilder?.set(
                    CaptureRequest.CONTROL_ZOOM_RATIO,
                    builder.get(CaptureRequest.CONTROL_ZOOM_RATIO)
                )
            }

            teleRequestBuilder?.let { builder ->
                teleCaptureBuilder?.set(
                    CaptureRequest.CONTROL_ZOOM_RATIO,
                    builder.get(CaptureRequest.CONTROL_ZOOM_RATIO)
                )
            }

            wideCaptureBuilder?.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            teleCaptureBuilder?.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            captureSession?.stopRepeating()
            captureSession?.abortCaptures()

            // Делаем снимки
            captureSession?.capture(
                wideCaptureBuilder?.build()!!,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        captureSession?.capture(
                            teleCaptureBuilder?.build()!!,
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
                                    // Восстанавливаем preview
                                    createCameraPreviewSession()
                                }
                            },
                            null
                        )
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking 3D photo", e)
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