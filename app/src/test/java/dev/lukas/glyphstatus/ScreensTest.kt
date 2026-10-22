package dev.lukas.glyphstatus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensTest {

    private val now = 1_000_000_000L

    private val all = Screens.View.entries.toSet()

    private fun status(
        uptime: Long? = 3_628_800,
        running: Int? = 7,
        total: Int? = 9,
        up: Boolean = true,
        cpu: Int? = 35,
        mem: Int? = 62,
        disk: Int? = 93,
    ) = CachedStatus(up, uptime, running, total, cpu, mem, disk, checkedAtMillis = now)

    private fun render(length: Int, view: Screens.View, status: CachedStatus?, hour: Int = 12, minute: Int = 34, is24: Boolean = true) =
        Screens.view(length, view, status, now, hour, minute, is24)

    private fun lit(frame: IntArray) = frame.count { it > 0 }

    @Test
    fun rotationContainsOnlyEnabledViewsWithData() {
        assertEquals(Screens.View.entries.toList(), Screens.views(status(), all))
        assertEquals(listOf(Screens.View.CLOCK), Screens.views(null, all))
        assertEquals(
            listOf(Screens.View.UPTIME, Screens.View.CPU, Screens.View.MEM, Screens.View.DISK, Screens.View.CLOCK),
            Screens.views(status(running = null, total = null), all),
        )
        assertEquals(
            listOf(Screens.View.GUESTS, Screens.View.DISK),
            Screens.views(status(), setOf(Screens.View.DISK, Screens.View.GUESTS)),
        )
    }

    @Test
    fun fallsBackToClockWhenNothingSelectedHasData() {
        assertEquals(listOf(Screens.View.CLOCK), Screens.views(status(cpu = null), setOf(Screens.View.CPU)))
        assertEquals(listOf(Screens.View.CLOCK), Screens.views(status(), emptySet()))
        assertEquals(listOf(Screens.View.CLOCK), Screens.views(status(up = false), setOf(Screens.View.UPTIME)))
    }

    @Test
    fun everyPercentageRenders() {
        for (length in listOf(13, 25)) {
            for (p in 0..100) {
                for (view in listOf(Screens.View.CPU, Screens.View.MEM, Screens.View.DISK)) {
                    val s = status(cpu = p, mem = p, disk = p)
                    assertTrue("$view $p on $length", lit(render(length, view, s)) > 0)
                }
            }
        }
        assertArrayEquals(MatrixText.render(13, listOf(listOf("C35"))), render(13, Screens.View.CPU, status()))
        assertArrayEquals(MatrixText.render(13, listOf(listOf("C99"))), render(13, Screens.View.CPU, status(cpu = 100)))
        assertArrayEquals(MatrixText.render(25, listOf(listOf("D100"))), render(25, Screens.View.DISK, status(disk = 100)))
    }

    @Test
    fun everyUptimeAndGuestCountRenders() {
        for (length in listOf(13, 25)) {
            for (seconds in listOf(0L, 3_599L, 7_200L, 86_400L, 42 * 86_400L, 999 * 86_400L)) {
                assertTrue("uptime $seconds on $length", lit(render(length, Screens.View.UPTIME, status(uptime = seconds))) > 0)
            }
            for (total in 0..120) {
                for (running in listOf(0, total / 2, total)) {
                    assertTrue("$running/$total on $length", lit(render(length, Screens.View.GUESTS, status(running = running, total = total))) > 0)
                }
            }
        }
    }

    @Test
    fun everyClockTimeRenders() {
        for (length in listOf(13, 25)) {
            for (is24 in listOf(true, false)) {
                for (hour in 0..23) {
                    for (minute in 0..59) {
                        assertTrue("$hour:$minute on $length", lit(render(length, Screens.View.CLOCK, null, hour, minute, is24)) > 0)
                    }
                }
            }
        }
    }

    @Test
    fun uptimeKeepsCountingFromTheLastCheck() {
        val fresh = render(13, Screens.View.UPTIME, status(uptime = 86_400 - 60))
        val later = Screens.view(13, Screens.View.UPTIME, status(uptime = 86_400 - 60), now + 120_000, 0, 0, true)
        assertArrayEquals(MatrixText.render(13, listOf(listOf("23h"))), fresh)
        assertArrayEquals(MatrixText.render(13, listOf(listOf("1d"))), later)
    }

    @Test
    fun textFallsBackWhenItDoesNotFit() {
        assertNull(MatrixText.render(13, listOf(listOf("12/15"))))
        assertArrayEquals(MatrixText.render(13, listOf(listOf("12"))), render(13, Screens.View.GUESTS, status(running = 12, total = 15)))
        assertNotNull(MatrixText.render(13, listOf(listOf("7/9"))))
        assertNotNull(MatrixText.render(13, listOf(listOf("42d"))))
    }

    @Test
    fun downBlinks() {
        assertTrue(lit(Screens.down(13, blinkOn = true)) > 0)
        assertEquals(0, lit(Screens.down(13, blinkOn = false)))
    }
}
