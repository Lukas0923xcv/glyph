package dev.lukas.glyphstatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixIconsTest {

    @Test
    fun crossHasMatrixSizeAndStaysInsideTheCircle() {
        for (length in listOf(13, 25)) {
            val icon = MatrixIcons.cross(length)
            assertEquals(length, icon.size)
            icon.forEachIndexed { y, row ->
                assertEquals(length, row.length)
                row.forEachIndexed { x, c ->
                    if (c == '#') assertTrue("($x,$y) outside $length", MatrixIcons.isVisible(length, x, y))
                }
            }
            assertTrue(icon.any { '#' in it })
        }
    }

    @Test
    fun frameUsesBrightnessForLitPixels() {
        val frame = MatrixIcons.toFrame(MatrixIcons.cross(13), 255)
        assertEquals(169, frame.size)
        assertEquals(255, frame[6 * 13 + 6])
        assertEquals(0, frame[0])
    }
}
