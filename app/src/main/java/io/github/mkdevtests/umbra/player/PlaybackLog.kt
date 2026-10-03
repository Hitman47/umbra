package io.github.mkdevtests.umbra.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One playback as measured: what the viewer waited for, and what the NAS delivered. */
@Serializable
data class PlaybackMeasure(
    val at: Long,
    val title: String,
    /** The NAS's name, or "Appareil" for a local file. */
    val source: String,
    val protocol: String = "SMB",
    /** "Wi-Fi", "Mobile", with " + VPN" when a VPN (Tailscale) is on. */
    val network: String,
    /** "Local" or "Tailscale": the address the NAS is reached at. */
    val route: String,
    val openMs: Long? = null,
    /** Part of [openMs] spent reading the file's header and index. */
    val loadedMs: Long? = null,
    val seeksMs: List<Long> = emptyList(),
    val stalls: Int = 0,
    val stalledMs: Long = 0,
    val watchedS: Long = 0,
    /** NAS throughput while reading, Mbit/s. */
    val readMbps: Double? = null,
    /** Mean time to open the file on the NAS. */
    val nasOpenMs: Long? = null,
    val requests: Int = 0,
    /** Times the file was opened on the NAS for those requests (the others reused a handle). */
    val opens: Int = 0,
    val megabytes: Long = 0,
    /** The file's mean bitrate, Mbit/s. */
    val fileMbps: Double? = null,
    val video: String? = null,
    val droppedFrames: Int? = null,
) {
    /** One line to paste: "03/10 21:14 · Dune · Zima · Wi-Fi · Local · SMB · ouverture 1,8 s · …". */
    fun line(): String = listOfNotNull(
        SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(at)),
        title,
        source,
        network,
        route,
        protocol,
        openMs?.let { "ouverture ${seconds(it)}" + (loadedMs?.let { loaded -> " (fichier lu en ${seconds(loaded)})" } ?: "") },
        seeksMs.takeIf { it.isNotEmpty() }?.let { "sauts ${it.size} (médiane ${seconds(median(it))}, max ${seconds(it.max())})" },
        "coupures $stalls" + if (stalls > 0) " (${seconds(stalledMs)})" else "",
        "vu ${watchedS / 60} min",
        readMbps?.let { "NAS ${"%.0f".format(Locale.FRANCE, it)} Mb/s" },
        fileMbps?.let { "fichier ${"%.0f".format(Locale.FRANCE, it)} Mb/s" },
        "requêtes $requests" + (nasOpenMs?.let { " (ouvertures NAS $opens × $it ms)" } ?: ""),
        video,
        droppedFrames?.takeIf { it > 0 }?.let { "images perdues $it" },
    ).joinToString(" · ")

    /** "Zima salon · Wi-Fi · Local · SMB": measures comparable with each other. */
    val setup get() = "$source · $network · $route · $protocol"
}

/** Summary of the measures of one setup: what to compare between protocols, places and networks. */
data class MeasureSummary(
    val setup: String,
    val count: Int,
    val openMs: Long?,
    val seekMs: Long?,
    val stallsPerHour: Double?,
    val readMbps: Double?,
) {
    fun line(): String = listOfNotNull(
        setup,
        "$count lecture${if (count > 1) "s" else ""}",
        openMs?.let { "ouverture ${seconds(it)}" },
        seekMs?.let { "saut ${seconds(it)}" },
        stallsPerHour?.let { "coupures ${"%.1f".format(Locale.FRANCE, it)}/h" },
        readMbps?.let { "NAS ${"%.0f".format(Locale.FRANCE, it)} Mb/s" },
    ).joinToString(" · ")
}

/** Medians per setup, the latest setups first. */
fun summarize(measures: List<PlaybackMeasure>): List<MeasureSummary> =
    measures.sortedByDescending { it.at }.groupBy { it.setup }.map { (setup, list) ->
        val watched = list.sumOf { it.watchedS }
        MeasureSummary(
            setup = setup,
            count = list.size,
            openMs = list.mapNotNull { it.openMs }.takeIf { it.isNotEmpty() }?.let(::median),
            seekMs = list.flatMap { it.seeksMs }.takeIf { it.isNotEmpty() }?.let(::median),
            stallsPerHour = watched.takeIf { it >= 60 }?.let { list.sumOf { m -> m.stalls } * 3600.0 / it },
            readMbps = list.mapNotNull { it.readMbps }.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] },
        )
    }

/** "Tailscale" for a NAS reached at its Tailscale address (100.64.0.0/10, *.ts.net), else "Local". */
fun routeOf(host: String): String {
    val name = host.substringBefore(':').trim().lowercase()
    val octets = name.split('.').mapNotNull { it.toIntOrNull() }
    val tailnet = octets.size == 4 && octets[0] == 100 && octets[1] in 64..127
    return if (tailnet || name.endsWith(".ts.net")) "Tailscale" else "Local"
}

fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

private fun seconds(ms: Long) = "%.1f s".format(Locale.FRANCE, ms / 1000.0)

/** The last playbacks measured, kept in a file of the app. */
class PlaybackLog(private val file: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _measures = MutableStateFlow<List<PlaybackMeasure>>(emptyList())
    val measures: StateFlow<List<PlaybackMeasure>> = _measures.asStateFlow()

    init {
        scope.launch {
            if (!file.exists()) return@launch
            runCatching { json.decodeFromString<List<PlaybackMeasure>>(file.readText()) }
                .onSuccess { saved -> _measures.update { saved + it } }
                .onFailure { Log.w(TAG, "measures unreadable", it) }
        }
    }

    fun add(measure: PlaybackMeasure) {
        _measures.update { (listOf(measure) + it).take(MAX) }
        save()
    }

    fun clear() {
        _measures.value = emptyList()
        save()
    }

    private fun save() {
        val list = _measures.value
        scope.launch { runCatching { file.writeText(json.encodeToString(list)) }.onFailure { Log.w(TAG, "measures not saved", it) } }
    }

    private companion object {
        const val TAG = "PlaybackLog"
        const val MAX = 200
    }
}
