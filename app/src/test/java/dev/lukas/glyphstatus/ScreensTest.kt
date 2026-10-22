package dev.lukas.glyphstatus

import dev.lukas.glyphstatus.Screens.AlertMode
import dev.lukas.glyphstatus.Screens.Design
import dev.lukas.glyphstatus.Screens.DownReason
import dev.lukas.glyphstatus.Screens.Fetch
import dev.lukas.glyphstatus.Screens.View
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensTest {

    private val now = 1_000_000_000L
    private val all = View.entries.toSet()
    private val defaults = DisplaySettings.DEFAULT

    private fun status(
        uptime: Long? = 3_628_800,
        running: Int? = 7,
        total: Int? = 9,
        up: Boolean = true,
        cpu: Int? = 35,
        mem: Int? = 62,
        disk: Int? = 47,
        load: Double? = 0.52,
        net: Int? = 12,
        backup: Boolean? = true,
        states: String? = "rrrrrrrss",
        cpuHistory: List<Int> = listOf(10, 20, 80, 40),
        updates: Int? = 3,
        health: Boolean? = true,
        quorate: Boolean? = null,
        reason: DownReason = DownReason.SERVER,
    ) = CachedStatus(
        up, uptime, running, total, cpu, mem, disk, load, net, backup, checkedAtMillis = now,
        guestStates = states, cpuHistory = cpuHistory, memHistory = cpuHistory, updates = updates,
        healthOk = health, quorate = quorate, downReason = reason,
    )

    private fun render(
        length: Int,
        view: View,
        status: CachedStatus?,
        settings: DisplaySettings = defaults,
        hour: Int = 12,
        minute: Int = 34,
        is24: Boolean = true,
        blinkOn: Boolean = true,
    ) = Screens.view(length, view, status, settings, now, hour, minute, is24, blinkOn)

    private fun lit(frame: IntArray) = frame.count { it > 0 }

    /** Every lit pixel must exist on the round matrix. */
    private fun assertInside(length: Int, frame: IntArray, what: String) {
        frame.forEachIndexed { i, v ->
            if (v > 0) assertTrue("$what: (${i % length},${i / length})", MatrixIcons.isVisible(length, i % length, i / length))
        }
    }

    private fun designs(vararg pairs: Pair<View, Design>) = defaults.copy(designs = pairs.toMap())

    @Test
    fun rotationContainsOnlyEnabledViewsWithData() {
        assertEquals(View.entries - View.QUORUM, Screens.views(status(), all))
        assertEquals(listOf(View.CLOCK), Screens.views(null, all))
        assertEquals(View.entries - View.GUESTS - View.QUORUM, Screens.views(status(running = null, total = null), all))
        assertEquals(listOf(View.GUESTS, View.DISK), Screens.views(status(), setOf(View.DISK, View.GUESTS)))
    }

    @Test
    fun fallsBackToClockWhenNothingSelectedHasData() {
        assertEquals(listOf(View.CLOCK), Screens.views(status(cpu = null), setOf(View.CPU)))
        assertEquals(listOf(View.CLOCK), Screens.views(status(), emptySet()))
        assertEquals(listOf(View.CLOCK), Screens.views(status(up = false), setOf(View.UPTIME)))
    }

    @Test
    fun everyViewAndDesignStaysInsideTheCircle() {
        val statuses = listOf(
            status(), status(cpu = 0, mem = 100, disk = 100, load = 99.0, net = 5000, updates = 0, health = false, quorate = false),
            status(states = "r".repeat(25), running = 25, total = 25), status(states = "rls".repeat(30), running = 30, total = 90),
        )
        for (length in listOf(13, 25)) {
            for (s in statuses) {
                for (view in View.entries) {
                    val choices = DisplaySettings.DESIGN_CHOICES[view] ?: listOf(Design.TEXT)
                    for (design in choices) {
                        for (byLoad in listOf(false, true)) {
                            val settings = defaults.copy(designs = mapOf(view to design), brightnessByLoad = byLoad)
                            assertInside(length, render(length, view, s, settings), "$view/$design on $length")
                        }
                    }
                }
            }
            for (reason in DownReason.entries) {
                val frame = Screens.down(length, blinkOn = true, reason = reason)
                assertInside(length, frame, "down $reason on $length")
                assertTrue("down $reason on $length", lit(frame) > 0)
            }
        }
    }

    @Test
    fun everyPercentageRendersInEveryDesign() {
        for (length in listOf(13, 25)) {
            for (p in 0..100) {
                for (view in listOf(View.CPU, View.MEM, View.DISK)) {
                    for (design in DisplaySettings.DESIGN_CHOICES.getValue(view)) {
                        val frame = render(length, view, status(cpu = p, mem = p, disk = p), designs(view to design))
                        assertTrue("$view $design $p on $length", lit(frame) > 0)
                    }
                }
            }
        }
        assertArrayEquals(MatrixText.render(13, listOf(listOf("C35"))), render(13, View.CPU, status()))
        assertArrayEquals(MatrixText.render(13, listOf(listOf("C99"))), render(13, View.CPU, status(cpu = 100)))
        assertArrayEquals(MatrixText.render(25, listOf(listOf("D100"))), render(25, View.DISK, status(disk = 100)))
    }

    @Test
    fun ringLightsTheMatchingShareOfTheEdge() {
        for (length in listOf(13, 25)) {
            val ring = MatrixGraphics.ringPixels(length)
            assertTrue(ring.size > 20)
            for (p in listOf(0, 25, 50, 100)) {
                val frame = render(length, View.CPU, status(cpu = p), designs(View.CPU to Design.RING))
                val lit = ring.count { (x, y) -> frame[y * length + x] == MatrixText.BRIGHTNESS_SYMBOL }
                assertEquals("$p % on $length", Math.round(ring.size * p / 100.0).toInt(), lit)
            }
        }
    }

    @Test
    fun ringShowsTheLabelOnlyWithAGap() {
        val ring = designs(View.CPU to Design.RING)
        val small = render(13, View.CPU, status(), ring)
        val ringOnly = MatrixGraphics.ring(13, 35, MatrixText.BRIGHTNESS_SYMBOL)
        assertArrayEquals(MatrixGraphics.combine(ringOnly, MatrixText.render(13, listOf(listOf("35")))!!), small)
        val big = render(25, View.CPU, status(), ring)
        val bigRing = MatrixGraphics.ring(25, 35, MatrixText.BRIGHTNESS_SYMBOL)
        assertArrayEquals(MatrixGraphics.combine(bigRing, MatrixText.render(25, listOf(listOf("C35")))!!), big)
    }

    @Test
    fun historyShowsTheNewestValuesRightAligned() {
        val layout = MatrixGraphics.historyLayout(13)
        val frame = render(13, View.CPU, status(cpuHistory = listOf(100, 0)), designs(View.CPU to Design.HISTORY))
        val newest = layout.barLeft + layout.barCount - 1
        // newest value 0 -> only the faint track; the one before (100) -> full bar
        assertEquals(MatrixGraphics.BRIGHTNESS_TRACK, frame[layout.barBottom * 13 + newest])
        assertEquals(MatrixText.BRIGHTNESS_SYMBOL, frame[layout.barTop * 13 + newest - 1])
    }

    @Test
    fun dotsShowEachGuestAndBlinkLockedOnes() {
        val settings = designs(View.GUESTS to Design.DOTS)
        val on = render(13, View.GUESTS, status(states = "rsl"), settings, blinkOn = true)
        val off = render(13, View.GUESTS, status(states = "rsl"), settings, blinkOn = false)
        assertEquals(3, lit(on))
        assertEquals(2, lit(off))
        assertEquals(1, on.count { it == MatrixGraphics.BRIGHTNESS_STOPPED })
        // 26 still fit on 13x13 (6x5 grid); 40 do not -> text
        val fits = status(states = "r".repeat(26), running = 26, total = 26)
        assertEquals(26, lit(render(13, View.GUESTS, fits, settings)))
        val many = status(states = "r".repeat(40), running = 40, total = 40)
        assertArrayEquals(MatrixText.renderFraction(13, "40", "40"), render(13, View.GUESTS, many, settings))
        // 25x25: 2x2 dots up to a 6x6 grid, then single-pixel dots
        assertEquals(26 * 4, lit(render(25, View.GUESTS, fits, settings)))
        assertEquals(40, lit(render(25, View.GUESTS, many, settings)))
    }

    @Test
    fun guestsShowBothNumbersUpTo99() {
        for (length in listOf(13, 25)) {
            for (total in 0..99) {
                for (running in listOf(0, total / 2, total)) {
                    val frame = render(length, View.GUESTS, status(running = running, total = total))
                    val expected = MatrixText.render(length, listOf(listOf("$running/$total")))
                        ?: MatrixText.renderFraction(length, "$running", "$total")
                    assertNotNull("$running/$total on $length", expected)
                    assertArrayEquals("$running/$total on $length", expected, frame)
                }
            }
        }
        assertArrayEquals(MatrixText.render(13, listOf(listOf("7/9"))), render(13, View.GUESTS, status()))
        assertNull(MatrixText.render(13, listOf(listOf("12/15"))))
    }

    @Test
    fun textViewsRender() {
        for (length in listOf(13, 25)) {
            for (tenths in 0..1500 step 7) {
                assertTrue(lit(render(length, View.LOAD, status(load = tenths / 10.0))) > 0)
            }
            for (mbit in 0..10_000 step 37) assertTrue(lit(render(length, View.NET, status(net = mbit))) > 0)
            for (count in 0..500 step 7) assertTrue(lit(render(length, View.UPDATES, status(updates = count))) > 0)
            for (ok in listOf(true, false)) {
                assertTrue(lit(render(length, View.BACKUP, status(backup = ok))) > 0)
                assertTrue(lit(render(length, View.HEALTH, status(health = ok))) > 0)
                assertTrue(lit(render(length, View.QUORUM, status(quorate = ok))) > 0)
            }
        }
        assertArrayEquals(MatrixText.render(13, listOf(listOf("L0.5"))), render(13, View.LOAD, status()))
        assertArrayEquals(MatrixText.render(13, listOf(listOf("U✓"))), render(13, View.UPDATES, status(updates = 0)))
        assertArrayEquals(MatrixText.render(13, listOf(listOf("HD✗"))), render(13, View.HEALTH, status(health = false)))
    }

    @Test
    fun everyUptimeAndClockRenders() {
        for (length in listOf(13, 25)) {
            for (seconds in listOf(0L, 3_599L, 7_200L, 86_400L, 42 * 86_400L, 999 * 86_400L)) {
                assertTrue(lit(render(length, View.UPTIME, status(uptime = seconds))) > 0)
            }
            for (is24 in listOf(true, false)) {
                for (hour in 0..23) {
                    for (minute in 0..59) {
                        assertTrue(lit(render(length, View.CLOCK, null, hour = hour, minute = minute, is24 = is24)) > 0)
                    }
                }
            }
        }
    }

    @Test
    fun brightnessFollowsLoad() {
        val byLoad = defaults.copy(brightnessByLoad = true)
        fun brightest(p: Int) = render(13, View.CPU, status(cpu = p), byLoad).max()
        assertTrue(brightest(10) < brightest(60))
        assertTrue(brightest(60) < brightest(90))
        assertEquals(MatrixText.BRIGHTNESS_DIGIT, brightest(90))
    }

    @Test
    fun overallBrightnessDimsEverything() {
        fun brightest(percent: Int, s: CachedStatus = status()) =
            Screens.compose(13, s, defaults.copy(brightness = percent), 0, now, 12, 34, true).max()
        assertEquals(MatrixText.BRIGHTNESS_DIGIT, brightest(100))
        assertEquals(128, brightest(50))
        assertTrue(brightest(10) in 1..30)
        // the DOWN cross is dimmed too, but never disappears
        assertEquals(26, brightest(10, status(up = false)))
        assertEquals(lit(Screens.down(13, blinkOn = true)), lit(Screens.compose(13, status(up = false), defaults.copy(brightness = 10), 0, now, 12, 34, true)))
    }

    @Test
    fun alertsOverrideOrInterleave() {
        val hot = status(cpu = 95, backup = false)
        val only = defaults.copy(alertMode = AlertMode.ONLY)
        assertEquals(listOf(View.CPU, View.BACKUP), Screens.alerts(hot, only))
        assertEquals(listOf(View.CPU, View.BACKUP), Screens.playlist(hot, only))
        val priority = defaults.copy(alertMode = AlertMode.PRIORITY)
        assertEquals(
            listOf(View.UPTIME, View.CPU, View.GUESTS, View.BACKUP, View.CLOCK, View.CPU),
            Screens.playlist(hot, priority),
        )
        assertEquals(Screens.views(hot, defaults.views), Screens.playlist(hot, defaults))
        assertEquals(emptyList<View>(), Screens.alerts(status(), only))
        assertEquals(listOf(View.DISK), Screens.alerts(status(disk = 70), only.copy(diskAlert = 70)))
    }

    @Test
    fun nightModeBlanksUnlessDiskFails() {
        val night = defaults.copy(nightEnabled = true, nightFrom = 23, nightTo = 7)
        assertTrue(Screens.isNight(night, 23))
        assertTrue(Screens.isNight(night, 3))
        assertFalse(Screens.isNight(night, 7))
        assertFalse(Screens.isNight(night, 12))
        assertTrue(Screens.isNight(night.copy(nightFrom = 1, nightTo = 5), 2))
        assertFalse(Screens.isNight(night.copy(nightEnabled = false), 3))

        fun compose(s: CachedStatus?, hour: Int, tick: Int = 0) = Screens.compose(13, s, night, tick, now, hour, 0, true)
        assertEquals(0, lit(compose(status(), 3)))
        assertArrayEquals(MatrixText.render(13, listOf(listOf("HD✗"))), compose(status(health = false), 3))
        assertArrayEquals(Screens.down(13, blinkOn = true), compose(status(up = false), 3))
        assertTrue(lit(compose(status(), 12)) > 0)
    }

    @Test
    fun clockOnlyReplacesTheRotationButNotDown() {
        val clockOnly = defaults.copy(clockOnly = true, intervalSeconds = 3)
        for (tick in listOf(0, 6, 12, 18)) {
            assertArrayEquals(render(13, View.CLOCK, status()), Screens.compose(13, status(), clockOnly, tick, now, 12, 34, true))
        }
        assertArrayEquals(Screens.down(13, blinkOn = true), Screens.compose(13, status(up = false), clockOnly, 0, now, 12, 34, true))
        val night = clockOnly.copy(nightEnabled = true, nightFrom = 22, nightTo = 6)
        assertEquals(0, lit(Screens.compose(13, status(), night, 0, now, 23, 0, true)))
    }

    @Test
    fun shortConnectionDropsKeepTheLastGoodStatus() {
        val good = status(cpu = 30)
        val lost = status(up = false, reason = DownReason.NO_NETWORK)
        // 1st failure with threshold 2: keep showing UP (with the old values)
        val first = Reliability.apply(good, lost, connectionFailed = true, threshold = 2)
        assertTrue(first.up)
        assertEquals(30, first.cpuPercent)
        assertEquals(1, first.failures)
        // 2nd failure in a row: DOWN
        val second = Reliability.apply(first, lost, connectionFailed = true, threshold = 2)
        assertFalse(second.up)
        assertEquals(DownReason.NO_NETWORK, second.downReason)
        // back up: counter reset
        assertEquals(0, Reliability.apply(second, good, connectionFailed = false, threshold = 2).failures)
        // threshold 1 = DOWN at once; a real answer (node offline) is DOWN at once too
        assertFalse(Reliability.apply(good, lost, connectionFailed = true, threshold = 1).up)
        assertFalse(Reliability.apply(good, status(up = false), connectionFailed = false, threshold = 5).up)
        // never checked before: nothing to keep
        assertFalse(Reliability.apply(null, lost, connectionFailed = true, threshold = 3).up)
    }

    @Test
    fun downReasonPicksTheSign() {
        fun compose(reason: DownReason, tick: Int) =
            Screens.compose(13, status(up = false, reason = reason), defaults, tick, now, 12, 0, true)
        assertTrue(lit(compose(DownReason.SERVER, 0)) > 0)
        assertEquals(0, lit(compose(DownReason.SERVER, 1)))
        // no network / VPN off do not blink and look different from the cross
        assertArrayEquals(compose(DownReason.NO_NETWORK, 0), compose(DownReason.NO_NETWORK, 1))
        assertNotEquals(compose(DownReason.SERVER, 0).toList(), compose(DownReason.NO_NETWORK, 0).toList())
        assertArrayEquals(MatrixText.render(13, listOf(listOf("VPN"))), compose(DownReason.VPN_DOWN, 1))
    }

    @Test
    fun rotationFollowsTheInterval() {
        val settings = defaults.copy(intervalSeconds = 3)
        fun at(tick: Int) = Screens.compose(13, status(), settings, tick, now, 12, 34, true)
        assertArrayEquals(render(13, View.UPTIME, status()), at(0))
        assertArrayEquals(render(13, View.UPTIME, status()), at(5))
        assertArrayEquals(render(13, View.GUESTS, status()), at(6))
        assertArrayEquals(render(13, View.CLOCK, status()), at(12))
        assertArrayEquals(render(13, View.UPTIME, status()), at(18))
    }

    @Test
    fun onlyNeededDataIsFetched() {
        assertEquals(setOf(Fetch.RESOURCES), Screens.fetches(defaults))
        assertEquals(emptySet<Fetch>(), Screens.fetches(defaults.copy(views = setOf(View.CLOCK, View.CPU))))
        assertEquals(
            setOf(Fetch.RRD),
            Screens.fetches(defaults.copy(views = setOf(View.CPU), designs = mapOf(View.CPU to Design.HISTORY))),
        )
        assertEquals(
            setOf(Fetch.RESOURCES, Fetch.TASKS, Fetch.HEALTH, Fetch.QUORUM),
            Screens.fetches(defaults.copy(views = setOf(View.CLOCK), alertMode = AlertMode.ONLY)),
        )
    }

    @Test
    fun uptimeKeepsCountingFromTheLastCheck() {
        val fresh = render(13, View.UPTIME, status(uptime = 86_400 - 60))
        val later = Screens.view(13, View.UPTIME, status(uptime = 86_400 - 60), defaults, now + 120_000, 0, 0, true)
        assertArrayEquals(MatrixText.render(13, listOf(listOf("23h"))), fresh)
        assertArrayEquals(MatrixText.render(13, listOf(listOf("1d"))), later)
    }

    @Test
    fun vpnIsExpectedForTailscaleHosts() {
        assertTrue(Connectivity.expectsVpn("https://100.79.207.128:8006"))
        assertTrue(Connectivity.expectsVpn("https://proxmox-01.tail1a2b.ts.net:8006"))
        assertFalse(Connectivity.expectsVpn("https://192.168.1.10:8006"))
        assertFalse(Connectivity.expectsVpn("https://100.200.1.1:8006"))
        assertFalse(Connectivity.expectsVpn("https://proxmox.example.com"))
    }
}
