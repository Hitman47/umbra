package io.github.mkdevtests.umbra.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SealedTest {
    @Test
    fun opensWithTheSameCodeOnly() {
        val plain = "réglages".toByteArray()
        val sealed = Sealed.seal("12345678", plain)
        assertArrayEquals(plain, Sealed.open("1234 5678", sealed))
        assertNull(Sealed.open("12345679", sealed))
    }

    @Test
    fun alteredIsRefused() {
        val sealed = Sealed.seal("00000000", ByteArray(64) { it.toByte() })
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertNull(Sealed.open("00000000", sealed))
        assertNull(Sealed.open("00000000", ByteArray(10)))
    }

    @Test
    fun codesHaveTheirDigits() {
        val code = Sealed.newCode()
        assertEquals(Sealed.CODE_DIGITS, code.length)
        assertEquals("1234 5678", Sealed.spaced("12345678"))
    }
}
