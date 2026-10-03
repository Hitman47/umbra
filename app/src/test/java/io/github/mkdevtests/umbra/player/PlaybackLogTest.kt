package io.github.mkdevtests.umbra.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackLogTest {
    private fun measure(at: Long, route: String, openMs: Long, seeks: List<Long>, stalls: Int, watchedS: Long) =
        PlaybackMeasure(at, "Dune", "Zima", network = "Wi-Fi", route = route, openMs = openMs, seeksMs = seeks, stalls = stalls, watchedS = watchedS)

    @Test
    fun mediansPerSetupLatestFirst() {
        val measures = listOf(
            measure(1, "Local", 1000, listOf(500, 700), 0, 1800),
            measure(2, "Local", 3000, listOf(900), 1, 1800),
            measure(3, "Local", 2000, emptyList(), 0, 0),
            measure(4, "Tailscale", 4000, listOf(2000), 2, 1800),
        )
        val summaries = summarize(measures)
        assertEquals(listOf("Zima · Wi-Fi · Tailscale · SMB", "Zima · Wi-Fi · Local · SMB"), summaries.map { it.setup })
        val local = summaries[1]
        assertEquals(3, local.count)
        assertEquals(2000L, local.openMs)
        assertEquals(700L, local.seekMs)
        assertEquals(1.0, local.stallsPerHour!!, 0.001) // 1 stall in 1 h of playback
    }

    @Test
    fun tailscaleAddresses() {
        assertEquals("Tailscale", routeOf("100.101.2.3"))
        assertEquals("Tailscale", routeOf("zima.tail1234.ts.net:445"))
        assertEquals("Local", routeOf("192.168.1.20"))
        assertEquals("Local", routeOf("100.200.1.1"))
    }
}
