package dev.lukas.glyphstatus

import android.app.Service
import android.content.ComponentName
import android.content.Intent
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

    /** Drives blinking (every tick) and the view rotation (every [TICKS_PER_VIEW] ticks). */
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
        checkJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                val config = runCatching { ConfigStore(applicationContext).load() }.getOrNull()
                if (config == null) {
                    CheckResult(false, "Einstellungen nicht lesbar")
                } else {
                    ProxmoxClient.check(config)
                }
            }
            Log.d(TAG, "Status: up=${result.up} (${result.message})")
            val newStatus = CachedStatus(
                up = result.up,
                uptimeSeconds = result.uptimeSeconds,
                guestsRunning = result.guestsRunning,
                guestsTotal = result.guestsTotal,
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
            val views = Screens.views(current)
            val view = views[(tick / TICKS_PER_VIEW) % views.size]
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
        /** Blink half-period of the DOWN cross. */
        const val TICK_MS = 500L
        /** 10 x 500 ms = each view is shown for 5 s. */
        const val TICKS_PER_VIEW = 10
    }
}
