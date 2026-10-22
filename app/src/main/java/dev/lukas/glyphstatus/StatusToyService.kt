package dev.lukas.glyphstatus

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.text.format.DateFormat
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class StatusToyService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var checkJob: Job? = null

    private var glyphMatrixManager: GlyphMatrixManager? = null
    private var connected = false
    private var matrixLength = 25

    /** null = never checked. */
    private var status: CachedStatus? = null
    private var displayStore: DisplaySettingsStore? = null
    private var display = DisplaySettings.DEFAULT
    private var tick = 0
    private var lastFrame: IntArray? = null

    private val toyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                GlyphToy.MSG_GLYPH_TOY -> {
                    val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
                    if (GlyphToy.EVENT_AOD == event) refreshStatus()
                }
                else -> super.handleMessage(msg)
            }
        }
    }
    private val serviceMessenger = Messenger(toyHandler)

    private val gmmCallback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(componentName: ComponentName?) {
            val gmm = glyphMatrixManager ?: return
            gmm.register(if (Common.is25111p()) Glyph.DEVICE_25111p else Glyph.DEVICE_23112)
            connected = true
            lastFrame = null
            render()
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            connected = false
        }
    }

    /** Picks up view/interval changes made in the app while the toy is running. */
    private val displayListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val before = display.views
        displayStore?.let { display = it.load() }
        // A newly enabled view may need data the last check did not fetch.
        if (!before.containsAll(display.views)) refreshStatus()
        render()
    }

    /** Drives blinking (every tick) and the view rotation (every [DisplaySettings.intervalSeconds]). */
    private val ticker = object : Runnable {
        override fun run() {
            tick++
            render()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        matrixLength = Common.getDeviceMatrixLength().takeIf { it == 13 || it == 25 } ?: 25
        status = StatusCache(this).load()
        displayStore = DisplaySettingsStore(this).also {
            display = it.load()
            it.prefs.registerOnSharedPreferenceChangeListener(displayListener)
        }
        tick = 0
        lastFrame = null

        glyphMatrixManager = GlyphMatrixManager.getInstance(applicationContext).also {
            it.init(gmmCallback)
        }
        mainHandler.postDelayed(ticker, TICK_MS)
        refreshStatus()
        return serviceMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        mainHandler.removeCallbacks(ticker)
        displayStore?.prefs?.unregisterOnSharedPreferenceChangeListener(displayListener)
        displayStore = null
        scope?.cancel()
        scope = null
        checkJob = null
        glyphMatrixManager?.turnOff()
        glyphMatrixManager?.unInit()
        glyphMatrixManager = null
        connected = false
        return false
    }

    private fun refreshStatus() {
        val scope = scope ?: return
        if (checkJob?.isActive == true) return
        val views = display.views
        checkJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                val config = runCatching { ConfigStore(applicationContext).load() }.getOrNull()
                if (config == null) {
                    CheckResult(false, "Einstellungen nicht lesbar")
                } else {
                    ProxmoxClient.check(config, views)
                }
            }
            Log.d(TAG, "Status: up=${result.up} (${result.message})")
            val newStatus = CachedStatus(
                up = result.up,
                uptimeSeconds = result.uptimeSeconds,
                guestsRunning = result.guestsRunning,
                guestsTotal = result.guestsTotal,
                cpuPercent = result.cpuPercent,
                memPercent = result.memPercent,
                diskPercent = result.diskPercent,
                loadAverage = result.loadAverage,
                netMbit = result.netMbit,
                backupOk = result.backupOk,
                checkedAtMillis = System.currentTimeMillis(),
            )
            StatusCache(applicationContext).store(newStatus)
            status = newStatus
            render()
        }
    }

    private fun render() {
        val gmm = glyphMatrixManager ?: return
        if (!connected) return
        val current = status
        val frame = if (current?.up == false) {
            Screens.down(matrixLength, blinkOn = tick % 2 == 0)
        } else {
            val views = Screens.views(current, display.views)
            val ticksPerView = (display.intervalSeconds * 1000 / TICK_MS).toInt().coerceAtLeast(1)
            val view = views[(tick / ticksPerView) % views.size]
            val now = Calendar.getInstance()
            Screens.view(
                length = matrixLength,
                view = view,
                status = current,
                nowMillis = now.timeInMillis,
                hour = now.get(Calendar.HOUR_OF_DAY),
                minute = now.get(Calendar.MINUTE),
                is24Hour = DateFormat.is24HourFormat(this),
            )
        }
        // Only push to the matrix when the picture actually changes.
        if (lastFrame?.contentEquals(frame) == true) return
        try {
            gmm.setMatrixFrame(frame)
            lastFrame = frame
        } catch (e: Exception) {
            Log.w(TAG, "setMatrixFrame failed", e)
        }
    }

    private companion object {
        const val TAG = "StatusToyService"
        /** Blink half-period of the DOWN cross; also the resolution of the rotation timer. */
        const val TICK_MS = 500L
    }
}
