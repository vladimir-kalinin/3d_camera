package com.example.my_3d_camera

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.example.my_3d_camera.camera.CaptureMode
import com.example.my_3d_camera.camera.StereoCamera
import com.example.my_3d_camera.camera.StereoRig
import com.example.my_3d_camera.databinding.ActivityMainBinding
import com.example.my_3d_camera.gl.StereoRenderer
import com.example.my_3d_camera.media.MediaSaver
import com.example.my_3d_camera.media.SbsVideoRecorder
import com.example.my_3d_camera.media.StillComposer
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var saver: MediaSaver
    private lateinit var videoRecorder: SbsVideoRecorder
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Сохранение файлов и управление MediaRecorder — вне UI-потока, строго по очереди. */
    private val io = Executors.newSingleThreadExecutor()

    private var rig: StereoRig? = null
    private var renderer: StereoRenderer? = null
    private var camera: StereoCamera? = null
    private var previewSurface: Surface? = null

    private var calibration = StereoRenderer.Calibration()
    private var mode = CaptureMode.VIDEO
    /** Камера настроена на полноразмерные JPEG (режим фото). */
    private var stillsEnabled = false
    private var isRecording = false
    private var recordingStartedAt = 0L
    private var streamInfo = ""
    private var permissionAsked = false
    private var cameraStalled = false
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalStatus = status
        updateWarning()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        saver = MediaSaver(this)
        videoRecorder = SbsVideoRecorder(this, saver)
        calibration = loadCalibration()
        mode = loadMode()

        rig = try {
            StereoRig.detect(getSystemService(CameraManager::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка поиска камер", e)
            null
        }
        if (rig == null) {
            binding.statusText.text = "Не найдена пара задних камер, работающих одновременно"
            binding.shutterButton.isEnabled = false
        }

        binding.preview.surfaceTextureListener = previewListener
        binding.shutterButton.setOnClickListener {
            if (mode == CaptureMode.PHOTO) takePhoto() else toggleRecording()
        }
        binding.photoModeButton.setOnClickListener { switchMode(CaptureMode.PHOTO) }
        binding.videoModeButton.setOnClickListener { switchMode(CaptureMode.VIDEO) }
        updateShutterUi()
        binding.menuButton.setOnClickListener { showMenu() }
        setupCalibrationControls()
    }

    override fun onResume() {
        super.onResume()
        getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermalListener)
        if (rig == null) return
        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else if (!permissionAsked) {
            // Диалог разрешений сам вызывает onPause/onResume — не запрашиваем повторно
            permissionAsked = true
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onPause() {
        // Камеру обязательно освобождаем, иначе её не смогут открыть другие приложения
        if (isRecording) stopRecording(wait = true)
        stopCamera()
        getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }

    // ---------- Камера и рендерер ----------

    private fun startCamera() {
        val rig = rig ?: return
        if (camera != null) return

        val renderer = StereoRenderer(rig, object : StereoRenderer.Listener {
            override fun onStats(stats: StereoRenderer.Stats) {
                runOnUiThread {
                    binding.statusText.text = String.format(
                        Locale.US, "%s · %.0f fps · рассинхрон %.1f мс · без пары %d",
                        streamInfo, stats.fps, stats.maxPairSkewMs, stats.droppedFrames
                    )
                    cameraStalled = stats.cameraStalled
                    updateWarning()
                }
            }
        })
        renderer.calibration = calibration
        val (wideSurface, mainSurface) = renderer.start()
        this.renderer = renderer
        previewSurface?.let { renderer.setPreviewSurface(it) }

        camera = StereoCamera(getSystemService(CameraManager::class.java), rig, object : StereoCamera.Listener {
            override fun onStreamSizesChosen(wideSize: Size, mainSize: Size): Pair<Surface, Surface> {
                renderer.setStreamSizes(wideSize, mainSize)
                return wideSurface to mainSurface
            }

            override fun onStarted(wideSize: Size, mainSize: Size, stillsEnabled: Boolean) {
                val out = renderer.outputSize
                runOnUiThread {
                    this@MainActivity.stillsEnabled = stillsEnabled
                    val main = rig.main.jpegSize
                    streamInfo = if (stillsEnabled && main != null) {
                        "фото ${main.height * 2}×${main.width}"
                    } else {
                        "SBS ${out.width}×${out.height} (ш/у ${wideSize.width}×${wideSize.height})"
                    }
                    binding.statusText.text = streamInfo
                    (binding.preview.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio =
                        "${out.width}:${out.height}"
                    binding.preview.requestLayout()
                }
            }

            override fun onError(message: String) {
                runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
            }
        }, mode, wideSizeOverride()).also { it.open() }
    }

    /** Отладочный запуск: adb shell am start ... --es wide_size 2048x1536 */
    private fun wideSizeOverride(): Size? {
        if (!BuildConfig.DEBUG) return null
        val value = intent.getStringExtra("wide_size") ?: return null
        return runCatching { Size.parseSize(value) }.getOrNull()
    }

    /**
     * В режиме фото широкоугольная камера отдаёт большой поток (резкий левый глаз, ~25 fps),
     * в режиме видео — такой же, как основная (30 fps). Смена режима перезапускает камеру.
     */
    private fun switchMode(newMode: CaptureMode) {
        if (newMode == mode || isRecording) return
        mode = newMode
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
        updateShutterUi()
        if (camera != null) {
            stopCamera()
            startCamera()
        }
    }

    private fun loadMode(): CaptureMode {
        val name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_MODE, null)
        return CaptureMode.entries.firstOrNull { it.name == name } ?: CaptureMode.VIDEO
    }

    private fun updateShutterUi() {
        val icon = when {
            isRecording -> R.drawable.ic_stop
            mode == CaptureMode.PHOTO -> R.drawable.ic_photo
            else -> R.drawable.ic_record
        }
        binding.shutterButton.setImageResource(icon)
        binding.shutterButton.contentDescription = getString(
            when {
                isRecording -> R.string.stop_video
                mode == CaptureMode.PHOTO -> R.string.take_photo
                else -> R.string.record_video
            }
        )
        for ((button, buttonMode) in listOf(
            binding.photoModeButton to CaptureMode.PHOTO,
            binding.videoModeButton to CaptureMode.VIDEO,
        )) {
            val selected = buttonMode == mode
            button.setTextColor(if (selected) 0xFFFFD54F.toInt() else 0xB3FFFFFF.toInt())
            button.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            button.isEnabled = !isRecording
        }
    }

    private fun stopCamera() {
        stillsEnabled = false
        // Сначала камера перестаёт слать кадры, потом освобождаем поверхности рендерера
        camera?.close()
        camera = null
        renderer?.release()
        renderer = null
    }

    private val previewListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val surface = Surface(texture)
            previewSurface = surface
            renderer?.setPreviewSurface(surface)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            renderer?.setPreviewSurface(null)
            previewSurface?.release()
            previewSurface = null
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    /**
     * При нагреве Android сначала снижает частоту кадров камер,
     * а потом может совсем отключить одну из физических камер.
     */
    private fun updateWarning() {
        val text = when {
            cameraStalled -> "Одна из камер перестала отдавать кадры. Обычно это перегрев — дайте телефону остыть"
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> "Телефон перегрелся: Android снижает частоту кадров камер"
            thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT -> "Телефон нагревается — возможны пропуски кадров"
            else -> null
        }
        binding.warningText.text = text
        binding.warningText.visibility = if (text != null) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ---------- Фото ----------

    private fun takePhoto() {
        val renderer = renderer ?: return
        val camera = camera
        binding.shutterButton.isEnabled = false
        binding.preview.animate().alpha(0.3f).setDuration(60).withEndAction {
            binding.preview.animate().alpha(1f).setDuration(120)
        }
        if (stillsEnabled && camera != null) {
            // Полноразмерный снимок с обеих камер, SBS собирается на CPU
            val rig = rig ?: return
            val calibration = calibration
            camera.captureStill { still ->
                if (still == null) {
                    onPhotoSaved(null, "")
                    return@captureStill
                }
                io.execute {
                    val uri = try {
                        val bitmap = StillComposer.compose(rig, calibration, still.wideJpeg, still.mainJpeg)
                        val size = "${bitmap.width}×${bitmap.height}"
                        saver.savePhoto(bitmap).also { bitmap.recycle() }?.let { it to size }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Не удалось собрать фото", e)
                        null
                    }
                    onPhotoSaved(uri?.first, uri?.second ?: "")
                }
            }
        } else {
            // Фото из кадра превью
            renderer.capturePhoto { pixels, width, height ->
                io.execute {
                    val uri = saver.savePhoto(pixels, width, height)
                    onPhotoSaved(uri, "${width}×${height}")
                }
            }
        }
    }

    private fun onPhotoSaved(uri: Uri?, size: String) {
        runOnUiThread {
            binding.shutterButton.isEnabled = true
            val text = if (uri != null) "Фото сохранено ($size)" else "Не удалось сохранить фото"
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Видео ----------

    private fun toggleRecording() {
        if (isRecording) stopRecording(wait = false) else startRecording()
    }

    private fun startRecording() {
        val renderer = renderer ?: return
        val size = renderer.outputSize
        if (size.width == 0) return
        val withAudio = hasPermission(Manifest.permission.RECORD_AUDIO)
        val frameRate = rig?.fpsRange?.upper ?: 30

        binding.shutterButton.isEnabled = false
        io.execute {
            try {
                val surface = videoRecorder.start(size, frameRate, withAudio)
                renderer.startRecording(surface)
                runOnUiThread {
                    isRecording = true
                    recordingStartedAt = SystemClock.elapsedRealtime()
                    updateRecordingUi()
                    binding.shutterButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось начать запись", e)
                runOnUiThread {
                    binding.shutterButton.isEnabled = true
                    Toast.makeText(this, "Не удалось начать запись: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopRecording(wait: Boolean) {
        val renderer = renderer
        isRecording = false
        updateRecordingUi()
        binding.shutterButton.isEnabled = false
        val task = io.submit {
            // Рендерер перестаёт рисовать в поверхность энкодера до остановки MediaRecorder
            renderer?.stopRecording()
            val uri = videoRecorder.stop()
            runOnUiThread {
                binding.shutterButton.isEnabled = true
                val text = if (uri != null) "Видео сохранено в галерею" else "Видео не записалось"
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            }
        }
        if (wait) task.get()
    }

    private fun updateRecordingUi() {
        updateShutterUi()
        binding.recordingIndicator.visibility = if (isRecording) android.view.View.VISIBLE else android.view.View.GONE
        mainHandler.removeCallbacks(recordingTicker)
        if (isRecording) recordingTicker.run()
    }

    private val recordingTicker = object : Runnable {
        override fun run() {
            val seconds = (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000
            binding.recordingIndicator.text = String.format(Locale.US, "● %02d:%02d", seconds / 60, seconds % 60)
            mainHandler.postDelayed(this, 500)
        }
    }

    // ---------- Подстройка совмещения ----------

    private fun setupCalibrationControls() {
        binding.scaleSeekBar.progress = scaleToProgress(calibration.scale)
        binding.verticalSeekBar.progress = shiftToProgress(calibration.verticalShift)
        updateCalibrationLabels()

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                calibration = calibration.copy(
                    scale = progressToScale(binding.scaleSeekBar.progress),
                    verticalShift = progressToShift(binding.verticalSeekBar.progress),
                )
                applyCalibration()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = saveCalibration()
        }
        binding.scaleSeekBar.setOnSeekBarChangeListener(listener)
        binding.verticalSeekBar.setOnSeekBarChangeListener(listener)
    }

    private fun applyCalibration() {
        renderer?.calibration = calibration
        updateCalibrationLabels()
    }

    private fun updateCalibrationLabels() {
        val total = (rig?.wideCropFactor ?: 1f) * calibration.scale
        binding.scaleValue.text = String.format(Locale.US, "%.3f× (итого %.3f×)", calibration.scale, total)
        binding.verticalValue.text = String.format(Locale.US, "%+.1f%%", calibration.verticalShift * 100)
    }

    // Масштаб: 0.90..1.10, сдвиг: −5%..+5% высоты кадра; середина ползунка — без подстройки
    private fun progressToScale(progress: Int) = 0.9f + progress / 1000f
    private fun scaleToProgress(scale: Float) = ((scale - 0.9f) * 1000).toInt().coerceIn(0, 200)
    private fun progressToShift(progress: Int) = (progress - 100) / 2000f
    private fun shiftToProgress(shift: Float) = (shift * 2000 + 100).toInt().coerceIn(0, 200)

    private fun loadCalibration(): StereoRenderer.Calibration {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return StereoRenderer.Calibration(
            scale = prefs.getFloat(KEY_SCALE, 1f),
            verticalShift = prefs.getFloat(KEY_VERTICAL, 0f),
            swapEyes = prefs.getBoolean(KEY_SWAP, false),
        )
    }

    private fun saveCalibration() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putFloat(KEY_SCALE, calibration.scale)
            .putFloat(KEY_VERTICAL, calibration.verticalShift)
            .putBoolean(KEY_SWAP, calibration.swapEyes)
            .apply()
    }

    // ---------- Меню ----------

    private fun showMenu() {
        PopupMenu(this, binding.menuButton).apply {
            menuInflater.inflate(R.menu.main_menu, menu)
            menu.findItem(R.id.action_swap_eyes).isChecked = calibration.swapEyes
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_swap_eyes -> {
                        calibration = calibration.copy(swapEyes = !calibration.swapEyes)
                        applyCalibration()
                        saveCalibration()
                    }
                    R.id.action_reset_calibration -> {
                        calibration = StereoRenderer.Calibration(swapEyes = calibration.swapEyes)
                        binding.scaleSeekBar.progress = scaleToProgress(calibration.scale)
                        binding.verticalSeekBar.progress = shiftToProgress(calibration.verticalShift)
                        applyCalibration()
                        saveCalibration()
                    }
                    R.id.action_about -> showAbout()
                }
                true
            }
        }.show()
    }

    private fun showAbout() {
        val rig = rig ?: return
        val left = if (rig.wideIsLeft != calibration.swapEyes) "широкоугольная" else "основная"
        val message = """
            Логическая камера: ${rig.logicalId}
            Широкоугольная: id ${rig.wide.id}, ${rig.wide.focalLengthMm} мм
            Основная: id ${rig.main.id}, ${rig.main.focalLengthMm} мм
            Кроп широкоугольной: ${String.format(Locale.US, "%.3f", rig.wideCropFactor)}×
            База: ${rig.baselineMeters?.let { String.format(Locale.US, "%.1f мм", it * 1000) } ?: "неизвестна"}
            Левый глаз: $left
            $streamInfo
            Файлы: Галерея → ${MediaSaver.ALBUM}
        """.trimIndent()
        AlertDialog.Builder(this).setTitle(R.string.about).setMessage(message).setPositiveButton("OK", null).show()
    }

    // ---------- Разрешения ----------

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Нужен доступ к камере")
            .setMessage("Без разрешения на камеру приложение не может работать. Микрофон нужен только для звука в видео.")
            .setPositiveButton("Открыть настройки") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Закрыть") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "stereo_calibration"
        private const val KEY_SCALE = "scale"
        private const val KEY_VERTICAL = "vertical_shift"
        private const val KEY_SWAP = "swap_eyes"
        private const val KEY_MODE = "capture_mode"
    }
}
