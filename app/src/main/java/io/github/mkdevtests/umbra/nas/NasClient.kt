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

    /** The shares the NAS offers, or null when it won't say (the user types them). Throws if it can't be reached. */
    fun availableShares(): List<String>?

    companion object {
        fun of(source: NasSource): NasClient = when (source.protocol) {
            Protocol.Smb -> SmbNas(source)
            Protocol.Nfs -> NfsNas(source)
            Protocol.WebDav -> WebDavNas(source)
        }
    }
}

/** The NAS refuses this folder or file (rights): skipped by a scan, unlike a NAS that doesn't answer. */
class RefusedException(message: String) : IOException(message)
