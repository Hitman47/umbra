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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.browse.naturalCompare
import io.github.mkdevtests.umbra.library.shortcutRoots
import io.github.mkdevtests.umbra.subtitles.SUBTITLE_LANGUAGES
import io.github.mkdevtests.umbra.subtitles.OpenSubtitles
import io.github.mkdevtests.umbra.BuildConfig
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.ui.theme.ScreenTitle
import io.github.mkdevtests.umbra.settings.AudioLanguage
import io.github.mkdevtests.umbra.settings.Language
import io.github.mkdevtests.umbra.settings.SettingsStore
import io.github.mkdevtests.umbra.settings.SubtitleSize
import io.github.mkdevtests.umbra.trakt.Trakt
import io.github.mkdevtests.umbra.ui.library.LibraryViewModel
import io.github.mkdevtests.umbra.update.UpdateBanner
import io.github.mkdevtests.umbra.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import io.github.mkdevtests.umbra.perso.PersoStore
import io.github.mkdevtests.umbra.ui.perso.MAX_PIN
import io.github.mkdevtests.umbra.ui.perso.MIN_PIN
import io.github.mkdevtests.umbra.ui.perso.PinDots
import io.github.mkdevtests.umbra.ui.perso.PinPad
import io.github.mkdevtests.umbra.ui.perso.fingerprintAvailable

@Composable
fun SettingsScreen(
    store: SettingsStore,
    trakt: Trakt,
    openSubtitles: OpenSubtitles,
    updater: Updater,
    sources: List<NasSource>,
    imageCache: File,
    onAddSource: () -> Unit,
    onEditSource: (NasSource) -> Unit,
    onRemoveSource: (NasSource) -> Unit,
    onEditFolders: (NasSource) -> Unit,
    onOpenStats: () -> Unit,
    library: LibraryViewModel,
    onOpenCorrections: () -> Unit,
    onOpenDownloads: () -> Unit,
    onTestNetwork: suspend () -> String,
    onExport: suspend (Uri) -> String,
    onImport: suspend (Uri) -> String,
    perso: PersoStore,
    catalog: io.github.mkdevtests.umbra.catalog.CatalogStore,
    /** The folders below a NAS path ("" for the shares), to choose the documentaries. */
    listFolders: suspend (String) -> List<String>,
    requests: io.github.mkdevtests.umbra.requests.RequestStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var networkResult by remember { mutableStateOf<String?>(null) }
    var backupResult by remember { mutableStateOf<String?>(null) }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { scope.launch { backupResult = runCatching { onExport(it) }.getOrElse { e -> "Échec : ${e.message}" } } }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { backupResult = runCatching { onImport(it) }.getOrElse { e -> "Échec : ${e.message}" } } }
    }
    val savedAt by library.savedAt.collectAsState()
    val fixes by library.matchFixes.collectAsState()
    val scan by library.scan.collectAsState()
    val settings by store.settings.collectAsState()
    val lastCheck by updater.lastCheck.collectAsState()
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(imageCache) {
        cacheSize = withContext(Dispatchers.IO) { imageCache.walk().filter { it.isFile }.sumOf { it.length() } }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle("Réglages", onBack)
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
                var removing by remember { mutableStateOf<NasSource?>(null) }
                sources.forEach { source ->
                    val addresses = source.hosts().joinToString(" ou ")
                    Item(source.label, "${source.protocol.label} · $addresses · ${source.shares.size} dossier${if (source.shares.size > 1) "s" else ""}") {
                        TextButton(onClick = { removing = source }) { Text("Retirer") }
                        TextButton(onClick = { onEditSource(source) }) { Text("Modifier ›") }
                    }
                    Item(
                        "Dossiers suivis",
                        if (source.excluded.isEmpty()) "Tous les dossiers" else "${source.excluded.size} dossier${if (source.excluded.size > 1) "s" else ""} exclu${if (source.excluded.size > 1) "s" else ""}",
                    ) {
                        TextButton(onClick = { onEditFolders(source) }) { Text("Choisir ›") }
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

            Section("Accueil") {
                val roots = remember(sources) { sources.flatMap { source -> source.shares.map(source::rootOf) }.sortedWith(::naturalCompare) }
                val chosen = shortcutRoots(settings.homeShortcuts, roots)
                ChipsItem("Raccourcis", "Dossiers proposés en haut de l'accueil, chacun avec ses films et séries.") {
                    roots.forEach { root ->
                        val on = chosen.any { it.equals(root, ignoreCase = true) }
                        FilterChip(
                            selected = on,
                            onClick = { store.update { it.copy(homeShortcuts = roots.filter { r -> if (r == root) !on else chosen.any { c -> c.equals(r, ignoreCase = true) } }) } },
                            label = { Text(root) },
                        )
                    }
                }
            }

            Section("Bibliothèque") {
                Item("Actualiser au lancement", "Seuls les fichiers nouveaux ou modifiés sont analysés.") {
                    Switch(checked = settings.rescanAtLaunch, onCheckedChange = { on -> store.update { it.copy(rescanAtLaunch = on) } })
                }
                Item(
                    "Bibliothèque enregistrée",
                    listOfNotNull(
                        savedAt?.let { "Analyse du ${DateUtils.formatDateTime(LocalContext.current, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)}" } ?: "Pas encore",
                        "point de départ du prochain lancement",
                        scan.error,
                    ).joinToString(" · "),
                )
                Item("Actualiser hors de chez moi", "Au lancement, même à travers Tailscale (plus lent). Sinon seulement à la maison.") {
                    Switch(checked = settings.scanAway, onCheckedChange = { on -> store.update { it.copy(scanAway = on) } })
                }
                Item("Téléchargements", "Films et épisodes copiés sur la tablette, pour les regarder sans le NAS.") {
                    TextButton(onClick = onOpenDownloads) { Text("Voir ›") }
                }
                Item("Corrections de matching", if (fixes.isEmpty()) "Aucune" else "${fixes.size} dossier${if (fixes.size > 1) "s" else ""} corrigé${if (fixes.size > 1) "s" else ""}") {
                    TextButton(onClick = onOpenCorrections) { Text("Voir ›") }
                }
                Item("Cache des affiches", cacheSize?.let { "${formatSize(it)} sur 1 Go" } ?: "Calcul…")
            }

            DocumentarySection(settings.documentaryFolders, listFolders) { folders -> store.update { it.copy(documentaryFolders = folders) } }

            Section("Lecture") {
                Item("Avancer, reculer", "Double appui sur un bord : 10 s. Maintenir ⏪ ou ⏩ : de plus en plus loin. Glisser sur l'image : des secondes aux minutes selon la longueur du geste.")
                Item("Passer les génériques tout seul", "Sinon, un bouton « Passer » apparaît pendant le générique (fichiers avec chapitres).") {
                    Switch(checked = settings.autoSkip, onCheckedChange = { on -> store.update { it.copy(autoSkip = on) } })
                }
                Item("Épisode suivant au générique", "Compte à rebours de 10 s dès le générique de fin, annulable.") {
                    Switch(checked = settings.nextEpisodeCountdown, onCheckedChange = { on -> store.update { it.copy(nextEpisodeCountdown = on) } })
                }
                Item("Image dans l'image", "Quitter le lecteur (bouton Accueil) garde la vidéo dans une petite fenêtre.") {
                    Switch(checked = settings.pictureInPicture, onCheckedChange = { on -> store.update { it.copy(pictureInPicture = on) } })
                }
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                Item("Son en arrière-plan", "Écran éteint ou autre appli : le son continue, avec une notification pour mettre en pause.") {
                    Switch(checked = settings.backgroundAudio, onCheckedChange = { on ->
                        store.update { it.copy(backgroundAudio = on) }
                        // The notification's pause button needs Android's permission.
                        if (on && android.os.Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                Item("Version plus légère hors de chez moi", "Par Tailscale ou en données mobiles : la version jusqu'au 1080p plutôt que la 4K. Toujours modifiable sur la fiche.") {
                    Switch(checked = settings.lighterAway, onCheckedChange = { on -> store.update { it.copy(lighterAway = on) } })
                }
                Item("Mode nuit", "Dialogues plus forts, explosions plus douces, à chaque lecture. Aussi dans le lecteur : Audio et sous-titres.") {
                    Switch(checked = settings.nightAudio, onCheckedChange = { on -> store.update { it.copy(nightAudio = on) } })
                }
                Item("Amélioration anime (Anime4K)", "Traits plus nets sur les dessins animés, à chaque lecture. Demande plus à la tablette.") {
                    Switch(checked = settings.animeUpscale, onCheckedChange = { on -> store.update { it.copy(animeUpscale = on) } })
                }
                Item("Tester le débit", networkResult ?: "Lit un gros film comme la lecture le ferait : aller-retour, débit, et ce qu'ils permettent. À faire chez soi puis dehors.") {
                    TextButton(onClick = {
                        networkResult = "Test en cours…"
                        scope.launch { networkResult = onTestNetwork() }
                    }) { Text("Tester") }
                }
                Item("Mesures de lecture", "Ouverture, sauts, coupures et débit de chaque lecture, à copier pour comparer.") {
                    TextButton(onClick = onOpenStats) { Text("Voir ›") }
                }
            }

            RequestsSection(requests)

            PersoSection(perso)
            CatalogSection(catalog)

            TraktSection(trakt)

            Section("Sauvegarde") {
                Item(
                    "Historique, corrections, réglages",
                    backupResult ?: "Dans un fichier que tu gardes où tu veux (jamais sur le NAS) ; à importer sur une autre tablette ou après une réinstallation. Les mots de passe des NAS n'y sont pas.",
                ) {
                    TextButton(onClick = { exportTo.launch("nyxara-sauvegarde.json") }) { Text("Exporter") }
                    TextButton(onClick = { importFrom.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) { Text("Importer") }
                }
            }

            OpenSubtitlesSection(
                openSubtitles, settings.onlineSubtitleLanguages, settings.autoOnlineSubtitles,
                onAuto = { on -> store.update { it.copy(autoOnlineSubtitles = on) } },
            ) { languages -> store.update { it.copy(onlineSubtitleLanguages = languages) } }

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
                "Lecture seule : Nyxara ne modifie ni ne supprime jamais de fichiers sur le NAS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The Perso tab's lock: a PIN, the fingerprint as a shortcut. */
@Composable
private fun PersoSection(perso: PersoStore) {
    val context = LocalContext.current
    val lock by perso.lock.collectAsState()
    // "new": choosing a PIN, then "confirm" it; "off": the current PIN asked to remove the lock.
    var step by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf("") }
    Section("Perso") {
        Item("Verrouiller l'onglet Perso", if (lock.enabled) "Code demandé à chaque retour dans l'appli." else "Un code (4 à 8 chiffres) avant d'afficher Perso.") {
            Switch(checked = lock.enabled, onCheckedChange = { on -> step = if (on) "new" else "off" })
        }
        if (lock.enabled) {
            Item("Changer le code") { TextButton(onClick = { step = "new" }) { Text("Changer") } }
            if (fingerprintAvailable(context)) {
                Item("Empreinte digitale", "Déverrouille Perso sans taper le code.") {
                    Switch(checked = lock.fingerprint, onCheckedChange = perso::setFingerprint)
                }
            }
        }
        Item("Historique à part", "Reprise et lectures de Perso restent sur cet appareil : ni Trakt, ni sauvegarde.")
    }
    when (step) {
        "new" -> PinDialog("Nouveau code", onDismiss = { step = null }) { pin -> chosen = pin; step = "confirm"; true }
        "confirm" -> PinDialog("Confirmer le code", onDismiss = { step = null }) { pin ->
            (pin == chosen).also { same -> if (same) { perso.setPin(pin); step = null } }
        }
        "off" -> PinDialog("Code actuel", onDismiss = { step = null }) { pin ->
            perso.unlock(pin).also { ok -> if (ok) { perso.removeLock(); step = null } }
        }
    }
}

/** Prowlarr (search) and qBittorrent (download) for the titles the library doesn't have. */
@Composable
private fun RequestsSection(requests: io.github.mkdevtests.umbra.requests.RequestStore) {
    val settings by requests.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Section("Recherche externe") {
        Item(
            "Prowlarr et qBittorrent",
            result ?: listOf(
                if (settings.canSearch) "Prowlarr : ${settings.prowlarrUrl}" else "Prowlarr : non réglé",
                if (settings.canSend) "qBittorrent : ${settings.qbitUrl}" else "qBittorrent : non réglé (envoi par Prowlarr)",
            ).joinToString(" · ") + ". Depuis la page d'un titre absent (titres similaires) : rechercher, choisir, envoyer. Catégories « Films Nyxara » et « Séries Nyxara ».",
        ) {
            TextButton(onClick = { editing = true }) { Text("Régler") }
            if (settings.canSearch || settings.canSend) {
                TextButton(onClick = {
                    result = "Test en cours…"
                    scope.launch { result = requests.check(settings) }
                }) { Text("Tester") }
            }
        }
    }
    if (editing) {
        var prowlarrUrl by remember { mutableStateOf(settings.prowlarrUrl) }
        var prowlarrKey by remember { mutableStateOf(settings.prowlarrKey) }
        var qbitUrl by remember { mutableStateOf(settings.qbitUrl) }
        var qbitUser by remember { mutableStateOf(settings.qbitUser) }
        var qbitPassword by remember { mutableStateOf(settings.qbitPassword) }
        var qbitKey by remember { mutableStateOf(settings.qbitKey) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Recherche externe") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(prowlarrUrl, { prowlarrUrl = it }, label = { Text("Adresse de Prowlarr") }, placeholder = { Text("http://192.168.1.10:9696") }, singleLine = true)
                    OutlinedTextField(
                        prowlarrKey, { prowlarrKey = it }, label = { Text("Clé API de Prowlarr") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    OutlinedTextField(qbitUrl, { qbitUrl = it }, label = { Text("Adresse de qBittorrent") }, placeholder = { Text("http://192.168.1.10:8080") }, singleLine = true)
                    OutlinedTextField(
                        qbitKey, { qbitKey = it }, label = { Text("Clé API WebUI de qBittorrent") }, singleLine = true,
                        supportingText = { Text("qBittorrent 5.2 ou plus récent. Sinon, identifiant et mot de passe ci-dessous.") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    OutlinedTextField(qbitUser, { qbitUser = it }, label = { Text("Identifiant qBittorrent") }, singleLine = true)
                    OutlinedTextField(
                        qbitPassword, { qbitPassword = it }, label = { Text("Mot de passe qBittorrent") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    requests.save(io.github.mkdevtests.umbra.requests.RequestSettings(prowlarrUrl, prowlarrKey, qbitUrl, qbitUser, qbitPassword, qbitKey))
                    result = null
                    editing = false
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Annuler") } },
        )
    }
}

/** The documentary folders: their titles go to the Docs tab, out of Films and Séries. */
@Composable
private fun DocumentarySection(folders: List<String>, listFolders: suspend (String) -> List<String>, onChange: (List<String>) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Section("Documentaires") {
        Item(
            "Dossiers de documentaires",
            if (folders.isEmpty()) "Aucun : un onglet Docs apparaît dès qu'un dossier est choisi. Ses titres quittent Films et Séries." else "Leurs films et séries sont dans l'onglet Docs, plus dans Films ni Séries.",
        ) { TextButton(onClick = { picking = true }) { Text("Ajouter ›") } }
        folders.forEach { folder ->
            Item(folder.substringAfterLast('\\'), folder.replace("\\", " › ")) {
                TextButton(onClick = { onChange(folders - folder) }) { Text("Retirer") }
            }
        }
    }
    if (picking) {
        FolderPickerDialog(
            "Dossier de documentaires",
            listFolders,
            onPick = { path ->
                picking = false
                // A folder inside one already chosen adds nothing; one around others replaces them.
                if (folders.none { path.equals(it, true) || path.startsWith("$it\\", true) }) onChange(folders.filterNot { it.startsWith("$path\\", true) } + path)
            },
            onDismiss = { picking = false },
        )
    }
}

/** The external catalogue shown as Profils in Perso: where, how often, its state. */
@Composable
private fun CatalogSection(catalog: io.github.mkdevtests.umbra.catalog.CatalogStore) {
    val address by catalog.address.collectAsState()
    val mode by catalog.mode.collectAsState()
    val status by catalog.status.collectAsState()
    val index by catalog.index.collectAsState()
    var editing by remember { mutableStateOf(false) }
    Section("Catalogue externe") {
        Item(
            "Adresse",
            if (address.configured) listOfNotNull(address.home, address.away.ifBlank { null }).joinToString(" · ") else "Aucune : la vue Profils de Perso reste cachée.",
        ) { TextButton(onClick = { editing = true }) { Text(if (address.configured) "Modifier" else "Régler") } }
        if (address.configured) {
            ChipsItem("Synchronisation", null) {
                io.github.mkdevtests.umbra.catalog.CatalogSync.entries.forEach { entry ->
                    FilterChip(selected = mode == entry, onClick = { catalog.setMode(entry) }, label = { Text(entry.label) })
                }
            }
            val state = when {
                status.syncing -> status.progress ?: "Synchronisation…"
                status.error != null -> status.error
                index != null -> index!!.let { "${it.people.size} profils · ${it.data.videos.size} vidéos · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it.data.syncedAt))}" }
                else -> "Jamais synchronisé"
            }
            Item("État", state) {
                TextButton(onClick = catalog::sync, enabled = !status.syncing) { Text("Synchroniser") }
            }
        }
    }
    if (editing) {
        var home by remember { mutableStateOf(address.home) }
        var away by remember { mutableStateOf(address.away) }
        var user by remember { mutableStateOf(address.user) }
        var password by remember { mutableStateOf(address.password) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Catalogue externe") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(home, { home = it }, label = { Text("Adresse à la maison") }, placeholder = { Text("http://192.168.1.10:8086") }, singleLine = true)
                    OutlinedTextField(away, { away = it }, label = { Text("Adresse hors de chez moi (facultatif)") }, singleLine = true)
                    OutlinedTextField(user, { user = it }, label = { Text("Identifiant (facultatif)") }, singleLine = true)
                    OutlinedTextField(
                        password, { password = it }, label = { Text("Mot de passe (facultatif)") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    Text("Lecture seule : rien n'est jamais modifié dans le catalogue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    catalog.setAddress(io.github.mkdevtests.umbra.catalog.CatalogAddress(home, away, user, password))
                    editing = false
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Annuler") } },
        )
    }
}

/** Asks a PIN; [onPin] says whether it is accepted, a refused one is asked again. */
@Composable
private fun PinDialog(title: String, onDismiss: () -> Unit, onPin: (String) -> Boolean) {
    var pin by remember(title) { mutableStateOf("") }
    var wrong by remember(title) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                PinDots(pin.length, wrong)
                PinPad(
                    onDigit = { digit -> wrong = false; if (pin.length < MAX_PIN) pin += digit },
                    onErase = { pin = pin.dropLast(1); wrong = false },
                    onValidate = {
                        if (pin.length >= MIN_PIN && onPin(pin)) pin = "" else { wrong = true; pin = "" }
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
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
                Item("Code : ${code.userCode}", "Entre-le sur ${code.verificationUrl} (téléphone ou PC). Nyxara attend la validation.") {
                    TextButton(onClick = { uri.openUri(code.verificationUrl) }) { Text("Ouvrir") }
                    TextButton(onClick = trakt::cancelConnect) { Text("Annuler") }
                }
            }
            !status.connected -> Item(
                "Compte",
                if (status.connecting) "Demande du code à Trakt…" else "Non connecté. Nyxara lit l'historique Trakt et y ajoute ce que tu regardes ; il n'efface jamais rien.",
            ) {
                TextButton(onClick = trakt::connect, enabled = !status.connecting) { Text(if (status.error != null) "Réessayer ›" else "Connecter ›") }
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

/** Subtitles searched online from the player: the languages, and an optional account for more downloads. */
@Composable
private fun OpenSubtitlesSection(service: OpenSubtitles, languages: List<String>, auto: Boolean, onAuto: (Boolean) -> Unit, onLanguages: (List<String>) -> Unit) {
    val status by service.status.collectAsState()
    Section("Sous-titres en ligne") {
        if (!status.configured) {
            Item("Clé OpenSubtitles absente", "Ajoute opensubtitles.key dans local.properties puis recompile.")
            return@Section
        }
        ChipsItem("Langues cherchées", "Dans le lecteur : Sous-titres › Chercher en ligne.") {
            SUBTITLE_LANGUAGES.forEach { (code, name) ->
                val on = code in languages
                FilterChip(
                    selected = on,
                    onClick = { onLanguages(if (on) languages - code else SUBTITLE_LANGUAGES.keys.filter { it in languages || it == code }) },
                    label = { Text(name) },
                )
            }
        }
        Item("Chercher tout seul", "Si le fichier n'a pas de sous-titres dans ta langue : le meilleur est ajouté dès le début, sans rien demander.") {
            Switch(checked = auto, onCheckedChange = onAuto)
        }
        val user = status.user
        if (user != null) {
            Item("Compte $user", status.allowed?.let { "$it téléchargements par jour" }) {
                TextButton(onClick = service::logout) { Text("Déconnecter") }
            }
        } else {
            var name by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            Item("Compte (facultatif)", "Sans compte : 5 téléchargements par jour. Gratuit sur opensubtitles.com : 20 par jour.")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Identifiant") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    password, { password = it }, label = { Text("Mot de passe") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { service.login(name.trim(), password) }, enabled = name.isNotBlank() && password.isNotEmpty() && !status.busy) { Text("Connecter") }
            }
        }
        status.error?.let { Item("Erreur", it) }
    }
}

/** Battery saver cuts Nyxara's network in the background: offers the system dialog that exempts it. */
@SuppressLint("BatteryLife")
@Composable
private fun ColumnScope.BatteryItem() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    fun exempt() = power?.isIgnoringBatteryOptimizations(context.packageName) != false
    var exempted by remember { mutableStateOf(exempt()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        exempted = exempt()
        if (exempted) (context.applicationContext as io.github.mkdevtests.umbra.NyxaraApp).trakt.onBatteryExempted()
    }
    if (exempted) return
    Item(
        "Économie d'énergie active",
        "Android coupe le réseau de Nyxara dès qu'il quitte l'écran : Trakt peut échouer (« injoignable ») et un épisode fini en arrière-plan ne pas être marqué vu. Autorise Nyxara sans restriction.",
    ) {
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
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column { content() }
        }
    }
}

/** One settings row: label and explanation on the left, controls on the right. */
@Composable
private fun ColumnScope.Item(label: String, detail: String? = null, controls: (@Composable () -> Unit)? = null) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        val text = @Composable { modifier: Modifier ->
            Column(modifier = modifier) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (maxWidth < 600.dp) {
            // A phone: the controls under the text, on as many lines as they need, the text keeps the width.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                text(Modifier.fillMaxWidth())
                controls?.let { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { it() } }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                text(Modifier.weight(1f))
                controls?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { it() } }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.background)
}

/** A settings row whose choices go below it, on as many lines as they need. */
@Composable
private fun ColumnScope.ChipsItem(label: String?, detail: String?, chips: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
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
