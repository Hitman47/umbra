package io.github.mkdevtests.umbra.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import io.github.mkdevtests.umbra.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A GitHub release newer than the installed app. */
data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** Handed to Android, which asks the user to confirm (except for silent updates). */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val release: Release, val message: String) : UpdateState
}

/**
 * Updates the app from the GitHub releases of the (public) repository: no
 * token, no store. The APK is installed through [PackageInstaller]; once Nyxara
 * has installed itself, Android lets it update without asking again.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val apkFile = File(context.cacheDir, "update.apk")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Outcome of the last check, for the settings screen ("À jour"); null before any. */
    private val _lastCheck = MutableStateFlow<String?>(null)
    val lastCheck: StateFlow<String?> = _lastCheck.asStateFlow()

    /** Looks for a newer release, quietly: no network, no release or a GitHub error just means no update. */
    fun check() {
        if (!BuildConfig.UPDATES) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        scope.launch {
            apkFile.delete() // left over by an earlier update
            _lastCheck.value = "Recherche…"
            runCatching { latestRelease() }
                .onSuccess { release ->
                    if (release != null && isNewer(release.version, BuildConfig.VERSION_NAME)) {
                        _state.value = UpdateState.Available(release)
                        _lastCheck.value = "Nyxara ${release.version} disponible"
                    } else {
                        _lastCheck.value = "À jour"
                    }
                }
                .onFailure {
                    Log.w(TAG, "update check failed", it)
                    _lastCheck.value = "Impossible de joindre GitHub"
                }
        }
    }

    fun install(release: Release) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        scope.launch {
            try {
                download(release)
                _state.value = UpdateState.Installing(release)
                commit()
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                _state.value = UpdateState.Failed(release, "Mise à jour impossible : ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Called by [InstallReceiver] when Android reports the outcome (success kills and restarts the app). */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release ?: return
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.None
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Available(release) // the user said no
            else -> UpdateState.Failed(release, "Installation refusée par Android${message?.let { " : $it" } ?: ""}")
        }
    }

    private suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPOSITORY/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null // nothing published yet
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            val release = json.decodeFromString(GitHubRelease.serializer(), response.body!!.string())
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@withContext null
            Release(release.tagName.removePrefix("v"), release.body.orEmpty().trim(), apk.url, apk.size)
        }
    }

    private suspend fun download(release: Release) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(release, 0f)
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (release.size > 0 && apkFile.length() != release.size) throw IOException("téléchargement incomplet")
    }

    private fun commit() {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apkFile.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Silent once Nyxara installed itself: Android only asks the first time.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("nyxara.apk", 0, apkFile.length()).use { output ->
                apkFile.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallReceiver::class.java),
                // Mutable: Android adds the status to the intent.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    companion object {
        private const val TAG = "Updater"
        const val REPOSITORY = "Hitman47/umbra"

        /** "0.10.0" is newer than "0.9.2"; suffixes ("-debug") are ignored. */
        fun isNewer(candidate: String, installed: String): Boolean {
            fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (diff != 0) return diff > 0
            }
            return false
        }
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val url: String,
)
