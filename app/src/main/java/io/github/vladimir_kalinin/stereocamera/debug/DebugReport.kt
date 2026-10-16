package io.github.vladimir_kalinin.stereocamera.debug

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.PowerManager
import io.github.vladimir_kalinin.stereocamera.BuildConfig
import io.github.vladimir_kalinin.stereocamera.MainActivity
import io.github.vladimir_kalinin.stereocamera.camera.StereoRig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Отчёт для разбора проблем у тестеров: телефон, все камеры с характеристиками,
 * которые важны для выбора пары, настройки приложения и logcat.
 *
 * Обычное приложение видит в logcat только записи своего uid (включая прошлые
 * процессы, например упавшие), поэтому разрешение READ_LOGS не нужно.
 * Логи cameraserver/HAL сюда не попадают — что видно лишь им, нужно логировать самим.
 */
object DebugReport {

    /**
     * Собирает отчёт в файл в cache/reports (его отдаёт FileProvider, см. res/xml/file_paths.xml).
     * Старые отчёты удаляются, чтобы не копились.
     */
    fun writeFile(context: Context): File {
        val dir = File(context.cacheDir, "reports")
        dir.deleteRecursively()
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "stereocamera-log-$stamp.txt")
        file.writeText(collect(context))
        return file
    }

    fun collect(context: Context): String = buildString {
        section("Device") { appendDevice(context) }
        section("Cameras") { appendCameras(context) }
        section("Settings") { appendSettings(context) }
        section("Logcat") { appendLogcat() }
    }

    private inline fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        append("===== ").append(title).append(" =====\n")
        try {
            body()
        } catch (e: Exception) {
            append("failed: ").append(e).append('\n')
        }
        append('\n')
    }

    private fun StringBuilder.appendDevice(context: Context) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        append("Time: ").append(time).append('\n')
        append("App: ").append(BuildConfig.APPLICATION_ID).append(' ')
            .append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(", ")
            .append(BuildConfig.BUILD_TYPE).append(")\n")
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.DEVICE).append(", ").append(Build.HARDWARE).append(")\n")
        append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
            .append("), build ").append(Build.DISPLAY).append('\n')
        val power = context.getSystemService(PowerManager::class.java)
        append("Thermal status: ").append(power.currentThermalStatus).append(" (0 none … 3 moderate … 6 shutdown)\n")
        val memory = Runtime.getRuntime()
        append("Heap: max ").append(memory.maxMemory() / 1_048_576).append(" MB\n")
    }

    private fun StringBuilder.appendCameras(context: Context) {
        val manager = context.getSystemService(CameraManager::class.java)
        val rig = try {
            StereoRig.detect(manager)
        } catch (e: Exception) {
            append("StereoRig.detect failed: ").append(e).append('\n')
            null
        }
        append("Selected pair: ")
        append(rig?.let { "logical ${it.logicalId}, wide ${it.wide.id}, main ${it.main.id}, " +
                "crop ${"%.3f".format(Locale.US, it.wideCropFactor)}, wideIsLeft ${it.wideIsLeft}" } ?: "none")
            .append('\n')
        append("Concurrent camera sets: ").append(manager.concurrentCameraIds).append('\n')

        // Физические камеры описываем один раз, даже если они видны и как отдельные id
        val described = mutableSetOf<String>()
        for (id in manager.cameraIdList) {
            appendCamera(manager, id, "")
            described += id
            val physicalIds = manager.getCameraCharacteristics(id).physicalCameraIds
            for (pid in physicalIds) {
                if (described.add(pid)) appendCamera(manager, pid, "    ")
            }
        }
    }

    private fun StringBuilder.appendCamera(manager: CameraManager, id: String, indent: String) {
        val c = try {
            manager.getCameraCharacteristics(id)
        } catch (e: Exception) {
            append(indent).append("Camera ").append(id).append(": ").append(e).append('\n')
            return
        }
        fun line(name: String, value: Any?) {
            append(indent).append("  ").append(name).append(": ").append(value).append('\n')
        }

        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "?"
        }
        append(indent).append("Camera ").append(id).append(" (").append(facing).append(")\n")
        line("hardware level", c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
        line("capabilities", c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString())
        if (c.physicalCameraIds.isNotEmpty()) {
            line("physical ids", c.physicalCameraIds)
            line("sensor sync", c.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE))
        }
        line("focal lengths", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString())
        line("physical size", c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
        line("pixel array", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE))
        line("active array", c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
        line("orientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
        line("timestamp source", c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
        line("pose reference", c.get(CameraCharacteristics.LENS_POSE_REFERENCE))
        line("pose translation", c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.joinToString())
        line("pose rotation", c.get(CameraCharacteristics.LENS_POSE_ROTATION)?.joinToString())
        line("intrinsics", c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.joinToString())
        line("distortion", c.get(CameraCharacteristics.LENS_DISTORTION)?.joinToString())
        line("min focus distance", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE))
        line("zoom range", c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE))
        line("fps ranges", c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString())
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            val sizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            line("preview sizes", sizes.joinToString { size ->
                val fps = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                    .takeIf { it > 0 }?.let { 1_000_000_000L / it }
                if (fps != null) "$size@$fps" else size.toString()
            })
            line("jpeg sizes", map.getOutputSizes(ImageFormat.JPEG)?.joinToString())
        }
    }

    private fun StringBuilder.appendSettings(context: Context) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.all.toSortedMap().forEach { (key, value) -> append(key).append(" = ").append(value).append('\n') }
    }

    private fun StringBuilder.appendLogcat() {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", "main,system,crash")
            .redirectErrorStream(true)
            .start()
        append(process.inputStream.bufferedReader().use { it.readText() })
        process.waitFor()
    }
}
