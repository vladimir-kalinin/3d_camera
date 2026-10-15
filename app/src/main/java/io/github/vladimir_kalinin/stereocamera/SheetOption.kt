package io.github.vladimir_kalinin.stereocamera

import android.animation.ValueAnimator
import com.google.android.material.button.MaterialButton

/**
 * Вариант настройки в шторке, как в Google Камере: выбранный — круг, остальные — квадрат со скруглением.
 * Форма меняется плавно, если кнопка уже на экране.
 */
fun MaterialButton.roundWhenChecked() {
    val squareRadius = cornerRadius
    fun target() = if (isChecked) maxOf(width, height, minimumHeight) / 2 else squareRadius
    var animator: ValueAnimator? = null
    addOnCheckedChangeListener { _, _ ->
        animator?.cancel()
        if (!isLaidOut) return@addOnCheckedChangeListener
        animator = ValueAnimator.ofInt(cornerRadius, target()).apply {
            duration = 200
            addUpdateListener { cornerRadius = it.animatedValue as Int }
            start()
        }
    }
    // До первой раскладки размер неизвестен — начальную форму ставим после неё
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (animator?.isRunning != true) cornerRadius = target()
    }
}
