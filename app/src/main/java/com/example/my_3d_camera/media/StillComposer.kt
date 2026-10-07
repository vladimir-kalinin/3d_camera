package com.example.my_3d_camera.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.example.my_3d_camera.camera.StereoRig
import com.example.my_3d_camera.gl.ColorMatch
import com.example.my_3d_camera.gl.StereoRenderer

/**
 * Собирает полноразмерное SBS-фото из двух JPEG (в ориентации сенсора).
 * Геометрия та же, что в GL-рендерере: поворот в портрет, кроп широкоугольной до поля
 * зрения основной с совмещением оптических центров и ручная подстройка.
 * Глаз = полный кадр основной камеры, повёрнутый в портрет.
 * Цвет широкоугольной подгоняется под основную (см. [ColorMatch]) по этой же паре кадров:
 * у полноразмерного снимка своя обработка, коэффициенты превью к нему не подходят.
 */
object StillComposer {

    fun compose(rig: StereoRig, calibration: StereoRenderer.Calibration, wideJpeg: ByteArray, mainJpeg: ByteArray): Bitmap {
        val mainBitmap = BitmapFactory.decodeByteArray(mainJpeg, 0, mainJpeg.size)
            ?: error("Не удалось декодировать кадр основной камеры")
        val rotated = rig.main.sensorOrientation % 180 != 0
        val eyeWidth = if (rotated) mainBitmap.height else mainBitmap.width
        val eyeHeight = if (rotated) mainBitmap.width else mainBitmap.height

        val output = Bitmap.createBitmap(eyeWidth * 2, eyeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val wideLeft = rig.wideIsLeft != calibration.swapEyes
        val mainX = if (wideLeft) eyeWidth else 0
        val wideX = if (wideLeft) 0 else eyeWidth

        // Основная камера: только поворот
        val mainMatrix = eyeMatrix(mainBitmap.width, mainBitmap.height, rig.main.sensorOrientation,
            1f, 0f, 1f, 0f, eyeWidth, eyeHeight, mainX)
        drawClipped(canvas, mainBitmap, mainMatrix, mainX, eyeWidth, eyeHeight, paint)
        mainBitmap.recycle()

        // Широкоугольная: u_w = s * u + ou (как в шейдере), декодируем только нужную область
        val s = 1f / (rig.wideCropFactor * calibration.scale)
        val ou = rig.wide.principalU - rig.main.principalU * s
        val ov = rig.wide.principalV - rig.main.principalV * s + calibration.verticalShift
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(wideJpeg, 0, wideJpeg.size, bounds)
        val wideMatrix = eyeMatrix(bounds.outWidth, bounds.outHeight, rig.wide.sensorOrientation,
            s, ou, s, ov, eyeWidth, eyeHeight, wideX)

        val inverse = Matrix().also { wideMatrix.invert(it) }
        val needed = RectF(wideX.toFloat(), 0f, (wideX + eyeWidth).toFloat(), eyeHeight.toFloat())
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
            colorGains(output, mainX, wideBitmap, regionMatrix, wideX, eyeWidth, eyeHeight)?.let { g ->
                widePaint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(g[0], g[1], g[2], 1f) })
            }
            drawClipped(canvas, wideBitmap, regionMatrix, wideX, eyeWidth, eyeHeight, widePaint)
            wideBitmap.recycle()
        }
        return output
    }

    /**
     * Сравнивает цвет глаз по сетке точек: основной глаз берётся из уже нарисованного
     * кадра, широкоугольный — из исходного кадра через обратную матрицу.
     */
    private fun colorGains(
        output: Bitmap, mainX: Int, wide: Bitmap, wideMatrix: Matrix, wideX: Int, eyeWidth: Int, eyeHeight: Int,
    ): FloatArray? {
        val inverse = Matrix().also { wideMatrix.invert(it) }
        val match = ColorMatch()
        val point = FloatArray(2)
        for (gy in 0 until GRID_H) {
            for (gx in 0 until GRID_W) {
                val x = (gx + 0.5f) * eyeWidth / GRID_W
                val y = (gy + 0.5f) * eyeHeight / GRID_H
                point[0] = wideX + x
                point[1] = y
                inverse.mapPoints(point)
                val wx = point[0].toInt()
                val wy = point[1].toInt()
                if (wx !in 0 until wide.width || wy !in 0 until wide.height) continue
                match.addArgb(output.getPixel(mainX + x.toInt(), y.toInt()), wide.getPixel(wx, wy))
            }
        }
        return match.gains()
    }

    private fun drawClipped(canvas: Canvas, bitmap: Bitmap, matrix: Matrix, x: Int, width: Int, height: Int, paint: Paint) {
        canvas.save()
        canvas.clipRect(x, 0, x + width, height)
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
        eyeWidth: Int, eyeHeight: Int, offsetX: Int,
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
        postTranslate(offsetX.toFloat(), 0f)
    }

    /** Сетка точек для сравнения цвета (портрет 3:4). */
    private const val GRID_W = 96
    private const val GRID_H = 128
}
