package io.github.mkdevtests.umbra.library

import android.util.Log
import io.github.mkdevtests.umbra.nas.NasEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File

@Serializable
private data class JournalEntry(val n: String, val p: String, val d: Boolean, val s: Long, val m: Long = 0)

@Serializable
private data class JournalLine(val p: String, val e: List<JournalEntry>)

/**
 * The folders an analysis has listed, written as it goes: an analysis cut
 * off (app closed, NAS lost) starts again from them instead of listing tens of
 * thousands of folders anew. Forgotten after [MAX_AGE_MS], and when an
 * analysis ends well ([finish]).
 */
class ScanJournal(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val saved: Map<String, List<NasEntry>> = load()
    private var writer: BufferedWriter? = null

    /** How many folders come from the journal: shown in the progress. */
    val resumed get() = saved.size

    /** [path]'s entries as listed by an analysis cut off, or null. */
    fun get(path: String): List<NasEntry>? = saved[path.lowercase()]

    @Synchronized
    fun put(path: String, entries: List<NasEntry>) {
        if (path.lowercase() in saved) return
        runCatching {
            val out = writer ?: file.bufferedWriter(Charsets.UTF_8, DEFAULT_BUFFER_SIZE).also { w ->
                // What the last analysis listed stays: written again first, the journal goes on from there.
                saved.forEach { (key, entries) -> w.write(line(key, entries)); w.newLine() }
                writer = w
            }
            out.write(line(path, entries))
            out.newLine()
            out.flush()
        }.onFailure { Log.w(TAG, "journal", it) }
    }

    /** The analysis ended well: nothing to resume. */
    @Synchronized
    fun finish() {
        runCatching { writer?.close() }
        writer = null
        file.delete()
    }

    private fun line(path: String, entries: List<NasEntry>) =
        json.encodeToString(JournalLine.serializer(), JournalLine(path, entries.map { JournalEntry(it.name, it.path, it.isDirectory, it.size, it.modified) }))

    private fun load(): Map<String, List<NasEntry>> {
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) {
            file.delete()
            return emptyMap()
        }
        return runCatching {
            file.useLines { lines ->
                lines.mapNotNull { text -> runCatching { json.decodeFromString(JournalLine.serializer(), text) }.getOrNull() }
                    .associate { line -> line.p.lowercase() to line.e.map { NasEntry(it.n, it.p, it.d, it.s, it.m) } }
            }
        }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) Log.i(TAG, "resuming: ${it.size} folders already listed") }
    }

    private companion object {
        const val TAG = "ScanJournal"
        const val MAX_AGE_MS = 12 * 3600_000L
    }
}
