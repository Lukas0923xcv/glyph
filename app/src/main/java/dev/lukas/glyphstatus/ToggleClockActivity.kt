package dev.lukas.glyphstatus

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible launcher entry "Uhr ⇄ Rotation": switches between clock only and the rotation and
 * closes immediately. Meant for a hardware key or gesture that can only "open an app".
 */
class ToggleClockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clockOnly = DisplaySettingsStore(applicationContext).toggleClockOnly()
        Toast.makeText(this, if (clockOnly) R.string.mode_clock_only else R.string.mode_rotation, Toast.LENGTH_SHORT).show()
        finish()
    }
}
