package dev.lukas.glyphstatus

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings tile: active = matrix shows only the clock. */
class ClockToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update(DisplaySettingsStore(applicationContext).load().clockOnly)
    }

    override fun onClick() {
        super.onClick()
        update(DisplaySettingsStore(applicationContext).toggleClockOnly())
    }

    private fun update(clockOnly: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (clockOnly) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(if (clockOnly) R.string.mode_clock_only else R.string.mode_rotation)
        tile.updateTile()
    }
}
