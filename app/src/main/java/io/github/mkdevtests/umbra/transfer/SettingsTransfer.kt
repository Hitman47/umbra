package io.github.mkdevtests.umbra.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.history.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

private const val TAG = "SettingsTransfer"
private const val SERVICE_TYPE = "_nyxara._tcp."
private const val PATH = "/nyxara/settings"

/** The port tried first, so that an address typed by hand needs no port. */
const val TRANSFER_PORT = 47474

private const val MAX_ATTEMPTS = 5
private const val MAX_SIZE = 8 shl 20

private val json = Json { ignoreUnknownKeys = true }

sealed interface ReceiveState {
    data object Idle : ReceiveState
    /** Shown on screen: the code to type on the sending device, and where it finds this one. */
    data class Waiting(val code: String, val addresses: List<String>, val port: Int) : ReceiveState
    data class Done(val message: String) : ReceiveState
    data class Failed(val message: String) : ReceiveState
}

/**
 * This device waiting for another's settings, on the home network only, for
 * as long as its screen is open: a code on screen, one transfer accepted,
 * then it stops (as it does after [MAX_ATTEMPTS] wrong codes).
 */
class TransferReceiver(private val app: NyxaraApp) {
    private val _state = MutableStateFlow<ReceiveState>(ReceiveState.Idle)
    val state: StateFlow<ReceiveState> = _state.asStateFlow()

    private var server: Server? = null
    private var registration: NsdManager.RegistrationListener? = null
    private val nsd get() = app.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Synchronized
    fun start() {
        stop()
        val code = Sealed.newCode()
        val started = listOf(TRANSFER_PORT, 0).firstNotNullOfOrNull { port ->
            runCatching { Server(code, port).apply { start(SOCKET_TIMEOUT, true) } }.getOrNull()
        }
        if (started == null) {
            _state.value = ReceiveState.Failed("Impossible d'attendre sur le réseau")
            return
        }
        server = started
        _state.value = ReceiveState.Waiting(code, localAddresses(), started.listeningPort)
        announce(started.listeningPort)
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        if (_state.value is ReceiveState.Waiting) _state.value = ReceiveState.Idle
    }

    /** Lets the sending device find this one by itself (no address to type). */
    private fun announce(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "Nyxara · ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Log.w(TAG, "announce failed: $error").let { }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onSuccess { registration = listener }
    }

    private inner class Server(private val code: String, port: Int) : NanoHTTPD(null, port) {
        private var attempts = 0

        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.POST || session.uri != PATH) return text(Response.Status.NOT_FOUND, "?")
            val length = session.headers["content-length"]?.toIntOrNull()
            if (length == null || length <= 0 || length > MAX_SIZE) return text(Response.Status.BAD_REQUEST, "Taille refusée")
            val sealed = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = session.inputStream.read(sealed, read, length - read)
                if (n < 0) return text(Response.Status.BAD_REQUEST, "Envoi interrompu")
                read += n
            }
            val plain = Sealed.open(code, sealed)
            if (plain == null) {
                attempts++
                if (attempts >= MAX_ATTEMPTS) finish(ReceiveState.Failed("Trop de codes faux : réception arrêtée"))
                return text(Response.Status.FORBIDDEN, "Code incorrect")
            }
            return runCatching {
                val backup = json.decodeFromString(Backup.serializer(), plain.decodeToString())
                runBlocking { app.backups.apply(backup) }
            }.fold(
                onSuccess = { message ->
                    finish(ReceiveState.Done(message))
                    text(Response.Status.OK, message)
                },
                onFailure = { e ->
                    Log.w(TAG, "transfer unreadable", e)
                    text(Response.Status.INTERNAL_ERROR, "Réglages illisibles : ${e.message}")
                },
            )
        }

        /** Stops once the answer is on its way. */
        private fun finish(state: ReceiveState) {
            _state.value = state
            Thread { Thread.sleep(1000); this@TransferReceiver.stop() }.start()
        }

        private fun text(status: Response.Status, message: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", message)
    }

    private companion object {
        const val SOCKET_TIMEOUT = 15_000
    }
}

/** This device's addresses on the local network ("192.168.1.42"). */
fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
}.getOrDefault(emptyList())

/** A device found on the network, waiting for settings. */
data class Peer(val name: String, val host: String, val port: Int)

/** Finds the devices waiting for settings and sends them this one's. */
class TransferSender(private val app: NyxaraApp) {
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val nsd get() = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discovery: NsdManager.DiscoveryListener? = null
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    @Synchronized
    fun discover() {
        stop()
        _peers.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = queue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.name == info.serviceName }
            }
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.onSuccess { discovery = listener }
    }

    @Synchronized
    fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        toResolve.clear()
    }

    // Android resolves one service at a time.
    @Synchronized
    private fun queue(info: NsdServiceInfo) {
        toResolve.addLast(info)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        val next = toResolve.removeFirstOrNull()
        resolving = next != null
        next ?: return
        @Suppress("DEPRECATION") // its replacement needs API 34
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = info.host?.hostAddress
                if (host != null) {
                    val peer = Peer(info.serviceName, host, info.port)
                    _peers.value = _peers.value.filterNot { it.name == peer.name } + peer
                }
                resolveNext()
            }
        })
    }

    /** Sends this device's settings to [host] ("192.168.1.42", or with ":port"); the answer to show. */
    suspend fun send(host: String, code: String): String = withContext(Dispatchers.IO) {
        val address = host.trim().removePrefix("http://").trimEnd('/')
        val target = if (':' in address) address else "$address:$TRANSFER_PORT"
        val plain = json.encodeToString(Backup.serializer(), app.backups.transferSnapshot()).toByteArray()
        val body = Sealed.seal(Sealed.normalized(code), plain).toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder().url("http://$target$PATH").post(body).build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> "Envoyé. ${text}"
                    response.code == 403 -> "Code incorrect"
                    else -> "Refusé : ${text.ifBlank { "HTTP ${response.code}" }}"
                }
            }
        } catch (e: IOException) {
            "Appareil injoignable ($target) : ouvre « Recevoir » dessus, sur le même réseau"
        }
    }
}
