package io.github.mkdevtests.umbra.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekingTest {
    @Test
    fun shortSwipesAreSecondsLongOnesMinutes() {
        assertTrue(swipeSeconds(0.1f) in 10.0..20.0)
        assertTrue(swipeSeconds(0.5f) in 150.0..200.0)
        assertEquals(600.0, swipeSeconds(1f), 0.1)
        assertEquals(-swipeSeconds(0.3f), swipeSeconds(-0.3f), 0.001)
    }

    @Test
    fun holdingGoesFurther() {
        assertEquals(listOf(10, 30, 60), listOf(500L, 3_000L, 8_000L).map(::holdStep))
        assertEquals("1 min 30", durationLabel(-90))
    }
}
