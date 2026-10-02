package io.github.mkdevtests.umbra.nas

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
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** A file or folder inside the share. [path] uses "\" and is relative to the share root. */
data class NasEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long)

/**
 * One authenticated connection to an SMB share, reopened on demand when the
 * NAS dropped it (sleep, Wi-Fi change, Tailscale reconnect).
 *
 * Blocking API: call from a background thread.
 */
class SmbShare(val source: SmbSource) : Closeable {

    private val client = SMBClient(
        SmbConfig.builder()
            // Android's crypto lacks MD4, which NTLM needs: use smbj's BouncyCastle provider.
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build(),
    )
    private var connection: Connection? = null
    private var share: DiskShare? = null

    fun list(path: String): List<NasEntry> = withShare { share ->
        share.list(path)
            .filter { it.fileName != "." && it.fileName != ".." }
            .filterNot { it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L }
            .map {
                NasEntry(
                    name = it.fileName,
                    path = if (path.isEmpty()) it.fileName else "$path\\${it.fileName}",
                    isDirectory = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    size = it.endOfFile,
                )
            }
    }

    /** Opens [path] read-only; the caller closes the returned file. */
    fun open(path: String): File = withShare { share ->
        share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    override fun close() {
        invalidate()
        client.close()
    }

    /** Runs [block] on the live share; if a cached connection turns out dead, reconnects once. */
    private fun <T> withShare(block: (DiskShare) -> T): T {
        val cached = synchronized(this) { share?.takeIf { it.isConnected } }
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
        return block(connect())
    }

    @Synchronized
    private fun connect(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        val host = source.host.substringBefore(':').trim()
        val port = source.host.substringAfter(':', "").toIntOrNull() ?: SMBClient.DEFAULT_PORT
        val auth = if (source.username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(source.username.trim(), source.password.toCharArray(), source.domain.ifBlank { null })
        }
        val conn = client.connect(host, port).also { connection = it }
        val session = conn.authenticate(auth)
        val disk = session.connectShare(source.share.trim().trim('/', '\\')) as? DiskShare
            ?: throw IOException("« ${source.share} » n'est pas un partage de fichiers")
        share = disk
        return disk
    }

    @Synchronized
    private fun invalidate() {
        runCatching { connection?.close(true) }
        connection = null
        share = null
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
