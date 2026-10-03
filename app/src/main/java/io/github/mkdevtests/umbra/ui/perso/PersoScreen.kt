package io.github.mkdevtests.umbra.ui.perso

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.download.DownloadState
import io.github.mkdevtests.umbra.nas.NasEntry
import io.github.mkdevtests.umbra.ui.theme.GlassButton
import io.github.mkdevtests.umbra.ui.theme.GlowButton
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.SectionHeader
import io.github.mkdevtests.umbra.ui.theme.focusRing
import java.util.Locale

/**
 * The Perso tab: folders played as plain videos, apart from the library (no
 * metadata, their own history), behind a PIN when the user asks for one.
 */
@Composable
fun PersoScreen(viewModel: PersoViewModel) {
    val lock by viewModel.store.lock.collectAsState()
    val unlocked by viewModel.store.unlocked.collectAsState()
    if (lock.enabled && !unlocked) {
        PersoLockScreen(viewModel.store)
        return
    }
    val app = LocalContext.current.applicationContext as io.github.mkdevtests.umbra.NyxaraApp
    val address by app.catalog.address.collectAsState()
    var profiles by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (!address.configured) {
        PersoFolders(viewModel)
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(selected = !profiles, onClick = { profiles = false }, label = { Text("Dossiers") })
            androidx.compose.material3.FilterChip(selected = profiles, onClick = { profiles = true }, label = { Text("Profils") })
        }
        Box(modifier = Modifier.weight(1f)) {
            if (profiles) ProfilesScreen(app) else PersoFolders(viewModel)
        }
    }
}

/** The Perso folders, browsed as they are. */
@Composable
private fun PersoFolders(viewModel: PersoViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val infos by viewModel.infos.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val sort by viewModel.sort.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var videoMenu by remember { mutableStateOf<NasEntry?>(null) }

    BackHandler(enabled = state.path.isNotEmpty()) { viewModel.up() }

    if (picking) FolderPicker(viewModel, onPick = { picking = false; viewModel.add(it) }, onDismiss = { picking = false })

    folderMenu?.let { path ->
        val root = folders.any { it.equals(path, ignoreCase = true) }
        MenuDialog(path.substringAfterLast('\\'), onDismiss = { folderMenu = null }) {
            MenuItem("Lecture aléatoire") { folderMenu = null; context.startActivity(viewModel.playIntent(path, shuffle = true)) }
            MenuItem("Lecture dans l'ordre") { folderMenu = null; context.startActivity(viewModel.playIntent(path, shuffle = false)) }
            if (root) {
                MenuItem("Remettre dans la bibliothèque") { folderMenu = null; viewModel.remove(path, exclude = false) }
                MenuItem(if ('\\' in path) "Retirer de Perso et exclure" else "Retirer de Perso", danger = true) { folderMenu = null; viewModel.remove(path, exclude = true) }
            }
        }
    }

    videoMenu?.let { entry ->
        val download = downloads.firstOrNull { it.key == persoDownloadKey(entry.path) }
        MenuDialog(entry.name, onDismiss = { videoMenu = null }) {
            MenuItem("Lire depuis le début") {
                videoMenu = null
                viewModel.forget(entry.path)
                context.startActivity(viewModel.playIntent(state.path, shuffle = false, start = entry.path))
            }
            if (progress[entry.path] != null) MenuItem("Marquer non vu") { videoMenu = null; viewModel.forget(entry.path) }
            when {
                download == null || download.state == DownloadState.Failed -> MenuItem("Télécharger sur l'appareil") { videoMenu = null; viewModel.download(entry) }
                download.state == DownloadState.Done -> MenuItem("Supprimer le téléchargement", danger = true) { videoMenu = null; viewModel.removeDownload(entry.path) }
                else -> MenuItem("Annuler le téléchargement", danger = true) { videoMenu = null; viewModel.removeDownload(entry.path) }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.path.isEmpty()) {
            val local = downloads.filter { it.key.startsWith("perso:") }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    SectionHeader("Perso", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                        IconButton(onClick = { picking = true }) { Icon(NyxaraIcons.Add, contentDescription = "Ajouter un dossier") }
                    }
                }
                if (folders.isEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(
                                "Des dossiers lus comme avec un simple lecteur vidéo : sans fiches ni recherche en ligne, avec leur propre reprise, " +
                                    "jamais mêlés aux films et séries. Lecture aléatoire ou dans l'ordre, sous-dossiers compris.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            GlowButton("Ajouter un dossier", onClick = { picking = true }, icon = NyxaraIcons.Add)
                        }
                    }
                }
                items(folders, key = { it }) { path ->
                    FolderRow(path.substringAfterLast('\\'), viewModel.folderDetail(path), onClick = { viewModel.open(path) }, onLongClick = { folderMenu = path })
                }
                if (local.isNotEmpty()) {
                    item { SectionHeader("Sur l'appareil", modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)) }
                    items(local, key = { it.key }) { download ->
                        val path = download.key.removePrefix("perso:")
                        val detail = when (download.state) {
                            DownloadState.Done -> formatSize(download.size)
                            DownloadState.Failed -> "Échec : ${download.error.orEmpty()}"
                            else -> "Téléchargement… ${(download.fraction * 100).toInt()} %"
                        }
                        VideoRow(
                            name = path.substringAfterLast('\\'),
                            detail = detail,
                            progress = progress[path],
                            downloaded = download.state == DownloadState.Done,
                            onClick = { if (download.state == DownloadState.Done) context.startActivity(viewModel.playDownloaded(path)) },
                            onLongClick = { viewModel.removeDownload(path) },
                        )
                    }
                }
            }
            return@Column
        }

        Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.up() }) { Icon(NyxaraIcons.Back, contentDescription = "Retour") }
            Column(modifier = Modifier.weight(1f)) {
                Text(state.crumbs.lastOrNull().orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.crumbs.size > 1) {
                    Text(
                        state.crumbs.dropLast(1).joinToString(" › "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlowButton("Aléatoire", onClick = { context.startActivity(viewModel.playIntent(state.path, shuffle = true)) }, icon = NyxaraIcons.Shuffle)
            GlassButton("Dans l'ordre", onClick = { context.startActivity(viewModel.playIntent(state.path, shuffle = false)) }, icon = NyxaraIcons.Play)
        }
        Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PersoSort.entries.forEach { entry ->
                androidx.compose.material3.FilterChip(selected = sort == entry, onClick = { viewModel.sortBy(entry) }, label = { Text(entry.label) })
            }
        }
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.error != null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::refresh) { Text("Réessayer") }
                }
                state.entries.isEmpty() -> Text("Aucune vidéo dans ce dossier.", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                    items(sortedFor(state.entries, sort), key = { it.path }) { entry ->
                        if (entry.isDirectory) {
                            FolderRow(entry.name, null, onClick = { viewModel.open(entry.path) }, onLongClick = { folderMenu = entry.path })
                        } else {
                            val duration = infos[entry.path]?.duration ?: progress[entry.path]?.duration
                            val download = downloads.firstOrNull { it.key == persoDownloadKey(entry.path) }
                            VideoRow(
                                name = entry.name,
                                detail = listOfNotNull(formatSize(entry.size), duration?.let(::lengthLabel)).joinToString("  ·  "),
                                progress = progress[entry.path],
                                downloaded = download?.state == DownloadState.Done,
                                onClick = { context.startActivity(viewModel.playIntent(state.path, shuffle = false, start = entry.path)) },
                                onLongClick = { videoMenu = entry },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, detail: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().focusRing().combinedClickable(onLongClick = onLongClick, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(NyxaraIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(NyxaraIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VideoRow(
    name: String,
    detail: String,
    progress: io.github.mkdevtests.umbra.perso.PersoProgress?,
    downloaded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().focusRing().combinedClickable(onLongClick = onLongClick, onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            if (progress?.watched == true) NyxaraIcons.Check else NyxaraIcons.Play,
            contentDescription = null,
            tint = if (progress?.watched == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (downloaded) Icon(NyxaraIcons.Download, contentDescription = "Sur l'appareil", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (progress?.inProgress == true) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.width(120.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun MenuDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Column { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun MenuItem(label: String, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

/** Chooses a folder for the Perso tab: every share of the NAS (those the library doesn't read too), then their folders. */
@Composable
private fun FolderPicker(viewModel: PersoViewModel, onPick: (PickPlace) -> Unit, onDismiss: () -> Unit) {
    var place by remember { mutableStateOf(PickPlace()) }
    var items by remember { mutableStateOf<List<PickItem>?>(null) }
    LaunchedEffect(place) {
        items = null
        items = viewModel.pick(place)
    }
    val several = viewModel.pickerSources.size > 1
    val share = place.share
    val title = when {
        share == null -> "Ajouter à Perso"
        place.sub.isEmpty() -> share
        else -> place.sub.substringAfterLast('\\')
    }
    val already = share != null && viewModel.isPersonal(place)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        share == null -> "Les partages du NAS : ceux que la bibliothèque n'utilise pas sont libres. Ce que vous choisissez quitte la bibliothèque et se lit ici, sans fiches."
                        already -> "Déjà dans Perso."
                        else -> listOfNotNull(place.source?.label?.takeIf { several }, share, place.sub.ifEmpty { null }?.replace("\\", " › ")).joinToString(" › ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                    val list = items
                    when {
                        list == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        list.isEmpty() -> Text(
                            if (share == null) "Aucun partage trouvé." else "Aucun sous-dossier.",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn {
                            items(list, key = { it.label + it.place.sub }) { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { place = item.place }.padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(NyxaraIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Text(item.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        item.tag ?: if (item.place.sub.isEmpty() && item.place.share != null) "Libre" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (item.tag == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(place) }, enabled = share != null && !already) {
                Text(if (place.sub.isEmpty()) "Choisir ce partage" else "Choisir ce dossier")
            }
        },
        dismissButton = {
            Row {
                val up = when {
                    place.sub.isNotEmpty() -> place.copy(sub = place.sub.substringBeforeLast('\\', ""))
                    share != null -> PickPlace(place.source.takeIf { several })
                    place.source != null -> PickPlace()
                    else -> null
                }
                if (up != null) TextButton(onClick = { place = up }) { Text("Remonter") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}

/** The Perso tab behind its PIN; the fingerprint as a shortcut when allowed. */
@Composable
private fun PersoLockScreen(store: io.github.mkdevtests.umbra.perso.PersoStore) {
    val context = LocalContext.current
    val lock by store.lock.collectAsState()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val canFingerprint = lock.fingerprint && fingerprintAvailable(context)
    LaunchedEffect(Unit) { if (canFingerprint) askFingerprint(context, store::unlockByFingerprint) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(NyxaraIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Perso est verrouillé", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        PinDots(pin.length, wrong)
        Spacer(Modifier.height(16.dp))
        PinPad(
            onDigit = { digit ->
                wrong = false
                if (pin.length < MAX_PIN) pin += digit
                // Opens as soon as the code is right; wrong once all its digits are in.
                when {
                    pin.length >= MIN_PIN && store.unlock(pin) -> pin = ""
                    pin.length == MAX_PIN -> {
                        wrong = true
                        pin = ""
                    }
                }
            },
            onErase = { pin = pin.dropLast(1); wrong = false },
            onValidate = { if (!store.unlock(pin)) wrong = true; pin = "" },
        )
        if (canFingerprint) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { askFingerprint(context, store::unlockByFingerprint) }) { Text("Utiliser l'empreinte") }
        }
    }
}

const val MIN_PIN = 4
const val MAX_PIN = 8

@Composable
fun PinDots(count: Int, wrong: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(20.dp)) {
        if (wrong) Text("Code incorrect", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        else repeat(count) {
            Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
fun PinPad(onDigit: (Char) -> Unit, onErase: () -> Unit, onValidate: () -> Unit) {
    val rows = listOf("123", "456", "789", "<0>")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { key ->
                    val label = when (key) { '<' -> "⌫"; '>' -> "OK"; else -> key.toString() }
                    Box(
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant).focusRing(RoundedCornerShape(50))
                            .clickable { when (key) { '<' -> onErase(); '>' -> onValidate(); else -> onDigit(key) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}

/** A fingerprint (or another strong-enough biometric) is set up on the device. */
fun fingerprintAvailable(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return false
    val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        manager.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
    } else {
        @Suppress("DEPRECATION")
        manager.canAuthenticate()
    }
    return result == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
}

/** Asks for the fingerprint; [onSuccess] when recognized, nothing else (the PIN stays there). */
fun askFingerprint(context: android.content.Context, onSuccess: () -> Unit) {
    val executor = context.mainExecutor
    val prompt = android.hardware.biometrics.BiometricPrompt.Builder(context)
        .setTitle("Déverrouiller Perso")
        .setNegativeButton("Code", executor) { _, _ -> }
        .build()
    runCatching {
        prompt.authenticate(
            android.os.CancellationSignal(),
            executor,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", bytes / (1L shl 30).toDouble())
    else -> String.format(Locale.FRANCE, "%d Mo", bytes / (1L shl 20))
}

/** "1 h 05", "23 min", "45 s". */
private fun lengthLabel(seconds: Double): String {
    val s = seconds.toInt()
    return when {
        s >= 3600 -> "${s / 3600} h ${"%02d".format(s % 3600 / 60)}"
        s >= 60 -> "${s / 60} min"
        else -> "$s s"
    }
}
