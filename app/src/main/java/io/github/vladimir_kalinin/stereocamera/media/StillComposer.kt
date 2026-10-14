package io.github.vladimir_kalinin.stereocamera.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import io.github.vladimir_kalinin.stereocamera.camera.StereoRig
import io.github.vladimir_kalinin.stereocamera.gl.ColorMatch
import io.github.vladimir_kalinin.stereocamera.gl.StereoRenderer

/**
 * Собирает полноразмерное SBS-фото из двух JPEG (в ориентации сенсора).
 * Геометрия та же, что в GL-рендерере: поворот в портрет, кроп широкоугольной до поля
 * зрения основной с совмещением оптических центров и ручная подстройка.
 * Глаз = полный кадр основной камеры, повёрнутый в портрет.
 * Цвет широкоугольной подгоняется под основную (см. [ColorMatch]) по этой же паре кадров:
 * у полноразмерного снимка своя обработка, коэффициенты превью к нему не подходят.
 */
object StillComposer {

    fun compose(
        rig: StereoRig, calibration: StereoRenderer.Calibration, frame: Boolean, frameShift: Float,
        wideJpeg: ByteArray, mainJpeg: ByteArray,
    ): Bitmap {
        val mainBitmap = BitmapFactory.decodeByteArray(mainJpeg, 0, mainJpeg.size)
            ?: error("Не удалось декодировать кадр основной камеры")
        val rotated = rig.main.sensorOrientation % 180 != 0
        val eyeWidth = if (rotated) mainBitmap.height else mainBitmap.width
        val eyeHeight = if (rotated) mainBitmap.width else mainBitmap.height

        // С рамкой кадр глаза лежит в полях целиком, без потери разрешения
        val padding = if (frame) StereoRenderer.framePadding(eyeWidth) else 0
        val cellWidth = eyeWidth + 2 * padding
        val output = Bitmap.createBitmap(cellWidth * 2, eyeHeight + 2 * padding, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val wideLeft = rig.wideIsLeft != calibration.swapEyes
        val mainX = (if (wideLeft) cellWidth else 0) + padding
        val wideX = (if (wideLeft) 0 else cellWidth) + padding
        val top = padding

        // Основная камера: только поворот
        val mainMatrix = eyeMatrix(mainBitmap.width, mainBitmap.height, rig.main.sensorOrientation,
            1f, 0f, 1f, 0f, eyeWidth, eyeHeight, mainX, top)
        drawClipped(canvas, mainBitmap, mainMatrix, mainX, top, eyeWidth, eyeHeight, paint)
        mainBitmap.recycle()

        // Широкоугольная: u_w = s * u + ou (как в шейдере), декодируем только нужную область
        val s = 1f / (rig.wideCropFactor * calibration.scale)
        val ou = rig.wide.principalU - rig.main.principalU * s + calibration.wideShiftU(rig)
        val ov = rig.wide.principalV - rig.main.principalV * s + calibration.verticalShift
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(wideJpeg, 0, wideJpeg.size, bounds)
        val wideMatrix = eyeMatrix(bounds.outWidth, bounds.outHeight, rig.wide.sensorOrientation,
            s, ou, s, ov, eyeWidth, eyeHeight, wideX, top)

        val inverse = Matrix().also { wideMatrix.invert(it) }
        val needed = RectF(wideX.toFloat(), top.toFloat(), (wideX + eyeWidth).toFloat(), (top + eyeHeight).toFloat())
        inverse.mapRect(needed)
        val region = Rect()
        needed.roundOut(region)
        region.inset(-2, -2)
        if (region.intersect(0, 0, bounds.outWidth, bounds.outHeight)) {
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(wideJpeg, 0, wideJpeg.size, false)
                ?: error("Не удалось открыть кадр широкоугольной камеры")
            val wideBitmap = try {
                decoder.decodeRegion(region, null)
            } finally {
                decoder.recycle()
            }
            // Пиксель (x, y) области = пиксель (x + left, y + top) полного кадра
            val regionMatrix = Matrix(wideMatrix).apply { preTranslate(region.left.toFloat(), region.top.toFloat()) }
            val widePaint = Paint(paint)
            colorGains(output, mainX, wideBitmap, regionMatrix, wideX, top, eyeWidth, eyeHeight)?.let { g ->
                widePaint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(g[0], g[1], g[2], 1f) })
            }
            drawClipped(canvas, wideBitmap, regionMatrix, wideX, top, eyeWidth, eyeHeight, widePaint)
            wideBitmap.recycle()
        }
        if (frame) drawFrame(canvas, eyeWidth, eyeHeight, frameShift)
        return output
    }

    /** Чёрная рамка со скруглёнными углами вокруг каждого глаза, как в шейдере рендерера. */
    private fun drawFrame(canvas: Canvas, eyeWidth: Int, eyeHeight: Int, shift: Float) {
        val cell = StereoRenderer.framedSize(eyeWidth, eyeHeight)
        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for (leftEye in listOf(true, false)) {
            val x = if (leftEye) 0f else cell.width.toFloat()
            val shape = StereoRenderer.frameShape(eyeWidth, eyeHeight, leftEye, shift)
            path.addRect(x, 0f, x + cell.width, cell.height.toFloat(), Path.Direction.CW)
            path.addRoundRect(RectF(shape.window).apply { offset(x, 0f) }, shape.radius, shape.radius, Path.Direction.CW)
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK })
    }

    /**
     * Сравнивает цвет глаз по сетке точек: основной глаз берётся из уже нарисованного
     * кадра, широкоугольный — из исходного кадра через обратную матрицу.
     */
    private fun colorGains(
        output: Bitmap, mainX: Int, wide: Bitmap, wideMatrix: Matrix, wideX: Int, top: Int, eyeWidth: Int, eyeHeight: Int,
    ): FloatArray? {
        val inverse = Matrix().also { wideMatrix.invert(it) }
        val match = ColorMatch()
        val point = FloatArray(2)
        for (gy in 0 until GRID_H) {
            for (gx in 0 until GRID_W) {
                val x = (gx + 0.5f) * eyeWidth / GRID_W
                val y = (gy + 0.5f) * eyeHeight / GRID_H
                point[0] = wideX + x
                point[1] = top + y
                inverse.mapPoints(point)
                val wx = point[0].toInt()
                val wy = point[1].toInt()
                if (wx !in 0 until wide.width || wy !in 0 until wide.height) continue
                match.addArgb(output.getPixel(mainX + x.toInt(), top + y.toInt()), wide.getPixel(wx, wy))
            }
        }
        return match.gains()
    }

    private fun drawClipped(
        canvas: Canvas, bitmap: Bitmap, matrix: Matrix, x: Int, y: Int, width: Int, height: Int, paint: Paint,
    ) {
        canvas.save()
        canvas.clipRect(x, y, x + width, y + height)
        canvas.drawBitmap(bitmap, matrix, paint)
        canvas.restore()
    }

    /**
     * Матрица из пикселей кадра сенсора в пиксели глаза в итоговом SBS.
     * Нормированные портретные координаты источника связаны с координатами глаза так:
     * u_src = su * u + ou, v_src = sv * v + ov.
     */
    private fun eyeMatrix(
        srcWidth: Int, srcHeight: Int, orientation: Int,
        su: Float, ou: Float, sv: Float, ov: Float,
        eyeWidth: Int, eyeHeight: Int, offsetX: Int, offsetY: Int,
    ): Matrix = Matrix().apply {
        // Кадр сенсора → портрет (как StereoRig.sensorToPortrait)
        when (orientation) {
            90 -> { postRotate(90f); postTranslate(srcHeight.toFloat(), 0f) }
            180 -> { postRotate(180f); postTranslate(srcWidth.toFloat(), srcHeight.toFloat()) }
            270 -> { postRotate(270f); postTranslate(0f, srcWidth.toFloat()) }
        }
        val rotated = orientation % 180 != 0
        val portraitWidth = (if (rotated) srcHeight else srcWidth).toFloat()
        val portraitHeight = (if (rotated) srcWidth else srcHeight).toFloat()
        postScale(1f / portraitWidth, 1f / portraitHeight)
        // Координаты источника → координаты глаза: u = (u_src - ou) / su
        postTranslate(-ou, -ov)
        postScale(1f / su, 1f / sv)
        postScale(eyeWidth.toFloat(), eyeHeight.toFloat())
        postTranslate(offsetX.toFloat(), offsetY.toFloat())
    }

    /** Сетка точек для сравнения цвета (портрет 3:4). */
    private const val GRID_W = 96
    private const val GRID_H = 128
}
