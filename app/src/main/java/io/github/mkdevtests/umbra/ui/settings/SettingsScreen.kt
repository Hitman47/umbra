package io.github.mkdevtests.umbra.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.BuildConfig
import io.github.mkdevtests.umbra.nas.SmbSource
import io.github.mkdevtests.umbra.settings.AudioLanguage
import io.github.mkdevtests.umbra.settings.Language
import io.github.mkdevtests.umbra.settings.SettingsStore
import io.github.mkdevtests.umbra.settings.SubtitleSize
import io.github.mkdevtests.umbra.trakt.Trakt
import io.github.mkdevtests.umbra.update.UpdateBanner
import io.github.mkdevtests.umbra.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(
    store: SettingsStore,
    trakt: Trakt,
    updater: Updater,
    sources: List<SmbSource>,
    imageCache: File,
    onAddSource: () -> Unit,
    onEditSource: (SmbSource) -> Unit,
    onRemoveSource: (SmbSource) -> Unit,
    onIncludeFolder: (SmbSource, String) -> Unit,
    onOpenStats: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by store.settings.collectAsState()
    val lastCheck by updater.lastCheck.collectAsState()
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(imageCache) {
        cacheSize = withContext(Dispatchers.IO) { imageCache.walk().filter { it.isFile }.sumOf { it.length() } }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(modifier = Modifier.padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Retour") }
            Text("Réglages", style = MaterialTheme.typography.headlineMedium)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Section("Profil de lecture") {
                Item("Audio préféré", "Touchez une langue pour la monter d'un rang.") {
                    settings.audioOrder.forEachIndexed { index, language ->
                        FilterChip(
                            selected = index == 0,
                            onClick = { store.update { it.copy(audioOrder = it.audioOrder.movedUp(language)) } },
                            label = { Text("${index + 1} · ${language.label}") },
                        )
                    }
                }
                Item("Sous-titres", "Complets, pas seulement les passages forcés. Si l'audio est déjà dans cette langue : seulement les passages étrangers.") {
                    (Language.entries + null).forEach { language ->
                        FilterChip(
                            selected = settings.subtitles == language,
                            onClick = { store.update { it.copy(subtitles = language) } },
                            label = { Text(language?.label ?: "Aucun") },
                        )
                    }
                }
                Item("Taille des sous-titres") {
                    SubtitleSize.entries.forEach { size ->
                        FilterChip(
                            selected = settings.subtitleSize == size,
                            onClick = { store.update { it.copy(subtitleSize = size) } },
                            label = { Text(size.label) },
                        )
                    }
                }
                Item("Si une langue manque", "La piste la plus proche est choisie : la lecture n'est jamais bloquée. Les choix s'appliquent à la prochaine vidéo ouverte.")
            }

            Section("Sources") {
                var removing by remember { mutableStateOf<SmbSource?>(null) }
                sources.forEach { source ->
                    Item(source.label, "SMB · ${source.host} · ${source.shares.size} partage${if (source.shares.size > 1) "s" else ""}") {
                        TextButton(onClick = { removing = source }) { Text("Retirer") }
                        TextButton(onClick = { onEditSource(source) }) { Text("Modifier ›") }
                    }
                    source.excluded.forEach { folder ->
                        Item("Exclu : ${folder.replace("\\", " › ")}", "Ni analysé, ni affiché, ni lisible.") {
                            TextButton(onClick = { onIncludeFolder(source, folder) }) { Text("Rétablir") }
                        }
                    }
                }
                Item("Ajouter un NAS", "Les vues Films et Séries regroupent toutes les sources.") {
                    TextButton(onClick = onAddSource) { Text("Ajouter ›") }
                }
                removing?.let { source ->
                    AlertDialog(
                        onDismissRequest = { removing = null },
                        title = { Text("Retirer ${source.label} ?") },
                        text = { Text("Ses titres quittent la bibliothèque. L'historique de lecture est gardé : il revient si le NAS est ajouté à nouveau.") },
                        confirmButton = { TextButton(onClick = { removing = null; onRemoveSource(source) }) { Text("Retirer") } },
                        dismissButton = { TextButton(onClick = { removing = null }) { Text("Annuler") } },
                    )
                }
            }

            Section("Bibliothèque") {
                Item("Actualiser au lancement", "Seuls les fichiers nouveaux ou modifiés sont analysés.") {
                    Switch(checked = settings.rescanAtLaunch, onCheckedChange = { on -> store.update { it.copy(rescanAtLaunch = on) } })
                }
                Item("Cache des affiches", cacheSize?.let { "${formatSize(it)} sur 1 Go" } ?: "Calcul…")
            }

            Section("Lecture") {
                Item("Mesures de lecture", "Ouverture, sauts, coupures et débit de chaque lecture, à copier pour comparer.") {
                    TextButton(onClick = onOpenStats) { Text("Voir ›") }
                }
            }

            TraktSection(trakt)

            Section("Mises à jour") {
                if (BuildConfig.UPDATES) {
                    Item("Version installée", "${BuildConfig.VERSION_NAME} · GitHub ${Updater.REPOSITORY}${lastCheck?.let { " · $it" } ?: ""}") {
                        TextButton(onClick = updater::check) { Text("Rechercher") }
                    }
                } else {
                    Item("Version installée", "${BuildConfig.VERSION_NAME} · version de test, mise à jour par le PC")
                }
            }
            UpdateBanner(updater)

            Text(
                "Lecture seule : Umbra ne modifie ni ne supprime jamais de fichiers sur le NAS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TraktSection(trakt: Trakt) {
    val status by trakt.status.collectAsState()
    val uri = LocalUriHandler.current
    Section("Trakt") {
        val code = status.pendingCode
        when {
            !status.configured -> Item("Clé Trakt absente", "Ajoute trakt.clientId dans local.properties puis recompile.")
            code != null -> {
                Item("Code : ${code.userCode}", "Entre-le sur ${code.verificationUrl} (téléphone ou PC). Umbra attend la validation.") {
                    TextButton(onClick = { uri.openUri(code.verificationUrl) }) { Text("Ouvrir") }
                    TextButton(onClick = trakt::cancelConnect) { Text("Annuler") }
                }
            }
            !status.connected -> Item("Compte", "Non connecté. Umbra lit l'historique Trakt et y ajoute ce que tu regardes ; il n'efface jamais rien.") {
                TextButton(onClick = trakt::connect) { Text("Connecter ›") }
            }
            else -> {
                val last = if (status.lastSync > 0) DateUtils.getRelativeTimeSpanString(status.lastSync).toString() else "jamais"
                Item("Synchronisation", if (status.syncing) status.syncingShows?.let { "Séries : $it" } ?: "En cours…" else "Dernière : $last") {
                    TextButton(onClick = { trakt.sync(force = true) }, enabled = !status.syncing) { Text("Synchroniser") }
                }
                BatteryItem()
                Item("Envoyer ce que je regarde", "Vu à 80 % : ajouté à l'historique Trakt. Avant : point de reprise.") {
                    Switch(checked = status.scrobble, onCheckedChange = trakt::setScrobble)
                }
                Item("Déconnecter", "Oublie le compte sur la tablette. Rien n'est effacé sur Trakt.") {
                    TextButton(onClick = trakt::disconnect) { Text("Déconnecter") }
                }
            }
        }
        status.error?.let { Item("Erreur", it) }
    }
}

/** Battery saver cuts Umbra's network in the background: offers the system dialog that exempts it. */
@SuppressLint("BatteryLife")
@Composable
private fun ColumnScope.BatteryItem() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    fun exempt() = power?.isIgnoringBatteryOptimizations(context.packageName) != false
    var exempted by remember { mutableStateOf(exempt()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { exempted = exempt() }
    if (exempted) return
    Item("Économie d'énergie", "Elle peut couper Trakt quand Umbra passe en arrière-plan (quitter un épisode avec Accueil). Autorise Umbra à rester connecté.") {
        TextButton(onClick = {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            runCatching { ask.launch(request) }.onFailure { ask.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }) { Text("Autoriser ›") }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column { content() }
        }
    }
}

/** One settings row: label and explanation on the left, controls on the right. */
@Composable
private fun ColumnScope.Item(label: String, detail: String? = null, controls: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        controls?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { it() } }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.background)
}

private fun List<AudioLanguage>.movedUp(language: AudioLanguage): List<AudioLanguage> {
    val index = indexOf(language)
    if (index <= 0) return this
    return toMutableList().apply { add(index - 1, removeAt(index)) }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f Go".format(bytes / (1L shl 30).toDouble())
    else -> "${bytes / (1L shl 20)} Mo"
}
