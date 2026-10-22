package dev.lukas.glyphstatus

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Non-text drawings for the round matrix. Every function only lights pixels that exist. */
object MatrixGraphics {

    const val BRIGHTNESS_TRACK = 24
    const val BRIGHTNESS_STOPPED = 40

    /** Outermost visible pixels, clockwise starting at 12 o'clock. */
    fun ringPixels(length: Int): List<Pair<Int, Int>> {
        val center = (length - 1) / 2.0
        val edge = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until length) {
            for (x in 0 until length) {
                if (!visible(length, x, y)) continue
                val onEdge = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) -> !visible(length, x + dx, y + dy) }
                if (onEdge) edge += x to y
            }
        }
        return edge.sortedBy { (x, y) ->
            val angle = Math.toDegrees(atan2(x - center, -(y - center)))
            (angle + 360) % 360
        }
    }

    /** [percent] of the edge ring lit clockwise from the top, the rest as a faint track. */
    fun ring(length: Int, percent: Int, brightness: Int): IntArray {
        val frame = IntArray(length * length)
        val pixels = ringPixels(length)
        val lit = (pixels.size * percent.coerceIn(0, 100) / 100.0).roundToInt()
        pixels.forEachIndexed { i, (x, y) ->
            frame[y * length + x] = if (i < lit) brightness else BRIGHTNESS_TRACK
        }
        return frame
    }

    /** True if no lit pixel of [frame] touches (8-neighbourhood) a ring pixel. */
    fun keepsGapToRing(length: Int, frame: IntArray): Boolean {
        val ring = ringPixels(length).toSet()
        for (y in 0 until length) {
            for (x in 0 until length) {
                if (frame[y * length + x] == 0) continue
                for (dy in -1..1) for (dx in -1..1) if ((x + dx) to (y + dy) in ring) return false
            }
        }
        return true
    }

    /** Where the label line and the bars of a history view go. */
    class HistoryLayout(val textY: Int, val barLeft: Int, val barCount: Int, val barTop: Int, val barBottom: Int)

    fun historyLayout(length: Int) =
        if (length == 13) HistoryLayout(textY = 2, barLeft = 2, barCount = 9, barTop = 8, barBottom = 11)
        else HistoryLayout(textY = 3, barLeft = 3, barCount = 19, barTop = 12, barBottom = 20)

    /** Bar chart of the newest values (0..100, oldest first), right-aligned, in the lower part. */
    fun historyBars(length: Int, values: List<Int>, brightness: Int): IntArray {
        val frame = IntArray(length * length)
        val layout = historyLayout(length)
        val height = layout.barBottom - layout.barTop + 1
        val shown = values.takeLast(layout.barCount)
        val firstColumn = layout.barLeft + layout.barCount - shown.size
        shown.forEachIndexed { i, value ->
            val bar = if (value <= 0) 0 else ((value.coerceAtMost(100) * height + 99) / 100)
            val x = firstColumn + i
            for (k in 0 until height) {
                val y = layout.barBottom - k
                if (visible(length, x, y)) frame[y * length + x] = if (k < bar) brightness else BRIGHTNESS_TRACK
            }
        }
        return frame
    }

    /**
     * One dot per guest ('r' running bright, 's' stopped dim, 'l' locked blinking) in a centered grid.
     * Null if there are too many guests to fit.
     */
    fun dots(length: Int, states: String, blinkOn: Boolean): IntArray? {
        if (states.isEmpty()) return null
        // 25x25 prefers 2x2 dots; both sizes fall back to single-pixel dots.
        val variants = if (length == 13) listOf(1 to 2) else listOf(2 to 3, 1 to 2)
        for ((size, step) in variants) {
            dotGrid(length, states, blinkOn, size, step)?.let { return it }
        }
        return null
    }

    private fun dotGrid(length: Int, states: String, blinkOn: Boolean, size: Int, step: Int): IntArray? {
        val columns = ceil(sqrt(states.length.toDouble())).toInt()
        val rows = (states.length + columns - 1) / columns
        val x0 = (length - ((columns - 1) * step + size)) / 2
        val y0 = (length - ((rows - 1) * step + size)) / 2
        val frame = IntArray(length * length)
        states.forEachIndexed { i, state ->
            val brightness = when (state) {
                'r' -> MatrixText.BRIGHTNESS_DIGIT
                'l' -> if (blinkOn) MatrixText.BRIGHTNESS_DIGIT else 0
                else -> BRIGHTNESS_STOPPED
            }
            val left = x0 + (i % columns) * step
            val top = y0 + (i / columns) * step
            for (dy in 0 until size) {
                for (dx in 0 until size) {
                    if (!visible(length, left + dx, top + dy)) return null
                    frame[(top + dy) * length + left + dx] = brightness
                }
            }
        }
        return frame
    }

    /** "No connection" sign: a circle with a diagonal slash. */
    fun noNetwork(length: Int, brightness: Int): IntArray {
        val frame = IntArray(length * length)
        val center = (length - 1) / 2.0
        val radius = if (length == 13) 4.0 else 8.0
        for (y in 0 until length) {
            for (x in 0 until length) {
                val onCircle = abs(hypot(x - center, y - center) - radius) < 0.5
                // bottom-left to top-right diagonal inside the circle
                val onSlash = x + y == length - 1 && hypot(x - center, y - center) < radius
                if ((onCircle || onSlash) && visible(length, x, y)) frame[y * length + x] = brightness
            }
        }
        return frame
    }

    /** Scales all lit pixels, keeping them at least 1 so nothing disappears. */
    fun scale(frame: IntArray, factor: Double): IntArray =
        IntArray(frame.size) { i -> if (frame[i] == 0) 0 else (frame[i] * factor).roundToInt().coerceIn(1, 255) }

    /** Pixel-wise maximum of several layers. */
    fun combine(vararg layers: IntArray): IntArray =
        IntArray(layers[0].size) { i -> layers.maxOf { it[i] } }

    private fun visible(length: Int, x: Int, y: Int) =
        x in 0 until length && y in 0 until length && MatrixIcons.isVisible(length, x, y)
}
