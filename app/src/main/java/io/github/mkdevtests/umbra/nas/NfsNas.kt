package io.github.mkdevtests.umbra.nas

import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Read access to an NFSv3 export, for the protocol test. Like [SmbNas], it
 * only reads: no write, rename or delete (ReadOnlyTest keeps it that way).
 *
 * The export must accept unprivileged ports ("insecure" on Linux): an
 * Android app can't open a port below 1024.
 *
 * Blocking API: call from a background thread.
 */
class NfsNas(val server: String, val export: String) {

    private val nfs: Nfs3 by lazy { Nfs3(server, export, CredentialUnix(0, 0, null), RETRIES) }

    /** [relative] to the export, with "\" or "/" between folders. */
    fun open(relative: String): RemoteFile = explained {
        val path = "/" + relative.replace('\\', '/').trimStart('/')
        val file = Nfs3File(nfs, path)
        if (!file.exists()) throw FileNotFoundException("NFS : $path introuvable dans $export")
        NfsFile(file)
    }

    /** The NAS's refusals in words: the client library only gives the protocol's status number. */
    private fun <T> explained(block: () -> T): T = try {
        block()
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: Exception) {
        val status = Regex("""state (\d+)""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        throw IOException(
            when (status) {
                13 -> "NFS : le NAS refuse le montage de $export (code 13, accès refusé). Dans l'export, ajoute l'option " +
                    "« insecure » et autorise l'adresse du téléphone (ou 192.168.1.0/24)."
                2 -> "NFS : $export n'est pas exporté par $server (code 2)."
                else -> "NFS : ${e.message ?: e}"
            },
            e,
        )
    }

    private class NfsFile(private val file: Nfs3File) : RemoteFile {
        override val size: Long by lazy { file.lengthEx() }

        override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
            val response = file.read(fileOffset, length, buffer, bufferOffset)
            return response.bytesRead.takeIf { it > 0 } ?: -1
        }

        /** NFSv3 has no open handles: nothing to close. */
        override fun close() {}
    }

    private companion object {
        const val RETRIES = 3
    }
}
