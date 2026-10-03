package io.github.mkdevtests.umbra.history

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.nas.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Writes and reads [Backup] files the user picks (Réglages › Sauvegarde). */
class BackupManager(private val app: NyxaraApp) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun export(uri: Uri): String = withContext(Dispatchers.IO) {
        val backup = Backup(
            createdAt = System.currentTimeMillis(),
            progress = app.history.progress.value.values.map { BackupProgress(it.file, it.position, it.duration, it.updatedAt) },
            fixes = app.matchFixes.load().values.map { BackupFix(it.groupKey, it.tmdbId, it.season, it.firstEpisode) },
            hidden = app.hidden.keys.value.toList(),
            prefs = PREFS.associateWith { name ->
                app.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapNotNull { (key, value) -> encodePref(value)?.let { key to it } }.toMap()
            },
            sources = app.sources.load().map {
                BackupSource(it.id, it.name, it.host, it.shares, it.username, it.domain, it.roots, it.excluded, it.protocol.name, it.fallbackHost)
            },
        )
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.encodeToString(Backup.serializer(), backup).toByteArray()) }
            ?: error("fichier inaccessible")
        "Sauvegardé : ${backup.progress.size} lectures, ${backup.fixes.size} corrections, ${backup.hidden.size} titres masqués, ${backup.sources.size} NAS."
    }

    /** Merges [uri]'s backup in: newer progress wins, corrections and hidden titles added, preferences replaced, missing NAS added. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("fichier illisible")
        val backup = json.decodeFromString(Backup.serializer(), text)
        require(backup.app == "Nyxara") { "ce n'est pas une sauvegarde de Nyxara" }
        val progress = newerProgress(app.history.progress.value, backup.progress)
        app.history.restore(progress)
        app.matchFixes.save(backup.fixes.map { MatchFix(it.group, it.tmdbId, it.season, it.firstEpisode) })
        backup.hidden.forEach { app.hidden.setHidden(it, true) }
        backup.prefs.filterKeys { it in PREFS }.forEach { (name, values) ->
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit {
                values.forEach { (key, encoded) ->
                    when (val value = decodePref(encoded)) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }
        }
        app.settings.reload()
        val known = app.sources.load().map { it.id }.toSet()
        val added = backup.sources.filter { it.id !in known }.map {
            NasSource(
                it.host, it.shares, it.username, "", it.domain, it.id, it.name, it.roots, it.excluded,
                Protocol.entries.firstOrNull { p -> p.name == it.protocol } ?: Protocol.Smb, it.fallbackHost,
            )
        }
        if (added.isNotEmpty()) app.addSources(added)
        buildString {
            append("Restauré : ${progress.size} lectures, ${backup.fixes.size} corrections, ${backup.hidden.size} titres masqués.")
            if (added.isNotEmpty()) append(" ${added.size} NAS ajouté(s) : saisis leur mot de passe dans Sources.")
            if (backup.fixes.isNotEmpty()) append(" Les corrections s'appliquent à la prochaine actualisation.")
        }
    }

    private companion object {
        /** The preference files worth carrying to another device. */
        val PREFS = listOf("settings", "versions")
    }
}
