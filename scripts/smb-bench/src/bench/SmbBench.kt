package bench

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.SecurityProvider
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import io.github.mkdevtests.umbra.nas.HybridSecurityProvider
import io.github.mkdevtests.umbra.nas.LocalStreamServer
import io.github.mkdevtests.umbra.nas.NasClient
import io.github.mkdevtests.umbra.nas.NasRouter
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.nas.ReadPlan
import io.github.mkdevtests.umbra.nas.SmbSecurity
import java.net.HttpURLConnection
import java.net.URL
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Where Nyxara's SMB reading loses its rate, measured step by step with the
 * app's own code: the SMB client's crypto (BouncyCastle as in the app, or the
 * system's), the size of each read, reads in parallel, and the app's local
 * server between the NAS and the player.
 *
 * Arguments: host share path-in-share user password [megabytes]
 *   e.g. 192.168.1.131 Films "Dune (2021)\Dune.mkv" mathieu secret 256
 */
/** Where the arguments can be written instead, away from the shell's quoting (Windows paths, spaces, passwords). */
private const val SETTINGS = "bench.txt"

/** bench.txt: "host=…", "share=…", "path=…", "user=…", "password=…", "megabytes=…", one per line; backslashes kept as they are. */
private fun settingsFile(): Array<String>? {
    val file = java.io.File(SETTINGS).takeIf { it.exists() } ?: return null
    val values = file.readLines(Charsets.UTF_8)
        .map { it.trim().removePrefix("\uFEFF") }
        .filter { '=' in it && !it.startsWith("#") }
        .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
    return listOf("host", "share", "path", "user", "password", "megabytes").map { values[it].orEmpty() }.toTypedArray()
}

/**
 * The output: UTF-8, but on Windows, where Gradle hands it to a console in
 * another code page ("Ã©" for "é"), without accents.
 */
private fun console(): java.io.PrintStream {
    val out = java.io.FileOutputStream(java.io.FileDescriptor.out)
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return java.io.PrintStream(out, true, "UTF-8")
    return object : java.io.PrintStream(out, true, "UTF-8") {
        override fun print(text: String?) = super.print(text?.let(::plain))
    }
}

/** "Réglage · 4 Mo" → "Reglage - 4 Mo". */
private fun plain(text: String) = java.text.Normalizer.normalize(text.replace("·", "-").replace("’", "'"), java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .replace(Regex("[^\\x00-\\x7F]"), "?")

fun main(cli: Array<String>) {
    System.setOut(console())
    val args = cli.takeIf { it.size >= 5 } ?: settingsFile()
    if (args == null || args.take(5).any { it.isEmpty() }) {
        println("Écris les réglages dans scripts/smb-bench/$SETTINGS (une ligne chacun), puis relance : ./gradlew -p scripts/smb-bench run")
        println("  host=192.168.1.131")
        println("  share=Films")
        println("  path=Dune (2021)\\Dune.mkv")
        println("  user=mathieu")
        println("  password=secret")
        println("  megabytes=256")
        return
    }
    val (host, share, path, user, password) = args
    val megabytes = args.getOrNull(5)?.toLongOrNull() ?: 256
    val bytes = megabytes shl 20
    println("Nyxara · mesure SMB · $host · $share\\$path · $megabytes Mo par essai\n")

    fun connect(provider: SecurityProvider, readBuffer: Int): Pair<SMBClient, DiskShare> {
        val client = SMBClient(
            SmbConfig.builder().withSecurityProvider(provider).withReadBufferSize(readBuffer)
                .withTimeout(15, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build(),
        )
        val session = client.connect(host).authenticate(AuthenticationContext(user, password.toCharArray(), null))
        return client to session.connectShare(share) as DiskShare
    }

    // What the NAS and the client agreed on.
    connect(BCSecurityProvider(), 4 shl 20).let { (client, disk) ->
        val session = disk.treeConnect.session
        val protocol = session.connection.negotiatedProtocol
        println("Dialecte SMB : ${protocol.dialect} · lecture max : ${protocol.maxReadSize / 1024} Kio")
        println("Signature : ${if (session.sessionContext.isSigningRequired) "OUI (chaque paquet est signé)" else "non"} · chiffrement : ${if (session.sessionContext.isEncryptData) "OUI" else "non"}\n")
        client.close()
    }

    /** Reads [bytes] with [parallel] handles, [block] bytes per read; Mbit/s. */
    fun raw(provider: SecurityProvider, block: Int, parallel: Int): Double {
        val (client, disk) = connect(provider, maxOf(block, 1 shl 20))
        try {
            val files = (0 until parallel).map {
                disk.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            }
            val size = files.first().fileInformation.standardInformation.endOfFile
            val total = minOf(bytes, size)
            val next = AtomicLong(0)
            val pool = Executors.newFixedThreadPool(parallel)
            val started = System.nanoTime()
            files.forEach { file ->
                pool.submit {
                    val buffer = ByteArray(block)
                    while (true) {
                        val at = next.getAndAdd(block.toLong())
                        if (at >= total) break
                        var done = 0
                        val want = minOf(block.toLong(), total - at).toInt()
                        while (done < want) {
                            val n = file.read(buffer, at + done, 0, want - done)
                            if (n <= 0) break
                            done += n
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.MINUTES)
            val seconds = (System.nanoTime() - started) / 1e9
            files.forEach { it.close() }
            return total * 8 / 1e6 / seconds
        } finally {
            client.close()
        }
    }

    /** Through the app's own SmbNas and local HTTP server, as the player reads; Mbit/s. */
    fun app(provider: () -> SecurityProvider, plan: ReadPlan): Double {
        SmbSecurity.provider = provider
        val nas = NasRouter(listOf(NasClient.of(NasSource(host, listOf(share), user, password))))
        val server = LocalStreamServer { nas }
        server.forcedPlan = plan
        try {
            val url = server.urlFor("$share\\$path")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=0-${bytes - 1}")
            val started = System.nanoTime()
            var read = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    read += n
                }
            }
            return read * 8 / 1e6 / ((System.nanoTime() - started) / 1e9)
        } finally {
            server.stop()
            nas.close()
        }
    }

    val bc = { BCSecurityProvider() }
    val hybrid = { HybridSecurityProvider() }
    val tests = listOf<Pair<String, () -> Double>>(
        "1. SMB brut · crypto de l'app (BouncyCastle) · 1 Mo, 1 à la fois" to { raw(bc(), 1 shl 20, 1) },
        "2. SMB brut · crypto du système · 1 Mo, 1 à la fois" to { raw(hybrid(), 1 shl 20, 1) },
        "3. SMB brut · crypto du système · 4 Mo, 1 à la fois" to { raw(hybrid(), 4 shl 20, 1) },
        "4. SMB brut · crypto du système · 2 Mo, 4 en parallèle" to { raw(hybrid(), 2 shl 20, 4) },
        "5. Chemin de l'app (serveur local) · comme aujourd'hui à la maison" to { app(bc, ReadPlan.LOCAL) },
        "6. Chemin de l'app · crypto du système · réglage Tailscale (4 en parallèle)" to { app(hybrid, ReadPlan.REMOTE) },
    )
    tests.forEach { (name, test) ->
        val result = runCatching { test() }
        println(
            result.fold(
                { "%-72s %7.0f Mbit/s".format(name, it) },
                { "%-72s ÉCHEC : %s".format(name, it.message ?: it.toString()) },
            ),
        )
    }
    println("\nÀ m'envoyer tel quel. 1 lent et 2 rapide : la crypto. 3/4 bien plus rapides que 2 : la taille ou le parallélisme. 5 bien plus lent que 2 : le serveur local.")
}
