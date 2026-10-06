package io.github.mkdevtests.umbra.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PassthroughTest {
    @Test
    fun onlyWhatTheOutputTakesIsSentAsItIs() {
        assertEquals("", passthroughFor(emptySet()))
        // A sound bar taking AC3, E-AC3 with Atmos (JOC) and TrueHD.
        assertEquals("ac3,eac3,truehd", passthroughFor(setOf(2, 5, 18, 14)))
        assertEquals("ac3,eac3,dts,dts-hd", passthroughFor(setOf(5, 6, 7, 8)))
    }
}
