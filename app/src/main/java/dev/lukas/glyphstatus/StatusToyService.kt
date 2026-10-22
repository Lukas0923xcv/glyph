package dev.lukas.glyphstatus

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
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

class StatusToyService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var checkJob: Job? = null

    private var glyphMatrixManager: GlyphMatrixManager? = null
    private var connected = false
    private var matrixLength = 25

    /** null = unknown (nothing cached yet). */
    private var serverUp: Boolean? = null
    private var blinkOn = true

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
            render()
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            connected = false
        }
    }

    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            render()
            mainHandler.postDelayed(this, BLINK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        matrixLength = Common.getDeviceMatrixLength().takeIf { it == 13 || it == 25 } ?: 25
        serverUp = StatusCache(this).lastUp()

        glyphMatrixManager = GlyphMatrixManager.getInstance(applicationContext).also {
            it.init(gmmCallback)
        }
        updateBlinking()
        refreshStatus()
        return serviceMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        mainHandler.removeCallbacks(blinkRunnable)
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
            StatusCache(applicationContext).store(result.up)
            serverUp = result.up
            updateBlinking()
            render()
        }
    }

    private fun updateBlinking() {
        mainHandler.removeCallbacks(blinkRunnable)
        blinkOn = true
        if (serverUp == false) {
            mainHandler.postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
        }
    }

    private fun render() {
        val gmm = glyphMatrixManager ?: return
        if (!connected) return
        val frame = when (serverUp) {
            true -> MatrixIcons.toFrame(MatrixIcons.check(matrixLength), MatrixIcons.BRIGHTNESS_UP)
            false -> if (blinkOn) {
                MatrixIcons.toFrame(MatrixIcons.cross(matrixLength), MatrixIcons.BRIGHTNESS_DOWN)
            } else {
                IntArray(matrixLength * matrixLength)
            }
            null -> IntArray(matrixLength * matrixLength)
        }
        try {
            gmm.setMatrixFrame(frame)
        } catch (e: Exception) {
            Log.w(TAG, "setMatrixFrame failed", e)
        }
    }

    private companion object {
        const val TAG = "StatusToyService"
        const val BLINK_INTERVAL_MS = 500L
    }
}
