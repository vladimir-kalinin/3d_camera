package io.github.vladimir_kalinin.stereocamera

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * С targetSdk 35+ Android всегда рисует окно под системными полосами, цвета полос из темы игнорируются.
 * Полосы делаем прозрачными со светлыми значками (тема всегда тёмная, даже при светлой системной),
 * а корневой view получает отступы, чтобы содержимое не уходило под статусбар, вырез и полосу жестов.
 * Фон корневого view при этом заливает и область под полосами.
 */
fun ComponentActivity.setupEdgeToEdge(root: View) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
