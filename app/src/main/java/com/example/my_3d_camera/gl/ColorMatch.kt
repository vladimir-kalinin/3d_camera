package com.example.my_3d_camera.gl

import kotlin.math.pow

/**
 * Подгонка цвета широкоугольной камеры под основную.
 *
 * Автобаланс белого и автоэкспозиция у физических камер работают независимо
 * (CONTROL_AWB_MODE задаётся только для логической камеры), а широкоугольная видит
 * больше сцены — поэтому на закатах и при смешанном свете глаза расходятся по цвету.
 * Аппаратно выровнять нельзя без отключения автобаланса, поэтому сравниваем средние
 * R/G/B двух глаз по одинаковым точкам и считаем покомпонентные коэффициенты.
 *
 * Средние считаются в линейном свете (гамма 2.2), пересвеченные и слишком тёмные точки
 * пропускаются. Коэффициент для кодированных в гамме значений = линейный^(1/2.2).
 */
class ColorMatch {
    private val sumMain = DoubleArray(3)
    private val sumWide = DoubleArray(3)
    private var samples = 0
    private var total = 0

    /** Добавляет пару соответствующих точек (компоненты 0..255). */
    fun add(mr: Int, mg: Int, mb: Int, wr: Int, wg: Int, wb: Int) {
        total++
        if (maxOf(mr, mg, mb, wr, wg, wb) >= CLIPPED) return
        if (mr + mg + mb < DARK || wr + wg + wb < DARK) return
        sumMain[0] += LINEAR[mr]; sumMain[1] += LINEAR[mg]; sumMain[2] += LINEAR[mb]
        sumWide[0] += LINEAR[wr]; sumWide[1] += LINEAR[wg]; sumWide[2] += LINEAR[wb]
        samples++
    }

    /** Добавляет точку в формате ARGB (как Bitmap.getPixel). */
    fun addArgb(main: Int, wide: Int) = add(
        (main shr 16) and 0xFF, (main shr 8) and 0xFF, main and 0xFF,
        (wide shr 16) and 0xFF, (wide shr 8) and 0xFF, wide and 0xFF,
    )

    /**
     * Коэффициенты (R, G, B) для гамма-значений широкоугольной камеры,
     * или null, если годных точек слишком мало.
     */
    fun gains(): FloatArray? {
        if (samples < MIN_SAMPLES || samples * 10 < total) return null
        return FloatArray(3) { c ->
            if (sumWide[c] <= 0.0) return null
            val linear = (sumMain[c] / sumWide[c]).toFloat().coerceIn(MIN_GAIN, MAX_GAIN)
            linear.pow(1f / GAMMA)
        }
    }

    fun reset() {
        sumMain.fill(0.0)
        sumWide.fill(0.0)
        samples = 0
        total = 0
    }

    companion object {
        private const val GAMMA = 2.2f
        private const val CLIPPED = 245
        private const val DARK = 24
        private const val MIN_SAMPLES = 50
        /** Пределы линейного коэффициента: больше — уже не разница настроек камер, а ошибка. */
        const val MIN_GAIN = 0.5f
        const val MAX_GAIN = 2f
        private val LINEAR = DoubleArray(256) { (it / 255.0).pow(GAMMA.toDouble()) }
    }
}
