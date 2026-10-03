package io.github.mkdevtests.umbra.nas

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class ProtocolsTest {

    @Test
    fun nfsExportsFromPortmapperThenMountService() {
        val mountd = ServerSocket(0)
        val portmap = ServerSocket(0)
        // Portmapper: GETPORT answers the mount service's port.
        thread { portmap.accept().use { s -> val xid = readCall(s.getInputStream()); reply(s.getOutputStream(), xid) { putInt(mountd.localPort) } } }
        // Mount service: EXPORT answers two exports, the first allowed to two groups.
        thread {
            mountd.accept().use { s ->
                val xid = readCall(s.getInputStream())
                reply(s.getOutputStream(), xid) {
                    putInt(1); string("/media/sdb1/Vidéos/Films"); putInt(1); string("192.168.1.0/24"); putInt(1); string("*"); putInt(0)
                    putInt(1); string("/media/sdb1/Séries"); putInt(0)
                    putInt(0)
                }
            }
        }
        assertEquals(listOf("/media/sdb1/Vidéos/Films", "/media/sdb1/Séries"), NfsExports.list("127.0.0.1", portmap.localPort))
    }

    @Test
    fun nfsAndWebdavSharesGetShortRoots() {
        val nfs = NasSource("192.168.1.131", listOf("/media/sdb1/Vidéos/Films", "/media/sdb1/Vidéos/Séries"), id = "n", protocol = Protocol.Nfs)
        assertEquals(listOf("Films", "Séries"), nfs.shares.map(nfs::rootOf))
        // A second source with "Films": suffixed, and kept as is by the first.
        val smb = NasSource("192.168.1.131", listOf("Films"), id = "s").withRoots(listOf(nfs))
        assertEquals("Films (192.168.1.131)", smb.rootOf("Films"))
        assertEquals(emptyMap<String, String>(), nfs.withRoots(emptyList()).roots)
        val router = NasRouter(listOf(NfsNas(nfs), WebDavNas(NasSource("http://nas:5005/", listOf("Vidéos/Docs"), "u", "p w", id = "w", protocol = Protocol.WebDav))))
        assertEquals(listOf("Docs", "Films", "Séries"), router.list("").map { it.path })
        assertEquals("http://u:p%20w@nas:5005/Vid%C3%A9os/Docs/Plan%C3%A8te%20(2006)/Plan%C3%A8te.mkv", router.directUrl("Docs\\Planète (2006)\\Planète.mkv"))
        assertEquals(null, router.directUrl("Films\\Dune.mkv"))
    }

    @Test
    fun webdavListing() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/Films/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>/Films/Am%C3%A9lie%20(2001)/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>http://nas:5005/Films/Dune%20+%20Co.mkv</D:href><D:propstat><D:prop><D:resourcetype/>
                <D:getcontentlength>3221225472</D:getcontentlength><D:getlastmodified>Sat, 03 Oct 2026 10:00:00 GMT</D:getlastmodified></D:prop></D:propstat></D:response>
              <D:response><D:href>/Films/.DS_Store</D:href></D:response>
            </D:multistatus>"""
        assertEquals(
            listOf(DavEntry("Amélie (2001)", true, 0, 0), DavEntry("Dune + Co.mkv", false, 3221225472, 1791021600000)),
            parsePropfind(xml, "/Films/"),
        )
    }

    private fun readCall(input: java.io.InputStream): Int {
        val data = DataInputStream(input)
        val body = ByteArray(data.readInt() and 0x7fffffff)
        data.readFully(body)
        return ByteBuffer.wrap(body).int
    }

    private fun reply(out: java.io.OutputStream, xid: Int, results: ByteBuffer.() -> Unit) {
        val body = ByteBuffer.allocate(1024).putInt(xid).putInt(1).putInt(0).putInt(0).putInt(0).putInt(0)
        body.results()
        out.write(ByteBuffer.allocate(4).putInt(0x80000000.toInt() or body.position()).array())
        out.write(body.array(), 0, body.position())
        out.flush()
    }

    private fun ByteBuffer.string(text: String) {
        val bytes = text.toByteArray()
        putInt(bytes.size)
        put(bytes)
        repeat((4 - bytes.size % 4) % 4) { put(0) }
    }
}
