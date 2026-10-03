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

/** A file or folder on the NAS. [path] starts with the share name and uses "\" ("Films\Dune (2021)"). */
data class NasEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long)

/**
 * One authenticated SMB session to the NAS, with the configured shares
 * mounted on demand. The path root ("") lists the shares themselves.
 * Reconnects when the NAS dropped the session (sleep, Wi-Fi change,
 * Tailscale reconnect).
 *
 * Blocking API: call from a background thread.
 */
class SmbNas(val source: SmbSource) : Closeable {

    private val client = SMBClient(
        SmbConfig.builder()
            // Android's crypto lacks MD4, which NTLM needs: use smbj's BouncyCastle provider.
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build(),
    )
    private var session: Session? = null
    private val shares = HashMap<String, DiskShare>()

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
                    )
                }
        }
    }

    /**
     * File shares the NAS offers to this user, like Infuse or a file manager
     * would list them. Throws on connection or login errors; returns null when
     * the NAS refuses to enumerate its shares (the user then types them).
     */
    fun availableShares(): List<String>? {
        val session = synchronized(this) { session?.takeIf { it.connection.isConnected } ?: openSession() }
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
        return shares
            // Disk shares only (not printers or IPC$), without hidden admin shares ("C$").
            .filter { it.type and 0xFFFF == 0 && !it.netName.endsWith('$') }
            .map { it.netName }
            .distinct()
    }

    /** Opens [path] read-only; the caller closes the returned file. */
    fun open(path: String): File {
        val (shareName, inner) = split(path)
        return withShare(shareName) { share ->
            share.openFile(
                inner,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        }
    }

    override fun close() {
        invalidate()
        client.close()
    }

    private fun split(path: String) = path.substringBefore('\\') to path.substringAfter('\\', "")

    /** Runs [block] on the live share; if a cached session turns out dead, reconnects once. */
    private fun <T> withShare(name: String, block: (DiskShare) -> T): T {
        val cached = synchronized(this) { shares[name]?.takeIf { it.isConnected } }
        if (cached != null) {
            try {
                return block(cached)
            } catch (e: TransportException) {
                invalidate()
            } catch (e: SMBRuntimeException) {
                if (e is SMBApiException) throw e // a real answer from the NAS, e.g. file not found
                invalidate()
            }
        }
        return block(connect(name))
    }

    @Synchronized
    private fun connect(name: String): DiskShare {
        shares[name]?.takeIf { it.isConnected }?.let { return it }
        val session = session?.takeIf { it.connection.isConnected } ?: openSession()
        val share = session.connectShare(name) as? DiskShare
            ?: throw IOException("« $name » n'est pas un partage de fichiers")
        shares[name] = share
        return share
    }

    private fun openSession(): Session {
        val host = source.host.substringBefore(':').trim()
        val port = source.host.substringAfter(':', "").toIntOrNull() ?: SMBClient.DEFAULT_PORT
        val auth = if (source.username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(source.username.trim(), source.password.toCharArray(), source.domain.ifBlank { null })
        }
        return client.connect(host, port).authenticate(auth).also { session = it }
    }

    @Synchronized
    private fun invalidate() {
        runCatching { session?.connection?.close(true) }
        session = null
        shares.clear()
    }

    private companion object {
        const val TAG = "SmbNas"
    }
}

/** Short French message for errors shown to the user. */
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
