package io.github.mkdevtests.umbra.nas

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * A NAS file opened for reading. It wraps smbj's handle so that nothing in
 * the app can reach a write, rename or delete: Nyxara never changes the NAS.
 */
class NasFile internal constructor(private val file: File) : RemoteFile {
    /** Asked once: each query is a round trip to the NAS. */
    override val size: Long by lazy { file.fileInformation.standardInformation.endOfFile }

    override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
        file.read(buffer, fileOffset, bufferOffset, length)

    override fun close() = file.close()
}

/** A file or folder on the NAS. [path] starts with the share name and uses "\" ("Films\Dune (2021)"). */
data class NasEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long, val modified: Long = 0)

/**
 * Authenticated SMB access to the NAS, with the configured shares mounted on
 * demand. The path root ("") lists the shares themselves. Reconnects when the
 * NAS dropped the connection (sleep, Wi-Fi change, Tailscale reconnect).
 *
 * Each share gets its own connection: over a single one, the ZimaOS server
 * answers a folder of one share with the same-named folder of another
 * ("Films\Drame" listed "Séries\Drame"), which merged shares in the library.
 *
 * Read-only by design: files open with GENERIC_READ only, and the class has
 * no write, rename or delete operation (ReadOnlyTest keeps it that way).
 *
 * Blocking API: call from a background thread.
 */
class SmbNas(override val source: NasSource) : NasClient {

    /** One share over its own TCP connection and session. */
    private class ShareLink(val client: SMBClient, val share: DiskShare)

    private val links = HashMap<String, ShareLink>()

    override fun list(path: String): List<NasEntry> {
        if (path.isEmpty()) return source.shares.map { NasEntry(it, it, isDirectory = true, size = 0) }
        val (shareName, inner) = split(path)
        return withShare(shareName) { share ->
            share.list(inner)
                .filter { it.fileName != "." && it.fileName != ".." }
                .filterNot { it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L }
                .map {
                    NasEntry(
                        name = it.fileName,
                        path = "$path\\${it.fileName}",
                        isDirectory = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                        size = it.endOfFile,
                        modified = it.lastWriteTime.toEpochMillis(),
                    )
                }
        }
    }

    /**
     * File shares the NAS offers to this user, like Infuse or a file manager
     * would list them. Throws on connection or login errors; returns null when
     * the NAS refuses to enumerate its shares (the user then types them).
     */
    override fun availableShares(): List<String>? = newClient().use { client ->
        val session = login(client)
        val shares = try {
            ServerService(SMBTransportFactories.SRVSVC.getTransport(session)).shares1
        } catch (e: Exception) {
            Log.w(TAG, "share enumeration failed", e)
            return null
        } catch (e: LinkageError) {
            // dcerpc is built against an older smbj: fail soft if an API moved.
            Log.w(TAG, "share enumeration unavailable", e)
            return null
        }
        shares
            // Disk shares only (not printers or IPC$), without hidden admin shares ("C$").
            .filter { it.type and 0xFFFF == 0 && !it.netName.endsWith('$') }
            .map { it.netName }
            .distinct()
    }

    /** Opens [path] read-only; the caller closes the returned file. */
    override fun open(path: String): NasFile {
        val (shareName, inner) = split(path)
        return withShare(shareName) { share ->
            NasFile(share.openFile(
                inner,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                // Only an existing file: never creates one.
                SMB2CreateDisposition.FILE_OPEN,
                null,
            ))
        }
    }

    @Synchronized
    override fun close() {
        links.values.forEach { runCatching { it.client.close() } }
        links.clear()
    }

    private fun split(path: String) = path.substringBefore('\\') to path.substringAfter('\\', "")

    /** Runs [block] on the live share; if its cached connection turns out dead, reconnects once. */
    private fun <T> withShare(name: String, block: (DiskShare) -> T): T {
        val cached = synchronized(this) { links[name]?.share?.takeIf { it.isConnected } }
        if (cached != null) {
            try {
                return block(cached)
            } catch (e: TransportException) {
                invalidate(name)
            } catch (e: SMBRuntimeException) {
                if (e is SMBApiException) throw e // a real answer from the NAS, e.g. file not found
                invalidate(name)
            }
        }
        return try {
            block(connect(name))
        } catch (e: Exception) {
            // A connection closed by the NAS as it opened (waking up, too many at once): once more, a moment later.
            if (e.isRefusedByNas() || generateSequence<Throwable>(e) { it.cause }.none { it is TransportException || it is java.io.EOFException }) throw e
            Log.w(TAG, "connection to $name closed by the NAS, retrying", e)
            invalidate(name)
            Thread.sleep(RETRY_DELAY_MS)
            block(connect(name))
        }
    }

    @Synchronized
    private fun connect(name: String): DiskShare {
        links[name]?.share?.takeIf { it.isConnected }?.let { return it }
        invalidate(name)
        val client = newClient()
        try {
            val share = login(client).connectShare(name) as? DiskShare
                ?: throw IOException("« $name » n'est pas un partage de fichiers")
            links[name] = ShareLink(client, share)
            return share
        } catch (e: Throwable) {
            runCatching { client.close() }
            throw e
        }
    }

    /** SMBClient shares one connection per host: a client per share keeps the shares apart. */
    private fun newClient() = SMBClient(
        SmbConfig.builder()
            // Android's crypto lacks MD4, which NTLM needs: use smbj's BouncyCastle provider.
            .withSecurityProvider(SmbSecurity.provider())
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            // Up to 4 MiB per READ (the NAS may allow less): a 2 MiB block in one round trip through Tailscale.
            .withReadBufferSize(4 shl 20)
            .build(),
    )

    /** The address chosen last, and when: asked again after a minute (the phone may have left home). */
    @Volatile private var chosen: Pair<String, Long>? = null

    override val currentHost: String get() = chosen?.first ?: source.host

    private fun address(): String {
        chosen?.takeIf { System.currentTimeMillis() - it.second < ADDRESS_TTL }?.let { return it.first }
        return firstReachable(source.hosts(), SMBClient.DEFAULT_PORT).also { chosen = it to System.currentTimeMillis() }
    }

    /** Logs in at the address that answers; if it fails, at the other one (local, Tailscale). */
    private fun login(client: SMBClient): Session {
        val first = address()
        return try {
            login(client, first)
        } catch (e: Exception) {
            val other = source.hosts().firstOrNull { it != first } ?: throw e
            login(client, other).also { chosen = other to System.currentTimeMillis() }
        }
    }

    private fun login(client: SMBClient, address: String): Session {
        val host = address.substringBefore(':').trim()
        val port = address.substringAfter(':', "").toIntOrNull() ?: SMBClient.DEFAULT_PORT
        val auth = if (source.username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(source.username.trim(), source.password.toCharArray(), source.domain.ifBlank { null })
        }
        return client.connect(host, port).authenticate(auth)
    }

    @Synchronized
    private fun invalidate(name: String) {
        links.remove(name)?.let { runCatching { it.client.close() } }
    }

    /** The network changed (Wi-Fi left, Tailscale up): the next connection chooses its address again. */
    override fun onNetworkChanged() {
        chosen = null
    }

    private companion object {
        const val ADDRESS_TTL = 60_000L
        const val RETRY_DELAY_MS = 1_500L
        const val TAG = "SmbNas"
    }
}

/** Short French message for errors shown to the user. */
/** The NAS answered with an error (rights, missing folder), as opposed to not answering. */
fun Throwable.isRefusedByNas(): Boolean = generateSequence(this) { it.cause }.any { it is com.hierynomus.mssmb2.SMBApiException || it is RefusedException }

fun Throwable.toUserMessage(): String {
    val text = generateSequence(this) { it.cause }.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }
    return when {
        "STATUS_LOGON_FAILURE" in text -> "Identifiant ou mot de passe incorrect."
        "STATUS_ACCESS_DENIED" in text -> "Accès refusé par le NAS."
        "STATUS_BAD_NETWORK_NAME" in text -> "Partage introuvable sur le NAS."
        "STATUS_OBJECT_NAME_NOT_FOUND" in text || "STATUS_OBJECT_PATH_NOT_FOUND" in text -> "Dossier ou fichier introuvable."
        generateSequence(this) { it.cause }.any { it is UnknownHostException } -> "Adresse du NAS inconnue."
        generateSequence(this) { it.cause }.any { it is ConnectException || it is SocketTimeoutException } ->
            "NAS injoignable : vérifie l'adresse et le réseau (Wi-Fi ou Tailscale)."
        // The NAS closed the connection: asleep, restarting, or too many connections at once.
        generateSequence(this) { it.cause }.any { it is java.io.EOFException || it is TransportException } ->
            "Connexion fermée par le NAS (veille, redémarrage, trop de connexions, ou NAS limité au vieux SMB1). Réessaie dans un instant."
        else -> message ?: javaClass.simpleName
    }
}
