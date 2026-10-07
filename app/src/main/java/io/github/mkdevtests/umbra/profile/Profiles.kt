package io.github.mkdevtests.umbra.profile

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Someone who watches on this device: their own history ("Lecture en
 * cours", what is watched) and their own Trakt account. The [MAIN] profile is
 * the owner's (the data from before profiles): only it sees the Perso tab
 * and manages the profiles. A [child] profile only sees the titles rated for
 * [maxAge] at most, and nothing to search for or request.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** Index in [PROFILE_COLORS]. */
    val color: Int = 0,
    val child: Boolean = false,
    val maxAge: Int = 10,
    /** Salted hash of the PIN; null without lock. */
    val pinHash: String? = null,
    val salt: String? = null,
    /** The fingerprint opens it too (where the device has one). */
    val fingerprint: Boolean = false,
) {
    val isMain get() = id == MAIN
    val locked get() = pinHash != null

    fun checks(pin: String) = pinHash == null || salt != null && hashPin(salt, pin) == pinHash

    /** Where its history is kept; the owner's is the one from before profiles. */
    val historyDatabase get() = if (isMain) "history.db" else "history-$id.db"

    /** Its Trakt account's preferences and cache; the owner's are the ones from before profiles. */
    val traktName get() = if (isMain) "trakt" else "trakt-$id"

    companion object {
        const val MAIN = "main"
    }
}

/** Colours of the profiles' circles (ARGB). */
val PROFILE_COLORS = listOf(0xFF7C5CF5, 0xFF2F9E8F, 0xFFE0794A, 0xFF3B82C4, 0xFFC2477A, 0xFF8A9A3B)

fun hashPin(salt: String, pin: String): String =
    MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

fun Profile.withPin(pin: String?): Profile {
    if (pin == null) return copy(pinHash = null, salt = null, fingerprint = false)
    val salt = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    return copy(pinHash = hashPin(salt, pin), salt = salt)
}

/**
 * The profiles of this device and the one in use. Changing profile restarts
 * Nyxara (see [ProfileSwitch]): every screen then reads the new one's data.
 */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Profile.serializer())

    private val _profiles = MutableStateFlow(read())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _askAtStart = MutableStateFlow(prefs.getBoolean(ASK, false))

    /** The choice of profile at each launch, even with one profile (to lock it). */
    val askAtStart: StateFlow<Boolean> = _askAtStart.asStateFlow()

    /** The profile in use for this run of the app; fixed until the next start. */
    val active: Profile = _profiles.value.firstOrNull { it.id == prefs.getString(ACTIVE, Profile.MAIN) } ?: _profiles.value.first()

    /** The choice of profile shows at launch: several profiles, or asked for. */
    val choiceAtStart get() = _profiles.value.size > 1 || _askAtStart.value

    private fun read(): List<Profile> {
        val saved = runCatching { json.decodeFromString(serializer, prefs.getString(LIST, null) ?: "[]") }.getOrDefault(emptyList())
        return if (saved.none { it.isMain }) listOf(Profile(Profile.MAIN, "Moi")) + saved else saved
    }

    private fun save(profiles: List<Profile>) {
        prefs.edit { putString(LIST, json.encodeToString(serializer, profiles)) }
        _profiles.value = profiles
    }

    fun put(profile: Profile) = save(_profiles.value.filterNot { it.id == profile.id }.let { list ->
        val index = _profiles.value.indexOfFirst { it.id == profile.id }
        list.toMutableList().apply { add(if (index >= 0) index else size, profile) }
    })

    fun newProfile(name: String, child: Boolean): Profile {
        val used = _profiles.value.map { it.color }.toSet()
        return Profile(
            id = java.util.UUID.randomUUID().toString().take(8),
            name = name.trim().ifEmpty { "Profil" },
            color = PROFILE_COLORS.indices.firstOrNull { it !in used } ?: 0,
            child = child,
        )
    }

    /** Removes [id] (never the owner's); its history file goes with it, its Trakt account is only forgotten here. */
    fun remove(context: Context, id: String) {
        val profile = _profiles.value.firstOrNull { it.id == id }?.takeIf { !it.isMain } ?: return
        save(_profiles.value - profile)
        context.deleteDatabase(profile.historyDatabase)
        context.deleteSharedPreferences(profile.traktName)
        context.filesDir.resolve("${profile.traktName}.json").delete()
    }

    fun setAskAtStart(ask: Boolean) {
        prefs.edit { putBoolean(ASK, ask) }
        _askAtStart.value = ask
    }

    /** The profile of the next start. */
    fun choose(id: String) = prefs.edit(commit = true) { putString(ACTIVE, id) }

    private companion object {
        const val LIST = "list"
        const val ACTIVE = "active"
        const val ASK = "ask_at_start"
    }
}
