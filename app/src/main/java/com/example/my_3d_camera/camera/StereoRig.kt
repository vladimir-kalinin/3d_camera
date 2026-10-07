package com.example.my_3d_camera.camera

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Range
import android.util.Size
import kotlin.math.abs

/**
 * Описание стереопары: логическая камера и две физические камеры внутри неё,
 * плюс всё, что нужно для совмещения кадров (масштаб, оптические центры, порядок глаз).
 *
 * Широкоугольная камера всегда кропается до поля зрения основной,
 * поэтому её поток берём с большим разрешением, чем у основной.
 */
enum class CaptureMode { PHOTO, VIDEO }

data class StereoRig(
    val logicalId: String,
    val wide: PhysicalCamera,
    val main: PhysicalCamera,
    /** true, если широкоугольная камера стоит левее основной (смотря на экран). */
    val wideIsLeft: Boolean,
    /** Расстояние между объективами в метрах, если известно. */
    val baselineMeters: Float?,
    val timestampSourceRealtime: Boolean,
    val fpsRange: Range<Int>,
) {
    data class PhysicalCamera(
        val id: String,
        val focalLengthMm: Float,
        /** Фокусное расстояние, нормированное на ширину кадра в портретной ориентации. */
        val normFocal: Float,
        /** То же, но посчитанное по фокусному расстоянию и размеру сенсора в мм. */
        val physicalNormFocal: Float,
        /** Оптический центр в портретных координатах кадра (0..1, y вниз). */
        val principalU: Float,
        val principalV: Float,
        val sensorOrientation: Int,
        val outputSizes: List<Size>,
    )

    /** Во сколько раз широкоугольный кадр нужно увеличить, чтобы совпасть с основным. */
    val wideCropFactor: Float get() = main.normFocal / wide.normFocal

    /**
     * Пары размеров потоков (широкоугольная, основная), от лучшей к худшей.
     *
     * Широкоугольный кадр кропается примерно вдвое, поэтому для полной резкости левого глаза
     * её поток должен быть вдвое больше основного. Но камера Pixel 9 на таком потоке
     * выдаёт только ~25 fps, поэтому для видео берём одинаковые потоки и честные 30 fps.
     */
    fun streamSizeCandidates(mode: CaptureMode, wideOverride: Size? = null): List<Pair<Size, Size>> {
        val mainSizes = PREFERRED_MAIN_SIZES.filter { it in main.outputSizes }
        val wide43 = wide.outputSizes.filter { isFourByThree(it) }.sortedBy { it.width }
        val sharp = mainSizes.mapNotNull { mainSize ->
            val needWidth = mainSize.width * wideCropFactor
            val wideSize = wide43.firstOrNull { it.width >= needWidth } ?: wide43.lastOrNull()
            wideSize?.let { it to mainSize }
        }
        val fast = mainSizes.filter { it in wide.outputSizes }.map { it to it }

        val result = mutableListOf<Pair<Size, Size>>()
        if (wideOverride != null && wideOverride in wide.outputSizes && mainSizes.isNotEmpty()) {
            result += wideOverride to mainSizes.first()
        }
        result += if (mode == CaptureMode.PHOTO) sharp + fast else fast + sharp
        return result.distinct()
    }

    companion object {
        private const val TAG = "StereoRig"

        /** Размеры основного потока 4:3 по убыванию качества. */
        private val PREFERRED_MAIN_SIZES = listOf(
            Size(2048, 1536),
            Size(1920, 1440),
            Size(1600, 1200),
            Size(1440, 1080),
            Size(1280, 960),
        )

        private fun isFourByThree(size: Size) = abs(size.width * 3 - size.height * 4) <= 8

        /**
         * Ищет заднюю логическую камеру с двумя физическими камерами
         * и выбирает из них самую широкую и ближайшую к ней по полю зрения.
         */
        fun detect(manager: CameraManager): StereoRig? {
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val physicalIds = chars.physicalCameraIds
                if (physicalIds.size < 2) continue

                val cameras = physicalIds.mapNotNull { pid ->
                    describe(pid, manager.getCameraCharacteristics(pid))
                }
                // Сортируем по полю зрения: чем меньше нормированный фокус, тем шире камера.
                // Для выбора берём оценку по физическим размерам: калибровка у служебных
                // камер (например, 2x-кроп сенсора на Pixel) бывает некорректной
                val byFov = cameras.sortedBy { it.physicalNormFocal }
                if (byFov.size < 2) continue
                val wide = byFov[0]
                val main = byFov[1]

                val widePose = manager.getCameraCharacteristics(wide.id).get(CameraCharacteristics.LENS_POSE_TRANSLATION)
                val mainPose = manager.getCameraCharacteristics(main.id).get(CameraCharacteristics.LENS_POSE_TRANSLATION)
                var wideIsLeft = true
                var baseline: Float? = null
                if (widePose != null && mainPose != null) {
                    // Ось X системы координат сенсоров направлена вправо при портретной ориентации
                    val dx = widePose[0] - mainPose[0]
                    val dy = widePose[1] - mainPose[1]
                    wideIsLeft = dx < 0
                    baseline = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (abs(dy) > abs(dx)) {
                        Log.w(TAG, "Объективы расположены вертикально, стерео в портрете будет некорректным")
                    }
                }

                val timestampRealtime = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                val fpsRange = fpsRanges.firstOrNull { it.lower == 30 && it.upper == 30 }
                    ?: fpsRanges.filter { it.upper == 30 }.maxByOrNull { it.lower }
                    ?: Range(30, 30)

                val rig = StereoRig(id, wide, main, wideIsLeft, baseline, timestampRealtime, fpsRange)
                Log.i(TAG, "Стереопара: логическая=$id, wide=${wide.id} (${wide.focalLengthMm}мм), " +
                        "main=${main.id} (${main.focalLengthMm}мм), кроп=${"%.3f".format(rig.wideCropFactor)}, " +
                        "wideIsLeft=$wideIsLeft, база=${baseline?.let { "%.1f мм".format(it * 1000) }}, fps=$fpsRange")
                return rig
            }
            return null
        }

        private fun describe(id: String, chars: CameraCharacteristics): PhysicalCamera? {
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
            val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()

            // Портретная ширина кадра соответствует высоте сенсора (сенсор повёрнут на 90°)
            val rotated = orientation % 180 != 0
            val sensorW = active.width().toFloat()
            val sensorH = active.height().toFloat()
            val portraitWidthPx = if (rotated) sensorH else sensorW

            val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
            val physW = physical.width * sensorW / pixelArray.width
            val physH = physical.height * sensorH / pixelArray.height
            val physicalNormFocal = focal / (if (rotated) physH else physW)

            // Калибровка объектива точнее, но используем её, только если она согласуется с физикой
            var normFocal = physicalNormFocal
            var cx = sensorW / 2
            var cy = sensorH / 2
            val intrinsics = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            if (intrinsics != null && intrinsics[0] > 0f) {
                val calibrated = (if (rotated) intrinsics[1] else intrinsics[0]) / portraitWidthPx
                if (abs(calibrated / physicalNormFocal - 1f) < 0.1f) {
                    normFocal = calibrated
                    cx = intrinsics[2]
                    cy = intrinsics[3]
                } else {
                    Log.w(TAG, "Калибровка камеры $id не согласуется с физикой ($calibrated vs $physicalNormFocal), игнорируем")
                }
            }

            // Переводим оптический центр из координат сенсора в портретные координаты кадра
            val (pu, pv) = sensorToPortrait(cx / sensorW, cy / sensorH, orientation)
            return PhysicalCamera(id, focal, normFocal, physicalNormFocal, pu, pv, orientation, sizes)
        }

        /** Нормированные координаты сенсора (y вниз) → портретные координаты (y вниз). */
        fun sensorToPortrait(x: Float, y: Float, orientation: Int): Pair<Float, Float> = when (orientation) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }
    }
}
