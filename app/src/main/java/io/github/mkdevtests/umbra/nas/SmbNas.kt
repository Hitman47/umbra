package io.github.mkdevtests.umbra.nas

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.security.bc.BCSecurityProvider
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
 * the app can reach a write, rename or delete: Umbra never changes the NAS.
 */
class NasFile internal constructor(private val file: File) : Closeable {
    val size: Long get() = file.fileInformation.standardInformation.endOfFile

    /** Reads up to [length] bytes at [fileOffset] into [buffer] from [bufferOffset]; -1 or 0 at the end. */
    fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int =
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
class SmbNas(val source: SmbSource) : Closeable {

    /** One share over its own TCP connection and session. */
    private class ShareLink(val client: SMBClient, val share: DiskShare)

    private val links = HashMap<String, ShareLink>()

    fun list(path: String): List<NasEntry> {
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
    fun availableShares(): List<String>? = newClient().use { client ->
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
    fun open(path: String): NasFile {
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
        return block(connect(name))
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
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build(),
    )

    private fun login(client: SMBClient): Session {
        val host = source.host.substringBefore(':').trim()
        val port = source.host.substringAfter(':', "").toIntOrNull() ?: SMBClient.DEFAULT_PORT
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

    private companion object {
        const val TAG = "SmbNas"
    }
}

/** Short French message for errors shown to the user. */
/** The NAS answered with an error (rights, missing folder), as opposed to not answering. */
fun Throwable.isRefusedByNas(): Boolean = generateSequence(this) { it.cause }.any { it is com.hierynomus.mssmb2.SMBApiException }

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
        else -> message ?: javaClass.simpleName
    }
}
