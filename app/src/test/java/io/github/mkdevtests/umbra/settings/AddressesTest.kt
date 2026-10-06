package io.github.mkdevtests.umbra.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressesTest {
    @Test
    fun addressesAreTakenApartAndBackWithoutTypingTheScheme() {
        assertEquals(Address(false, "192.168.1.10", "9696"), parseAddress("http://192.168.1.10:9696/"))
        assertEquals(Address(true, "nas.local", "", "/qbit"), parseAddress("https://nas.local/qbit"))
        assertEquals(Address(false, "nas", "8080"), parseAddress("nas:8080"))
        assertEquals("http://192.168.1.10:9696", Address(host = "192.168.1.10").url(9696))
        assertEquals("https://nas:8443", Address(true, "nas", "8443").url(8080))
        assertEquals("", Address().url(9696))
        assertEquals("192.168.1.10", hostOf("smb://192.168.1.10:445/share"))
        assertEquals("100.64.1.2", hostOf("100.64.1.2"))
    }
}
