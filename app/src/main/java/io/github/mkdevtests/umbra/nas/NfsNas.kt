package io.github.mkdevtests.umbra.nas

import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import java.io.FileNotFoundException

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
    fun open(relative: String): RemoteFile {
        val path = "/" + relative.replace('\\', '/').trimStart('/')
        val file = Nfs3File(nfs, path)
        if (!file.exists()) throw FileNotFoundException("NFS : $path introuvable dans $export")
        return NfsFile(file)
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
