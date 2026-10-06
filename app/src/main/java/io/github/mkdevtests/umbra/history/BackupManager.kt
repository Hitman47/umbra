package io.github.mkdevtests.umbra.history

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.catalog.CatalogAddress
import io.github.mkdevtests.umbra.catalog.CatalogSync
import io.github.mkdevtests.umbra.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Writes and reads [Backup] files the user picks (Réglages › Sauvegarde). */
class BackupManager(private val app: NyxaraApp) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Everything a backup keeps; [secrets]: with the passwords and keys too, for a sealed transfer only. */
    suspend fun snapshot(secrets: Boolean): Backup {
        val requests = app.requests.settings.value
        val catalog = app.catalog.address.value
        val services = BackupServices(
            prowlarrUrl = requests.prowlarrUrl, qbitUrl = requests.qbitUrl, qbitUser = requests.qbitUser,
            catalogHome = catalog.home, catalogAway = catalog.away, catalogUser = catalog.user,
            catalogMode = app.catalog.mode.value.name, catalogIgnored = app.catalog.ignored.value,
            prowlarrKey = requests.prowlarrKey, qbitPassword = requests.qbitPassword, qbitKey = requests.qbitKey,
            catalogPassword = catalog.password,
        )
        return Backup(
            createdAt = System.currentTimeMillis(),
            progress = app.history.progress.value.values.map { BackupProgress(it.file, it.position, it.duration, it.updatedAt) },
            fixes = app.matchFixes.load().values.map { BackupFix(it.groupKey, it.tmdbId, it.season, it.firstEpisode) },
            hidden = app.hidden.keys.value.toList(),
            prefs = PREFS.associateWith { name ->
                app.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapNotNull { (key, value) -> encodePref(value)?.let { key to it } }.toMap()
            },
            sources = app.sources.load().map {
                BackupSource(
                    it.id, it.name, it.host, it.shares, it.username, it.domain, it.roots, it.excluded, it.protocol.name, it.fallbackHost,
                    it.personal, password = if (secrets) it.password else "",
                )
            },
            services = if (secrets) services else services.public(),
        )
    }

    /** For another device: with the secrets, without what suits one screen only (sizes, sound output). */
    suspend fun transferSnapshot(): Backup = snapshot(secrets = true).let { backup ->
        backup.copy(prefs = backup.prefs.mapValues { (name, values) -> if (name == "settings") values - DEVICE_KEYS else values })
    }

    suspend fun export(uri: Uri): String = withContext(Dispatchers.IO) {
        val backup = snapshot(secrets = false)
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.encodeToString(Backup.serializer(), backup).toByteArray()) }
            ?: error("fichier inaccessible")
        "Sauvegardé : ${backup.progress.size} lectures, ${backup.fixes.size} corrections, ${backup.hidden.size} titres masqués, ${backup.sources.size} NAS."
    }

    /** Merges [uri]'s backup in (see [apply]). */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("fichier illisible")
        apply(json.decodeFromString(Backup.serializer(), text))
    }

    /**
     * Merges [backup] in: newer progress wins, corrections and hidden titles
     * added, preferences replaced, missing NAS added (known ones only take a
     * password they lack), the services' settings taken when given.
     */
    suspend fun apply(backup: Backup): String = withContext(Dispatchers.IO) {
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
        val merge = mergeSources(app.sources.load(), backup.sources)
        if (merge.updated.isNotEmpty()) app.updateSources(merge.updated)
        if (merge.added.isNotEmpty()) app.addSources(merge.added)
        backup.services?.let(::applyServices)
        buildString {
            append("Restauré : ${progress.size} lectures, ${backup.fixes.size} corrections, ${backup.hidden.size} titres masqués.")
            if (merge.added.isNotEmpty()) append(" ${merge.added.size} NAS ajouté(s).")
            if (merge.added.any { it.password.isEmpty() && it.username.isNotEmpty() }) append(" Saisis leur mot de passe dans Sources.")
            if (backup.fixes.isNotEmpty()) append(" Les corrections s'appliquent à la prochaine actualisation.")
        }
    }

    /** The services' addresses taken when given; a secret kept here when the backup has none. */
    private fun applyServices(services: BackupServices) {
        val requests = app.requests.settings.value
        if (services.prowlarrUrl.isNotBlank() || services.qbitUrl.isNotBlank()) {
            app.requests.save(
                requests.copy(
                    prowlarrUrl = services.prowlarrUrl.ifBlank { requests.prowlarrUrl },
                    prowlarrKey = services.prowlarrKey.ifBlank { requests.prowlarrKey },
                    qbitUrl = services.qbitUrl.ifBlank { requests.qbitUrl },
                    qbitUser = services.qbitUser.ifBlank { requests.qbitUser },
                    qbitPassword = services.qbitPassword.ifBlank { requests.qbitPassword },
                    qbitKey = services.qbitKey.ifBlank { requests.qbitKey },
                ),
            )
        }
        if (services.catalogHome.isNotBlank()) {
            val catalog = app.catalog.address.value
            CatalogSync.entries.firstOrNull { it.name == services.catalogMode }?.let(app.catalog::setMode)
            if (services.catalogIgnored.isNotEmpty()) app.catalog.setIgnored(services.catalogIgnored)
            app.catalog.setAddress(
                CatalogAddress(
                    services.catalogHome,
                    services.catalogAway.ifBlank { catalog.away },
                    services.catalogUser.ifBlank { catalog.user },
                    services.catalogPassword.ifBlank { catalog.password },
                ),
            )
        }
    }

    private companion object {
        /** The preference files worth carrying to another device. */
        val PREFS = listOf("settings", "versions")

        /** Settings that depend on the screen or the sound output, left as they are on the receiving device. */
        val DEVICE_KEYS = setOf(
            SettingsStore.KEY_CARD_SCALE, SettingsStore.KEY_UI_SCALE, SettingsStore.KEY_STRONG_FOCUS, SettingsStore.KEY_PASSTHROUGH,
        )
    }
}
