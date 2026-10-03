package io.github.mkdevtests.umbra.nas

import com.emc.ecs.nfsclient.nfs.NfsType
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import java.io.FileNotFoundException
import java.io.IOException

/**
 * NFSv3 access to a NAS: each share is an export ("/media/sdb1/Vidéos/Films").
 * It only reads: no write, rename or delete (ReadOnlyTest keeps it that way).
 *
 * The export must accept unprivileged ports ("insecure" in /etc/exports): an
 * Android app can't open a port below 1024.
 *
 * Blocking API: call from a background thread.
 */
class NfsNas(override val source: NasSource) : NasClient {

    /** One mount per export, made on first use. */
    private val mounts = HashMap<String, Nfs3>()

    @Volatile private var address: String? = null

    override val currentHost: String get() = address ?: source.host

    override val probePort get() = NFS_PORT

    /** Mounted at the address that answers (local, else Tailscale); forgotten after a failure, to choose again. */
    private fun mount(export: String): Nfs3 = synchronized(mounts) {
        mounts.getOrPut(export) {
            val host = address ?: firstReachable(source.hosts(), NFS_PORT).also { address = it }
            explained(export) { Nfs3(host, export, CredentialUnix(UID, GID, null), RETRIES) }
        }
    }

    private fun split(path: String): Pair<String, String> {
        val export = source.shares.firstOrNull { path == it || path.startsWith("$it\\") } ?: path.substringBefore('\\')
        return export to "/" + path.removePrefix(export).trim('\\').replace('\\', '/')
    }

    override fun list(path: String): List<NasEntry> {
        if (path.isEmpty()) return source.shares.map { NasEntry(it, it, isDirectory = true, size = 0) }
        val (export, inside) = split(path)
        val nfs = mount(export)
        return explained(export) {
            val folder = Nfs3File(nfs, inside)
            val entries = mutableListOf<NasEntry>()
            var cookie = 0L
            var verifier = 0L
            do {
                val page = folder.readdirplus(cookie, verifier, DIR_COUNT, MAX_COUNT)
                page.entries.forEach { entry ->
                    val name = entry.fileName
                    if (name == "." || name == ".." || name.startsWith('.')) return@forEach
                    val attributes = entry.attributes?.takeIf { it.isLoaded }
                    val isDirectory: Boolean
                    val size: Long
                    val modified: Long
                    if (attributes != null) {
                        isDirectory = attributes.type == NfsType.NFS_DIR
                        size = attributes.size
                        modified = attributes.mtime?.timeInMillis ?: 0
                    } else {
                        val child = Nfs3File(nfs, inside.trimEnd('/') + "/" + name)
                        isDirectory = child.isDirectory
                        size = if (isDirectory) 0 else child.lengthEx()
                        modified = 0
                    }
                    entries += NasEntry(name, "$path\\$name", isDirectory, if (isDirectory) 0 else size, modified)
                }
                cookie = page.cookie
                verifier = page.cookieverf
            } while (!page.isEof && page.entries.isNotEmpty())
            entries
        }
    }

    override fun open(path: String): RemoteFile {
        val (export, inside) = split(path)
        val nfs = mount(export)
        return explained(export) {
            val file = Nfs3File(nfs, inside)
            if (!file.exists()) throw FileNotFoundException("NFS : $inside introuvable dans $export")
            NfsFile(file)
        }
    }

    /** Asks the NAS for its exports, like "showmount -e". */
    override fun availableShares(): List<String>? = NfsExports.list(source.host.trim()).sorted()

    override fun close() = synchronized(mounts) { mounts.clear() }

    override fun onNetworkChanged() {
        synchronized(mounts) { mounts.clear() }
        address = null
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

    /** The NAS's refusals in words: the client library only gives the protocol's status number. */
    private fun <T> explained(export: String, block: () -> T): T = try {
        block()
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: Exception) {
        // The NAS may have moved (Wi-Fi to mobile, Tailscale): mount again, at the address that answers.
        synchronized(mounts) { mounts.clear() }
        address = null
        val status = Regex("""(?:state|status)\D{0,3}(\d+)""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        throw when (status) {
            13 -> RefusedException(
                "NFS : accès refusé à $export (code 13). Dans /etc/exports, ajoute « insecure » à cet export, puis exportfs -ra.",
            )
            2 -> FileNotFoundException("NFS : $export n'est pas exporté par ${source.host} (code 2).")
            else -> IOException("NFS : ${e.message ?: e}", e)
        }
    }

    private companion object {
        /** root: the export maps it to its anonymous user unless told otherwise (root_squash). */
        const val UID = 0
        const val GID = 0
        const val RETRIES = 3
        const val NFS_PORT = 2049
        const val DIR_COUNT = 8 * 1024
        const val MAX_COUNT = 64 * 1024
    }
}
