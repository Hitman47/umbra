package io.github.mkdevtests.umbra.support

import android.content.res.Configuration
import android.os.Build
import android.os.Process
import io.github.mkdevtests.umbra.BuildConfig
import io.github.mkdevtests.umbra.NyxaraApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A problem report to send in a message: the app and the device, the NAS
 * states and checks, the last playbacks' measures and Nyxara's recent log.
 * No password, key or token: they are never logged, and anything looking like
 * one in the log is masked ([redacted]).
 */
object ProblemReport {
    private const val LOG_LINES = 400

    suspend fun build(app: NyxaraApp): String = withContext(Dispatchers.IO) {
        val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)
        val tv = (app.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        buildString {
            appendLine("Nyxara ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})${if (tv) " · TV" else ""}")
            appendLine("Écran : ${app.resources.displayMetrics.widthPixels}×${app.resources.displayMetrics.heightPixels} · ABI ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine()
            appendLine("NAS :")
            app.sources.load().forEach { appendLine("- ${it.label} · ${it.protocol.label} · ${it.hosts().joinToString(" / ")} · ${it.shares.size} dossier(s)") }
            app.nasMonitor.states.value.forEach { appendLine("- ${it.label} : ${if (it.online) "en ligne" else "injoignable"} depuis ${time.format(Date(it.since))}") }
            app.nasMonitor.history.value.takeLast(10).forEach { appendLine("  ${time.format(Date(it.at))} ${it.reason.label} : ${it.results.joinToString(", ")}") }
            appendLine()
            appendLine("Derniers arrêts de Nyxara :")
            append(exits(app, time))
            crashFile(app).takeIf { it.exists() }?.let { appendLine("Dernière erreur :"); appendLine(it.readText().take(6_000)) }
            appendLine()
            appendLine("Dernières lectures :")
            app.measures.measures.value.takeLast(10).forEach { appendLine("- ${it.line()}") }
            appendLine()
            appendLine("Journal :")
            append(redacted(recentLog()))
        }
    }

    private fun crashFile(app: android.content.Context) = java.io.File(app.filesDir, "last-crash.txt")

    /** A crash of the app's own code: written down (with what was happening) before Android closes it, for the next report. */
    fun keepCrashes(app: android.content.Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val at = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
                crashFile(app).writeText("$at, fil ${thread.name} :\n${redacted(error.stackTraceToString())}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Why Android ended Nyxara the last times (Android 11 and later): a crash of
     * the player's native code, the app frozen too long (ANR), memory taken
     * back… with the start of the trace of a freeze.
     */
    private fun exits(app: android.content.Context, time: SimpleDateFormat): String = buildString {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            appendLine("(Android 10 : non disponible)")
            return@buildString
        }
        val manager = app.getSystemService(android.app.ActivityManager::class.java) ?: return@buildString
        val exits = runCatching { manager.getHistoricalProcessExitReasons(app.packageName, 0, 6) }.getOrDefault(emptyList())
        if (exits.isEmpty()) appendLine("aucun")
        exits.forEach { exit ->
            appendLine("- ${time.format(Date(exit.timestamp))} : ${reason(exit.reason)}${exit.description?.let { " ($it)" } ?: ""} · mémoire ${exit.pss / 1024} Mo")
            // An ANR's trace is text (a native crash's is binary: its description says enough).
            if (exit.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    exit.traceInputStream?.bufferedReader()?.use { reader -> reader.lineSequence().take(TRACE_LINES).forEach { appendLine("    $it") } }
                }
            }
        }
    }

    private fun reason(code: Int): String = when (code) {
        android.app.ApplicationExitInfo.REASON_ANR -> "bloqué (ANR)"
        android.app.ApplicationExitInfo.REASON_CRASH -> "plantage (Java)"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage (lecteur natif)"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "mémoire insuffisante"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "ressources excessives"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "fermé par l'appli"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "arrêt demandé"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "tué par le système"
        else -> "autre ($code)"
    }

    private const val TRACE_LINES = 80

    /** This process's last log lines (an app reads its own without any permission). */
    private fun recentLog(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", LOG_LINES.toString(), "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
    }.getOrElse { "illisible : ${it.message}" }

    private val SECRET = Regex("""(?i)(api[_-]?key|apikey|token|password|passwd|pwd|secret|authorization)(["']?\s*[:=]\s*["']?)[^\s&"',;]+""")

    /** Whatever looks like a key or a password in [text], masked. */
    fun redacted(text: String): String = text.replace(SECRET) { it.groupValues[1] + it.groupValues[2] + "•••" }
}
