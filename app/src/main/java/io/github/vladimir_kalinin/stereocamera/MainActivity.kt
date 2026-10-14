package io.github.vladimir_kalinin.stereocamera

import android.Manifest
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
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import io.github.vladimir_kalinin.stereocamera.camera.CaptureMode
import io.github.vladimir_kalinin.stereocamera.camera.VideoAspect
import io.github.vladimir_kalinin.stereocamera.camera.VideoFormat
import io.github.vladimir_kalinin.stereocamera.camera.StereoCamera
import io.github.vladimir_kalinin.stereocamera.camera.StereoRig
import io.github.vladimir_kalinin.stereocamera.databinding.ActivityMainBinding
import io.github.vladimir_kalinin.stereocamera.databinding.SheetSettingsBinding
import io.github.vladimir_kalinin.stereocamera.databinding.SheetVideoSettingsBinding
import io.github.vladimir_kalinin.stereocamera.gl.StereoRenderer
import io.github.vladimir_kalinin.stereocamera.media.MediaSaver
import io.github.vladimir_kalinin.stereocamera.media.SbsVideoRecorder
import io.github.vladimir_kalinin.stereocamera.media.StillComposer
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var saver: MediaSaver
    private lateinit var videoRecorder: SbsVideoRecorder
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Сохранение файлов и управление записью видео — вне UI-потока, строго по очереди. */
    private val io = Executors.newSingleThreadExecutor()

    private var rig: StereoRig? = null
    private var renderer: StereoRenderer? = null
    private var camera: StereoCamera? = null
    private var previewSurface: Surface? = null

    private var calibration = StereoRenderer.Calibration()
    private var overlayPreview = true
    private var showStreamInfo = true
    private var eyeFrame = false
    private var frameShift = 0f
    private var mode = CaptureMode.VIDEO
    private var videoFormat = VideoFormat()
    /** Открытая панель настроек видео: обновляется при смене формата. */
    private var videoSettings: SheetVideoSettingsBinding? = null
    private var videoSettingsDialog: BottomSheetDialog? = null
    private var settings: SheetSettingsBinding? = null
    private var settingsDialog: BottomSheetDialog? = null
    /** Частота кадров, с которой камера фактически работает (для энкодера). */
    private var streamFrameRate = 30
    /** Камера настроена на полноразмерные JPEG (режим фото). */
    private var stillsEnabled = false
    private var isRecording = false
    private var recordingStartedAt = 0L
    /** Строки о размерах потоков для карточки статуса: подпись → значение. */
    private var streamRows = emptyList<Pair<String, String>>()
    private var wideStreamSize: Size? = null
    private var permissionAsked = false
    private var cameraStalled = false
    /** Последний снимок или видео — открывается по нажатию на миниатюру. */
    private var lastMediaUri: Uri? = null
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
        videoRecorder = SbsVideoRecorder(saver)
        calibration = loadCalibration()
        mode = loadMode()

        rig = try {
            StereoRig.detect(getSystemService(CameraManager::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка поиска камер", e)
            null
        }
        videoFormat = loadVideoFormat()
        if (rig == null) {
            showStatus(listOf("Error" to "no pair of rear cameras\nthat can run concurrently"))
            binding.shutterButton.isEnabled = false
        }

        binding.preview.surfaceTextureListener = previewListener
        binding.shutterButton.setOnClickListener {
            if (mode == CaptureMode.PHOTO) takePhoto() else toggleRecording()
        }
        updateShutterUi()
        binding.modeSwitch.addOnButtonCheckedListener { _, id, isChecked ->
            if (isChecked) switchMode(if (id == R.id.photoModeButton) CaptureMode.PHOTO else CaptureMode.VIDEO)
        }
        binding.videoSettingsButton.setOnClickListener { showVideoSettings() }
        binding.galleryButton.setOnClickListener { openLastMedia() }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).let {
            overlayPreview = it.getBoolean(KEY_OVERLAY, true)
            showStreamInfo = it.getBoolean(KEY_STREAM_INFO, true)
            eyeFrame = it.getBoolean(KEY_EYE_FRAME, false)
            frameShift = it.getFloat(KEY_FRAME_SHIFT, 0f).coerceIn(0f, 0.1f)
        }
        binding.aboutButton.setOnClickListener { showAbout() }
        binding.calibrationButton.setOnClickListener { showSettings() }
        io.execute {
            saver.moveToCommonFolder()
            saver.latestMedia()?.let { showLastMedia(it) }
        }
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
        videoSettingsDialog?.dismiss()
        settingsDialog?.dismiss()
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

    // ---------- Кнопки громкости ----------

    /** Кнопки громкости работают как спуск: снимок в режиме фото, старт/стоп записи в режиме видео. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isShutterKey(keyCode)) return super.onKeyDown(keyCode, event)
        // Удержание кнопки не должно делать серию снимков или сразу останавливать запись
        if (event.repeatCount == 0 && binding.shutterButton.isEnabled) binding.shutterButton.performClick()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        isShutterKey(keyCode) || super.onKeyUp(keyCode, event)

    private fun isShutterKey(keyCode: Int) =
        rig != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)

    // ---------- Камера и рендерер ----------

    /** Показывает технические параметры столбиком: подписи слева, значения справа. */
    private fun showStatus(rows: List<Pair<String, String>>) {
        binding.statusLabels.text = rows.joinToString("\n") { it.first }
        binding.statusValues.text = rows.joinToString("\n") { it.second }
        // Ошибку поиска камер показываем всегда, параметры потока — если включены в настройках
        val show = rows.isNotEmpty() && (showStreamInfo || rig == null)
        binding.statusPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun startCamera() {
        val rig = rig ?: return
        if (camera != null) return

        val renderer = StereoRenderer(rig, object : StereoRenderer.Listener {
            override fun onStats(stats: StereoRenderer.Stats) {
                runOnUiThread {
                    showStatus(streamRows + listOf(
                        "Frame rate" to String.format(Locale.US, "%.0f / %d fps", stats.fps, streamFrameRate),
                        "Pair skew" to String.format(Locale.US, "%.1f ms", stats.maxPairSkewMs),
                        "Unpaired" to stats.droppedFrames.toString(),
                    ))
                    cameraStalled = stats.cameraStalled
                    updateWarning()
                }
            }
        })
        renderer.calibration = calibration
        renderer.overlayPreview = overlayPreview
        renderer.eyeFrame = eyeFrame
        renderer.frameShift = frameShift
        val (wideSurface, mainSurface) = renderer.start()
        this.renderer = renderer
        previewSurface?.let { renderer.setPreviewSurface(it) }

        camera = StereoCamera(getSystemService(CameraManager::class.java), rig, object : StereoCamera.Listener {
            override fun onStreamSizesChosen(wideSize: Size, mainSize: Size, frameRate: Int): Pair<Surface, Surface> {
                renderer.setStreamSizes(wideSize, mainSize, frameRate)
                return wideSurface to mainSurface
            }

            override fun onStarted(wideSize: Size, mainSize: Size, frameRate: Int, stillsEnabled: Boolean) {
                runOnUiThread {
                    this@MainActivity.stillsEnabled = stillsEnabled
                    streamFrameRate = frameRate
                    wideStreamSize = wideSize
                    updateStreamRows()
                    showStatus(streamRows)
                    updatePreviewLayout()
                }
            }

            override fun onError(message: String) {
                runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
            }
        }, mode, videoFormat, wideSizeOverride()).also { it.open() }
    }

    /** Размер итогового кадра (с рамкой, если она включена) и поток широкоугольной для карточки статуса. */
    private fun updateStreamRows() {
        val rig = rig ?: return
        val renderer = renderer ?: return
        val wideSize = wideStreamSize ?: return
        val main = rig.main.jpegSize
        streamRows = if (stillsEnabled && main != null) {
            // JPEG в ориентации сенсора, глаз — портрет
            val eye = if (eyeFrame) StereoRenderer.framedSize(main.height, main.width) else Size(main.height, main.width)
            listOf("Photo" to "${eye.width * 2}×${eye.height}")
        } else {
            val out = renderer.framedOutputSize()
            listOf("SBS" to "${out.width}×${out.height}")
        } + ("Wide stream" to "${wideSize.width}×${wideSize.height}")
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

    /** Смена формата видео перезапускает камеру: размеры потоков и fps задаются при настройке сессии. */
    private fun switchVideoFormat(newFormat: VideoFormat) {
        if (newFormat == videoFormat || isRecording) return
        videoFormat = newFormat
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_VIDEO_ASPECT, videoFormat.aspect.name)
            .putInt(KEY_VIDEO_FPS, videoFormat.frameRate)
            .apply()
        updateShutterUi()
        if (camera != null) {
            stopCamera()
            startCamera()
        }
    }

    private fun loadVideoFormat(): VideoFormat {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val name = prefs.getString(KEY_VIDEO_ASPECT, null)
        val aspect = VideoAspect.entries.firstOrNull { it.name == name } ?: VideoAspect.RATIO_4_3
        var format = VideoFormat(aspect).withFrameRate(prefs.getInt(KEY_VIDEO_FPS, 30))
        // Сохранённый формат может не поддерживаться этой камерой
        if (rig?.supports60 != true) format = format.withFrameRate(30)
        if (rig?.supports169 != true) format = format.withAspect(VideoAspect.RATIO_4_3)
        return format
    }

    private fun loadMode(): CaptureMode {
        val name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_MODE, null)
        return CaptureMode.entries.firstOrNull { it.name == name } ?: CaptureMode.VIDEO
    }

    private fun updateShutterUi() {
        val icon = when {
            isRecording -> R.drawable.shutter_stop
            mode == CaptureMode.PHOTO -> R.drawable.shutter_photo
            else -> R.drawable.shutter_video
        }
        binding.shutterButton.setImageResource(icon)
        binding.shutterButton.contentDescription = getString(
            when {
                isRecording -> R.string.stop_video
                mode == CaptureMode.PHOTO -> R.string.take_photo
                else -> R.string.record_video
            }
        )
        binding.modeSwitch.check(if (mode == CaptureMode.PHOTO) R.id.photoModeButton else R.id.videoModeButton)
        // Во время записи всё, что перезапускает камеру или открывает другие экраны, недоступно
        binding.photoModeButton.isEnabled = !isRecording
        binding.videoModeButton.isEnabled = !isRecording
        updateVideoFormatUi()
        for (view in listOf(binding.galleryButton, binding.aboutButton)) {
            view.isEnabled = !isRecording
            view.animate().alpha(if (isRecording) 0f else 1f).setDuration(150)
        }
    }

    /** Кнопка формата видео: видна только в режиме видео и если камера умеет что-то кроме 4:3. */
    private fun updateVideoFormatUi() {
        val is169 = videoFormat.aspect == VideoAspect.RATIO_16_9
        binding.videoSettingsButton.visibility =
            if (mode == CaptureMode.VIDEO && rig?.supports169 == true) View.VISIBLE else View.INVISIBLE
        binding.videoSettingsButton.text = getString(R.string.video_format_short,
            getString(if (is169) R.string.aspect_16_9 else R.string.aspect_4_3), videoFormat.frameRate)
        binding.videoSettingsButton.isEnabled = !isRecording

        val sheet = videoSettings ?: return
        sheet.aspectValue.setText(if (is169) R.string.aspect_16_9_value else R.string.aspect_4_3_value)
        sheet.aspect43Option.isChecked = !is169
        sheet.aspect169Option.isChecked = is169
        sheet.frameRateValue.text = videoFormat.frameRate.toString()
        sheet.fps30Option.isChecked = videoFormat.frameRate == 30
        sheet.fps60Option.isChecked = videoFormat.frameRate == 60
    }

    /** Панель «Настройки видео» по образцу Google Камеры. */
    private fun showVideoSettings() {
        if (isRecording || videoSettingsDialog != null) return
        val sheet = SheetVideoSettingsBinding.inflate(layoutInflater)
        sheet.frameRateRow.visibility = if (rig?.supports60 == true) View.VISIBLE else View.GONE
        // Варианты ведут себя как переключатели: нажатие на выбранный ничего не меняет
        fun option(button: View, format: () -> VideoFormat) = button.setOnClickListener {
            switchVideoFormat(format())
            updateVideoFormatUi()
        }
        option(sheet.aspect43Option) { videoFormat.withAspect(VideoAspect.RATIO_4_3) }
        option(sheet.aspect169Option) { videoFormat.withAspect(VideoAspect.RATIO_16_9) }
        option(sheet.fps30Option) { videoFormat.withFrameRate(30) }
        option(sheet.fps60Option) { videoFormat.withFrameRate(60) }

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)
        dialog.setOnDismissListener {
            videoSettings = null
            videoSettingsDialog = null
        }
        videoSettings = sheet
        videoSettingsDialog = dialog
        updateVideoFormatUi()
        dialog.show()
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
            cameraStalled -> "One of the cameras stopped sending frames. This is usually overheating — let the phone cool down"
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> "Phone is overheating: Android is lowering the camera frame rate"
            thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT -> "Phone is getting warm — frames may be dropped"
            else -> null
        }
        binding.warningText.text = text
        binding.warningText.visibility = if (text != null) View.VISIBLE else View.GONE
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
            val frame = eyeFrame
            val frameShift = frameShift
            camera.captureStill { still ->
                if (still == null) {
                    onPhotoSaved(null)
                    return@captureStill
                }
                io.execute {
                    val uri = try {
                        val bitmap = StillComposer.compose(rig, calibration, frame, frameShift, still.wideJpeg, still.mainJpeg)
                        saver.savePhoto(bitmap).also { bitmap.recycle() }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Не удалось собрать фото", e)
                        null
                    }
                    onPhotoSaved(uri)
                }
            }
        } else {
            // Фото из кадра превью
            renderer.capturePhoto { pixels, width, height ->
                io.execute {
                    val uri = saver.savePhoto(pixels, width, height)
                    onPhotoSaved(uri)
                }
            }
        }
    }

    private fun onPhotoSaved(uri: Uri?) {
        if (uri != null) showLastMedia(uri)
        runOnUiThread {
            binding.shutterButton.isEnabled = true
            if (uri == null) Toast.makeText(this, "Couldn't save the photo", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Миниатюра последнего снимка ----------

    /** Загружает миниатюру и показывает её на кнопке галереи. Вызывать не на UI-потоке. */
    private fun showLastMedia(uri: Uri) {
        val size = resources.getDimensionPixelSize(R.dimen.thumbnail_size)
        val thumbnail = saver.loadThumbnail(uri, size)
        runOnUiThread {
            lastMediaUri = uri
            if (thumbnail == null) return@runOnUiThread
            binding.galleryButton.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            binding.galleryButton.imageTintList = null
            binding.galleryButton.setImageBitmap(thumbnail)
            binding.galleryButton.scaleX = 0.6f
            binding.galleryButton.scaleY = 0.6f
            binding.galleryButton.animate().scaleX(1f).scaleY(1f).setDuration(200)
        }
    }

    private fun openLastMedia() {
        val uri = lastMediaUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Видео ----------

    private fun toggleRecording() {
        if (isRecording) stopRecording(wait = false) else startRecording()
    }

    private fun startRecording() {
        val renderer = renderer ?: return
        val frame = eyeFrame
        val size = renderer.framedOutputSize(frame)
        if (size.width == 0) return
        val withAudio = hasPermission(Manifest.permission.RECORD_AUDIO)
        val frameRate = streamFrameRate

        binding.shutterButton.isEnabled = false
        io.execute {
            try {
                val surface = videoRecorder.start(size, frameRate, withAudio)
                renderer.startRecording(surface, frame)
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
                    Toast.makeText(this, "Couldn't start recording: ${e.message}", Toast.LENGTH_LONG).show()
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
            // Рендерер перестаёт рисовать в поверхность энкодера до остановки энкодера
            renderer?.stopRecording()
            val uri = videoRecorder.stop()
            if (uri != null) showLastMedia(uri)
            runOnUiThread {
                binding.shutterButton.isEnabled = true
                if (uri == null) Toast.makeText(this, "Video wasn't saved", Toast.LENGTH_SHORT).show()
            }
        }
        if (wait) task.get()
    }

    private fun updateRecordingUi() {
        updateShutterUi()
        binding.recordingIndicator.visibility = if (isRecording) View.VISIBLE else View.GONE
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

    // ---------- Настройки: подстройка совмещения и превью ----------

    /** Шторка «Настройки» по образцу панели видео; превью под ней не затемняется, чтобы видеть совмещение. */
    private fun showSettings() {
        if (settingsDialog != null) return
        val sheet = SheetSettingsBinding.inflate(layoutInflater)
        sheet.scaleSlider.value = scaleToProgress(calibration.scale).toFloat()
        sheet.verticalSlider.value = shiftToProgress(calibration.verticalShift).toFloat()
        sheet.horizontalSlider.value = shiftToProgress(calibration.horizontalShift).toFloat()
        val changeListener = Slider.OnChangeListener { _, _, fromUser ->
            if (!fromUser) return@OnChangeListener
            calibration = calibration.copy(
                scale = progressToScale(sheet.scaleSlider.value.toInt()),
                verticalShift = progressToShift(sheet.verticalSlider.value.toInt()),
                horizontalShift = progressToShift(sheet.horizontalSlider.value.toInt()),
            )
            applyCalibration()
        }
        val touchListener = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = saveCalibration()
        }
        for (slider in listOf(sheet.scaleSlider, sheet.verticalSlider, sheet.horizontalSlider)) {
            slider.addOnChangeListener(changeListener)
            slider.addOnSliderTouchListener(touchListener)
        }
        sheet.resetCalibrationButton.setOnClickListener { resetCalibration() }
        sheet.swapEyesSwitch.isChecked = calibration.swapEyes
        sheet.swapEyesSwitch.setOnCheckedChangeListener { _, isChecked -> setSwapEyes(isChecked) }
        sheet.overlaySwitch.isChecked = overlayPreview
        sheet.overlaySwitch.setOnCheckedChangeListener { _, isChecked -> setOverlayPreview(isChecked) }
        sheet.eyeFrameSwitch.isChecked = eyeFrame
        sheet.eyeFrameSwitch.setOnCheckedChangeListener { _, isChecked -> setEyeFrame(isChecked) }
        sheet.frameShiftSlider.value = frameShiftToProgress(frameShift).toFloat()
        sheet.frameShiftSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setFrameShift(progressToFrameShift(value.toInt()))
        }
        sheet.frameShiftSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) =
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putFloat(KEY_FRAME_SHIFT, frameShift).apply()
        })
        sheet.streamInfoSwitch.isChecked = showStreamInfo
        sheet.streamInfoSwitch.setOnCheckedChangeListener { _, isChecked -> setShowStreamInfo(isChecked) }

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)
        dialog.window?.setDimAmount(0f)
        dialog.setOnDismissListener {
            settings = null
            settingsDialog = null
            binding.calibrationButton.isChecked = false
        }
        settings = sheet
        settingsDialog = dialog
        updateCalibrationLabels()
        binding.calibrationButton.isChecked = true
        dialog.show()
    }

    private fun applyCalibration() {
        renderer?.calibration = calibration
        updateCalibrationLabels()
    }

    private fun updateCalibrationLabels() {
        val sheet = settings ?: return
        val total = (rig?.wideCropFactor ?: 1f) * calibration.scale
        sheet.scaleValue.text = String.format(Locale.US, "%.3f× (total %.3f×)", calibration.scale, total)
        sheet.verticalValue.text = String.format(Locale.US, "%+.1f%%", calibration.verticalShift * 100)
        sheet.horizontalValue.text = String.format(Locale.US, "%+.1f%%", calibration.horizontalShift * 100)
        sheet.frameShiftValue.text = String.format(Locale.US, "%+.1f%%", frameShift * 100)
        // Сдвиг рамки имеет смысл только с рамкой
        val frameShiftVisibility = if (eyeFrame) View.VISIBLE else View.GONE
        sheet.frameShiftRow.visibility = frameShiftVisibility
        sheet.frameShiftSlider.visibility = frameShiftVisibility
    }

    // Масштаб: 0.90..1.10, сдвиги: −5%..+5% кадра; середина ползунка — без подстройки
    private fun progressToScale(progress: Int) = 0.9f + progress / 1000f
    private fun scaleToProgress(scale: Float) = ((scale - 0.9f) * 1000).toInt().coerceIn(0, 200)
    private fun progressToShift(progress: Int) = (progress - 100) / 2000f
    private fun shiftToProgress(shift: Float) = (shift * 2000 + 100).toInt().coerceIn(0, 200)
    // Сдвиг рамки: 0..10% ширины кадра
    private fun progressToFrameShift(progress: Int) = progress / 2000f
    private fun frameShiftToProgress(shift: Float) = (shift * 2000).toInt().coerceIn(0, 200)

    private fun loadCalibration(): StereoRenderer.Calibration {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return StereoRenderer.Calibration(
            scale = prefs.getFloat(KEY_SCALE, 1f),
            verticalShift = prefs.getFloat(KEY_VERTICAL, 0f),
            horizontalShift = prefs.getFloat(KEY_HORIZONTAL, 0f),
            swapEyes = prefs.getBoolean(KEY_SWAP, false),
        )
    }

    private fun saveCalibration() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putFloat(KEY_SCALE, calibration.scale)
            .putFloat(KEY_VERTICAL, calibration.verticalShift)
            .putFloat(KEY_HORIZONTAL, calibration.horizontalShift)
            .putBoolean(KEY_SWAP, calibration.swapEyes)
            .apply()
    }

    private fun resetCalibration() {
        calibration = StereoRenderer.Calibration(swapEyes = calibration.swapEyes)
        settings?.let {
            it.scaleSlider.value = scaleToProgress(calibration.scale).toFloat()
            it.verticalSlider.value = shiftToProgress(calibration.verticalShift).toFloat()
            it.horizontalSlider.value = shiftToProgress(calibration.horizontalShift).toFloat()
        }
        applyCalibration()
        saveCalibration()
    }

    private fun setOverlayPreview(overlay: Boolean) {
        if (overlay == overlayPreview) return
        overlayPreview = overlay
        renderer?.overlayPreview = overlay
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_OVERLAY, overlay).apply()
        updatePreviewLayout()
    }

    private fun setEyeFrame(frame: Boolean) {
        if (frame == eyeFrame) return
        eyeFrame = frame
        renderer?.eyeFrame = frame
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_EYE_FRAME, frame).apply()
        updateCalibrationLabels()
        updatePreviewLayout()
        updateStreamRows()
        showStatus(streamRows)
    }

    private fun setFrameShift(shift: Float) {
        frameShift = shift
        renderer?.frameShift = shift
        updateCalibrationLabels()
    }

    private fun setShowStreamInfo(show: Boolean) {
        if (show == showStreamInfo) return
        showStreamInfo = show
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_STREAM_INFO, show).apply()
        showStatus(streamRows)
    }

    /** Пропорции превью: в режиме наложения — один глаз, иначе весь кадр SBS (с рамкой — вместе с её полями). */
    private fun updatePreviewLayout() {
        val out = renderer?.framedOutputSize() ?: return
        if (out.width == 0) return
        val width = if (overlayPreview) out.width / 2 else out.width
        (binding.preview.layoutParams as ConstraintLayout.LayoutParams).dimensionRatio = "$width:${out.height}"
        binding.preview.requestLayout()
        binding.eyeDivider.visibility = if (overlayPreview) View.GONE else View.VISIBLE
    }

    private fun setSwapEyes(swap: Boolean) {
        if (swap == calibration.swapEyes) return
        calibration = calibration.copy(swapEyes = swap)
        applyCalibration()
        saveCalibration()
    }

    // ---------- О камерах ----------

    private fun showAbout() {
        val rig = rig ?: return
        val left = if (rig.wideIsLeft != calibration.swapEyes) "wide" else "main"
        val lines = listOf(
            "Logical camera: ${rig.logicalId}",
            "Wide: id ${rig.wide.id}, ${rig.wide.focalLengthMm} mm",
            "Main: id ${rig.main.id}, ${rig.main.focalLengthMm} mm",
            "Wide crop: ${String.format(Locale.US, "%.3f", rig.wideCropFactor)}×",
            "Baseline: ${rig.baselineMeters?.let { String.format(Locale.US, "%.1f mm", it * 1000) } ?: "unknown"}",
            "Left eye: $left",
        ) + streamRows.map { (label, value) -> "$label: $value" } +
            "Files: Gallery → ${MediaSaver.ALBUM}"
        val message = lines.joinToString("\n")
        MaterialAlertDialogBuilder(this).setTitle(R.string.about).setMessage(message).setPositiveButton("OK", null).show()
    }

    // ---------- Разрешения ----------

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Camera access required")
            .setMessage("The app can't work without camera permission. The microphone is only used for audio in videos.")
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Close") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "stereo_calibration"
        private const val KEY_SCALE = "scale"
        private const val KEY_VERTICAL = "vertical_shift"
        private const val KEY_HORIZONTAL = "horizontal_shift"
        private const val KEY_SWAP = "swap_eyes"
        private const val KEY_OVERLAY = "overlay_preview"
        private const val KEY_STREAM_INFO = "show_stream_info"
        private const val KEY_EYE_FRAME = "eye_frame"
        private const val KEY_FRAME_SHIFT = "frame_shift"
        private const val KEY_MODE = "capture_mode"
        private const val KEY_VIDEO_ASPECT = "video_aspect"
        private const val KEY_VIDEO_FPS = "video_fps"
    }
}
