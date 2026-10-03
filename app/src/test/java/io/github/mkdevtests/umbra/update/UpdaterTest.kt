package io.github.mkdevtests.umbra.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updater.isNewer("0.2.0", "0.1.0"))
        assertTrue(Updater.isNewer("0.10.0", "0.9.2"))
        assertTrue(Updater.isNewer("1.0", "0.99.99"))
        assertTrue(Updater.isNewer("0.2.1", "0.2"))
        assertFalse(Updater.isNewer("0.2.0", "0.2.0"))
        assertFalse(Updater.isNewer("0.1.9", "0.2.0"))
        assertFalse(Updater.isNewer("0.2.0", "0.2.0-debug"))
    }
}
