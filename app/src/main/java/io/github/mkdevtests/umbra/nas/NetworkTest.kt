package io.github.mkdevtests.umbra.nas

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** What a network test found: the way to the NAS, its round trip and the rate playback gets. */
data class NetworkReport(val route: String, val roundTripMs: Long, val mbps: Double) {
    /** "Tailscale · 38 ms · 142 Mbit/s". */
    val summary get() = "$route · $roundTripMs ms · ${String.format(Locale.FRANCE, "%.0f", mbps)} Mbit/s"

    /** What it allows, and a hint when the tunnel seems relayed. */
    val verdict get() = buildList {
        add(
            when {
                mbps >= REMUX_4K -> "Assez pour tout, 4K remux comprise."
                mbps >= HEAVY_1080 -> "Assez pour le 1080p, même lourd ; la 4K remux peut saccader."
                mbps >= LIGHT_1080 -> "Assez pour le 1080p léger (WEB) ; préfère les versions légères."
                else -> "Juste pour le 720p : préfère les versions légères."
            },
        )
        if (route == "Tailscale" && roundTripMs > RELAYED_MS) {
            add("Aller-retour élevé : Tailscale passe peut-être par un relais (DERP). Une connexion directe (UPnP / NAT-PMP sur la box, ou le port UDP 41641 ouvert vers le NAS) va bien plus vite.")
        }
    }.joinToString(" ")

    private companion object {
        const val REMUX_4K = 100.0
        const val HEAVY_1080 = 35.0
        const val LIGHT_1080 = 12.0
        const val RELAYED_MS = 80L
    }
}

/**
 * Measures the way to the NAS as playback uses it: the round trip of a small
 * read (best of 3), then [bytes] read through [url], the local server's URL
 * of [path] (same blocks, same parallel reads as a film). Blocking.
 */
fun measureNetwork(nas: NasRouter, path: String, url: String, bytes: Long = 48L shl 20): NetworkReport {
    val roundTrip = nas.open(path).use { file ->
        val probe = ByteArray(4096)
        (0 until 3).minOf { attempt ->
            val started = System.nanoTime()
            file.read(probe, attempt * 1_000_000L, 0, probe.size)
            (System.nanoTime() - started) / 1_000_000
        }
    }
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.setRequestProperty("Range", "bytes=0-${bytes - 1}")
    val started = System.nanoTime()
    var read = 0L
    connection.inputStream.use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val count = stream.read(buffer)
            if (count <= 0) break
            read += count
        }
    }
    val seconds = (System.nanoTime() - started) / 1e9
    val host = nas.hostOf(path).orEmpty()
    return NetworkReport(if (isTailnet(host)) "Tailscale" else "Local", roundTrip, read * 8 / 1e6 / seconds)
}
