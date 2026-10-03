package io.github.mkdevtests.umbra.perso

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
private data class PersoData(
    val progress: Map<String, PersoProgress> = emptyMap(),
    val folders: Map<String, PersoFolderState> = emptyMap(),
)

/** How the Perso tab is locked; see [PersoStore.lock]. */
data class PersoLock(val enabled: Boolean, val fingerprint: Boolean)

/**
 * The Perso tab's own data: where each video stopped and what each folder
 * played, apart from the library's history, out of Trakt and of the backups
 * (in the app's no-backup folder); and its lock (a PIN, a fingerprint).
 */
class PersoStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.noBackupFilesDir, "perso.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("perso_lock", Context.MODE_PRIVATE)
    private val data = MutableStateFlow(load())
    private var saving: Job? = null

    private val _progress = MutableStateFlow(data.value.progress)
    val progress: StateFlow<Map<String, PersoProgress>> = _progress.asStateFlow()

    fun folder(path: String): PersoFolderState = data.value.folders[path] ?: PersoFolderState()

    fun saveFolder(path: String, state: PersoFolderState) {
        data.update { it.copy(folders = it.folders + (path to state)) }
        saveSoon()
    }

    /** Called by the player while it plays [file]; ignored until the duration is known. */
    fun save(file: String, position: Double, duration: Double) {
        if (duration <= 0) return
        val entry = PersoProgress(position.coerceIn(0.0, duration), duration, System.currentTimeMillis())
        data.update { it.copy(progress = it.progress + (file to entry)) }
        _progress.value = data.value.progress
        saveSoon()
    }

    /** Where [file] starts again: where it stopped, unless it was finished. */
    fun resumeAt(file: String): Double = data.value.progress[file]?.takeIf { it.inProgress }?.position ?: 0.0

    /** [files] back to never played. */
    fun forget(files: Collection<String>) {
        data.update { it.copy(progress = it.progress - files.toSet()) }
        _progress.value = data.value.progress
        saveSoon()
    }

    @Synchronized
    private fun saveSoon() {
        saving?.cancel()
        saving = scope.launch(Dispatchers.IO) {
            delay(1_000)
            runCatching {
                val temp = File(file.path + ".tmp")
                temp.writeText(json.encodeToString(PersoData.serializer(), data.value))
                temp.renameTo(file)
            }.onFailure { Log.w(TAG, "save", it) }
        }
    }

    private fun load(): PersoData = runCatching {
        if (file.exists()) json.decodeFromString(PersoData.serializer(), file.readText()) else PersoData()
    }.getOrElse { PersoData() }

    // The lock: a PIN (salted hash), and the fingerprint as a shortcut to it.

    private val _lock = MutableStateFlow(readLock())
    val lock: StateFlow<PersoLock> = _lock.asStateFlow()

    private val _unlocked = MutableStateFlow(false)

    /** The tab may be shown: no lock, or unlocked since the app came to the front. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private fun readLock() = PersoLock(prefs.getString(PIN, null) != null, prefs.getBoolean(FINGERPRINT, false))

    fun setPin(pin: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        prefs.edit { putString(SALT, salt).putString(PIN, hash(salt, pin)) }
        _lock.value = readLock()
        _unlocked.value = true
    }

    fun removeLock() {
        prefs.edit { remove(PIN).remove(SALT).remove(FINGERPRINT) }
        _lock.value = readLock()
    }

    fun setFingerprint(enabled: Boolean) {
        prefs.edit { putBoolean(FINGERPRINT, enabled) }
        _lock.value = readLock()
    }

    /** Unlocks with [pin]; false when it is wrong. */
    fun unlock(pin: String): Boolean {
        val salt = prefs.getString(SALT, null) ?: return true
        val ok = hash(salt, pin) == prefs.getString(PIN, null)
        if (ok) _unlocked.value = true
        return ok
    }

    /** The fingerprint was recognized. */
    fun unlockByFingerprint() {
        _unlocked.value = true
    }

    /** The app left the screen: locked again. */
    fun relock() {
        _unlocked.value = false
    }

    val isOpen get() = !lock.value.enabled || _unlocked.value

    /** The order of the tab's videos, kept from one launch to the next. */
    var sort: String
        get() = prefs.getString(SORT, null) ?: "name"
        set(value) = prefs.edit { putString(SORT, value) }

    private fun hash(salt: String, pin: String) =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "PersoStore"
        const val PIN = "pin"
        const val SALT = "salt"
        const val FINGERPRINT = "fingerprint"
        const val SORT = "sort"
    }
}
