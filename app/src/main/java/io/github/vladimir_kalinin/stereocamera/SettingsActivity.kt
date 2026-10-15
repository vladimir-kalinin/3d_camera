package io.github.vladimir_kalinin.stereocamera

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.vladimir_kalinin.stereocamera.camera.StereoRig
import io.github.vladimir_kalinin.stereocamera.databinding.ActivitySettingsBinding
import io.github.vladimir_kalinin.stereocamera.media.MediaSaver
import java.util.Locale

/** Экран настроек, которые не нужно подстраивать, глядя на превью. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setupEdgeToEdge(binding.root)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        binding.streamInfoSwitch.isChecked = prefs.getBoolean(MainActivity.KEY_STREAM_INFO, true)
        binding.streamInfoSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(MainActivity.KEY_STREAM_INFO, isChecked).apply()
        }
        binding.aboutRow.setOnClickListener { showAbout() }
    }

    // ---------- О камерах ----------

    private fun showAbout() {
        val rig = try {
            StereoRig.detect(getSystemService(CameraManager::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка поиска камер", e)
            null
        }
        if (rig == null) {
            Toast.makeText(this, "No pair of rear cameras that can run concurrently", Toast.LENGTH_LONG).show()
            return
        }
        val swapEyes = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_SWAP, false)
        val left = if (rig.wideIsLeft != swapEyes) "wide" else "main"
        // Размеры потоков знает только работающая камера — их передаёт главный экран
        val labels = intent.getStringArrayExtra(EXTRA_STREAM_LABELS).orEmpty()
        val values = intent.getStringArrayExtra(EXTRA_STREAM_VALUES).orEmpty()
        val lines = listOf(
            "Logical camera: ${rig.logicalId}",
            "Wide: id ${rig.wide.id}, ${rig.wide.focalLengthMm} mm",
            "Main: id ${rig.main.id}, ${rig.main.focalLengthMm} mm",
            "Wide crop: ${String.format(Locale.US, "%.3f", rig.wideCropFactor)}×",
            "Baseline: ${rig.baselineMeters?.let { String.format(Locale.US, "%.1f mm", it * 1000) } ?: "unknown"}",
            "Left eye: $left",
        ) + labels.zip(values).map { (label, value) -> "$label: $value" } +
            "Files: Gallery → ${MediaSaver.ALBUM}"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        private const val TAG = "SettingsActivity"
        private const val EXTRA_STREAM_LABELS = "stream_labels"
        private const val EXTRA_STREAM_VALUES = "stream_values"

        fun intent(context: Context, streamRows: List<Pair<String, String>>): Intent =
            Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_STREAM_LABELS, streamRows.map { it.first }.toTypedArray())
                .putExtra(EXTRA_STREAM_VALUES, streamRows.map { it.second }.toTypedArray())
    }
}
