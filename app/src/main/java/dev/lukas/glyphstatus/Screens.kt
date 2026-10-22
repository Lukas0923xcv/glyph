package dev.lukas.glyphstatus

/**
 * What the matrix shows. While the server is UP (or not checked yet) the toy rotates
 * through [views]; while it is DOWN a blinking cross replaces all of them.
 */
object Screens {

    /** In rotation order. */
    enum class View { UPTIME, GUESTS, CPU, MEM, DISK, CLOCK }

    /**
     * The [enabled] views that have data to show, in rotation order.
     * Falls back to the clock so the matrix is never empty.
     */
    fun views(status: CachedStatus?, enabled: Set<View>): List<View> {
        val up = status?.up == true
        val available = View.entries.filter { view ->
            view in enabled && when (view) {
                View.UPTIME -> up && status?.uptimeSeconds != null
                View.GUESTS -> up && status?.guestsRunning != null && status.guestsTotal != null
                View.CPU -> up && status?.cpuPercent != null
                View.MEM -> up && status?.memPercent != null
                View.DISK -> up && status?.diskPercent != null
                View.CLOCK -> true
            }
        }
        return available.ifEmpty { listOf(View.CLOCK) }
    }

    fun down(length: Int, blinkOn: Boolean): IntArray =
        if (blinkOn) {
            MatrixIcons.toFrame(MatrixIcons.cross(length), MatrixIcons.BRIGHTNESS_DOWN)
        } else {
            IntArray(length * length)
        }

    fun view(
        length: Int,
        view: View,
        status: CachedStatus?,
        nowMillis: Long,
        hour: Int,
        minute: Int,
        is24Hour: Boolean,
    ): IntArray {
        val candidates = when (view) {
            View.UPTIME -> uptimeCandidates(status, nowMillis)
            View.GUESTS -> {
                val running = status?.guestsRunning
                val total = status?.guestsTotal
                if (running == null || total == null) emptyList()
                else listOf(listOf("$running/$total"), listOf("$running"))
            }
            View.CPU -> percentCandidates('C', status?.cpuPercent)
            View.MEM -> percentCandidates('R', status?.memPercent)
            View.DISK -> percentCandidates('D', status?.diskPercent)
            View.CLOCK -> {
                val h = if (is24Hour) hour.toString().padStart(2, '0') else (if (hour % 12 == 0) 12 else hour % 12).toString()
                val m = minute.toString().padStart(2, '0')
                listOf(listOf("$h:$m"), listOf(h, m))
            }
        }
        return MatrixText.render(length, candidates) ?: IntArray(length * length)
    }

    /** "C35" etc.; the letter tells the percentages apart. 100 falls back to 99 where it does not fit. */
    private fun percentCandidates(label: Char, percent: Int?): List<List<String>> {
        if (percent == null) return emptyList()
        return listOf(listOf("$label$percent"), listOf("$label${percent.coerceAtMost(99)}"))
    }

    private fun uptimeCandidates(status: CachedStatus?, nowMillis: Long): List<List<String>> {
        val base = status?.uptimeSeconds ?: return emptyList()
        val elapsed = ((nowMillis - status.checkedAtMillis) / 1000).coerceAtLeast(0)
        val seconds = base + elapsed
        val days = seconds / 86_400
        return if (days >= 1) {
            listOf(listOf("${days}d"), listOf("$days"))
        } else {
            val hours = seconds / 3_600
            listOf(listOf("${hours}h"), listOf("$hours"))
        }
    }
}
