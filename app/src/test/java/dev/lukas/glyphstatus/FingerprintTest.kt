package dev.lukas.glyphstatus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintTest {

    private val colon = (0 until 32).joinToString(":") { "%02X".format(it) }

    @Test
    fun parsesColonAndPlainHex() {
        val expected = ByteArray(32) { it.toByte() }
        assertArrayEquals(expected, Fingerprint.parse(colon))
        assertArrayEquals(expected, Fingerprint.parse(colon.replace(":", "").lowercase()))
        assertEquals(colon, Fingerprint.format(expected))
    }

    @Test
    fun rejectsWrongLengthOrCharacters() {
        assertNull(Fingerprint.parse("AB:CD"))
        assertNull(Fingerprint.parse(colon.replaceFirst('0', 'G')))
        assertNull(Fingerprint.parse(""))
    }
}
