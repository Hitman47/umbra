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
            appendLine("Dernières lectures :")
            app.measures.measures.value.takeLast(10).forEach { appendLine("- ${it.line()}") }
            appendLine()
            appendLine("Journal :")
            append(redacted(recentLog()))
        }
    }

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
