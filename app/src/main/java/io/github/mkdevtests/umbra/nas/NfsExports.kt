package io.github.mkdevtests.umbra.nas

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * The folders an NFS server exports ("showmount -e"): asks the portmapper
 * where the mount service listens, then the mount service for its exports.
 * Two ONC RPC calls over TCP, without authentication. Reads only.
 */
object NfsExports {
    private const val PORTMAP = 100000
    private const val MOUNT = 100005
    private const val MOUNT_V3 = 3
    private const val GETPORT = 3
    private const val EXPORT = 5
    private const val TCP = 6

    fun list(host: String, portmapPort: Int = 111, timeoutMs: Int = 8_000): List<String> {
        val mountPort = call(host, portmapPort, timeoutMs, PORTMAP, 2, GETPORT, intArrayOf(MOUNT, MOUNT_V3, TCP, 0)).int
        if (mountPort == 0) throw IOException("NFS : le service de montage ne répond pas sur $host")
        val reply = call(host, mountPort, timeoutMs, MOUNT, MOUNT_V3, EXPORT, IntArray(0))
        val exports = mutableListOf<String>()
        while (reply.int != 0) { // exportnode: dirpath, groups, next
            exports += reply.string()
            while (reply.int != 0) reply.string() // the groups allowed: not needed
        }
        return exports
    }

    /** One call; the reply's results, past the RPC header. */
    private fun call(host: String, port: Int, timeoutMs: Int, program: Int, version: Int, procedure: Int, args: IntArray): ByteBuffer {
        val xid = (System.nanoTime() and 0x7fffffff).toInt()
        val body = ByteBuffer.allocate(40 + args.size * 4)
            .putInt(xid).putInt(0).putInt(2).putInt(program).putInt(version).putInt(procedure)
            .putInt(0).putInt(0) // credentials: AUTH_NONE
            .putInt(0).putInt(0) // verifier: AUTH_NONE
        args.forEach { body.putInt(it) }
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val out = socket.getOutputStream()
            out.write(ByteBuffer.allocate(4).putInt(0x80000000.toInt() or body.position()).array())
            out.write(body.array(), 0, body.position())
            out.flush()
            val input = DataInputStream(socket.getInputStream())
            val reply = ByteArrayOutputStream()
            do {
                val mark = input.readInt()
                val fragment = ByteArray(mark and 0x7fffffff)
                input.readFully(fragment)
                reply.write(fragment)
            } while ((mark and 0x80000000.toInt()) == 0)
            val buffer = ByteBuffer.wrap(reply.toByteArray())
            if (buffer.int != xid || buffer.int != 1) throw IOException("NFS : réponse inattendue")
            if (buffer.int != 0) throw IOException("NFS : appel refusé par $host")
            buffer.int // verifier flavor
            val verifier = buffer.int
            buffer.position(buffer.position() + pad(verifier))
            if (buffer.int != 0) throw IOException("NFS : service indisponible sur $host")
            return buffer
        }
    }

    private fun ByteBuffer.string(): String {
        val length = int
        val bytes = ByteArray(length)
        get(bytes)
        position(position() + pad(length) - length)
        return String(bytes, Charsets.UTF_8)
    }

    private fun pad(length: Int) = (length + 3) / 4 * 4
}
