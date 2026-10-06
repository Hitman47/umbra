package io.github.mkdevtests.umbra.nas

import java.io.Closeable
import java.io.IOException

/**
 * Read access to one NAS, whatever the protocol. Paths start with the share
 * ([NasSource.shares]) and use "\" ("Films\Dune (2021)\Dune.mkv"). Nothing
 * here writes, renames or deletes: Nyxara never changes a NAS.
 *
 * Blocking API: call from a background thread.
 */
interface NasClient : Closeable {
    val source: NasSource

    /** The entries of the folder at [path]. */
    fun list(path: String): List<NasEntry>

    /** Opens the file at [path] for reading; the caller closes it. */
    fun open(path: String): RemoteFile

    /** A URL the player can read [path] at by itself (WebDAV), or null to go through the app's local server. */
    fun directUrl(path: String): String? = null

    /** The address in use: [NasSource.host], or its fallback when the NAS is reached that way. */
    val currentHost: String get() = source.host

    /** The shares the NAS offers, or null when it won't say (the user types them). Throws if it can't be reached. */
    fun availableShares(): List<String>?

    /** The device changed networks: choose the address again at the next connection. */
    fun onNetworkChanged() {}

    /** Port the NAS answers on, to tell home from away. */
    val probePort: Int get() = 445

    /**
     * The home address answers: the device is at home. Always true for a NAS
     * without a Tailscale address. Blocking (half a second at most).
     */
    fun atHome(): Boolean {
        if (source.fallbackHost.isBlank()) return true
        val host = source.host.trim()
        return runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host.substringBefore(':'), host.substringAfter(':', "").toIntOrNull() ?: probePort), HOME_PROBE_MS) }
        }.isSuccess
    }

    /**
     * The NAS answers now, at its home address or its fallback: for the
     * state shown at the top of the screen. Blocking (a few seconds at most).
     */
    fun answers(): Boolean = listOf(source.host, source.fallbackHost).map { it.trim() }.filter { it.isNotEmpty() }.any { host ->
        reachable(host.substringBefore(':'), host.substringAfter(':', "").toIntOrNull() ?: probePort)
    }

    companion object {
        fun of(source: NasSource): NasClient = when (source.protocol) {
            Protocol.Smb -> SmbNas(source)
            Protocol.Nfs -> NfsNas(source)
            Protocol.WebDav -> WebDavNas(source)
        }
    }
}

/**
 * The first of [hosts] that answers on [port]: the local address at home,
 * the Tailscale one elsewhere. The first when none answers (its error is the one to show).
 */
fun firstReachable(hosts: List<String>, port: Int, timeoutMs: Int = 1_500): String {
    if (hosts.size <= 1) return hosts.firstOrNull().orEmpty()
    // Every address tried at once: away from home, the local one no longer costs its whole timeout first.
    val answered = java.util.concurrent.LinkedBlockingQueue<String>()
    hosts.forEach { host ->
        kotlin.concurrent.thread(name = "probe", isDaemon = true) {
            val ok = runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(host.substringBefore(':'), host.substringAfter(':', "").toIntOrNull() ?: port), timeoutMs) }
            }.isSuccess
            answered.put(if (ok) host else "")
        }
    }
    // The home address wins when it answers, even a little after the other: a LAN beats the tunnel.
    var other: String? = null
    var until = System.currentTimeMillis() + timeoutMs + 200
    var pending = hosts.size
    while (pending > 0) {
        val host = answered.poll(maxOf(1, until - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
        pending--
        if (host.isEmpty()) continue
        if (host == hosts.first()) return host
        if (other == null) {
            other = host
            until = minOf(until, System.currentTimeMillis() + PREFER_HOME_MS)
        }
    }
    return other ?: hosts.first()
}

private const val HOME_PROBE_MS = 600

/** Something listens at [host]:[port]. */
fun reachable(host: String, port: Int, timeoutMs: Int = 1_500): Boolean = runCatching {
    java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), timeoutMs) }
}.isSuccess

/** How long the home address may answer after the Tailscale one and still be chosen. */
private const val PREFER_HOME_MS = 150L

/** The NAS refuses this folder or file (rights): skipped by a scan, unlike a NAS that doesn't answer. */
class RefusedException(message: String) : IOException(message)

/** An address of the tailnet (100.64.0.0/10, or a MagicDNS name): the NAS is reached through Tailscale. */
fun isTailnet(host: String): Boolean {
    val name = host.substringBefore(':').trim().lowercase()
    val octets = name.split('.').mapNotNull { it.toIntOrNull() }
    return (octets.size == 4 && octets[0] == 100 && octets[1] in 64..127) || name.endsWith(".ts.net")
}
