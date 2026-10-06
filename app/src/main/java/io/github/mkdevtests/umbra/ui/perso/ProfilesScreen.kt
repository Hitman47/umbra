package io.github.mkdevtests.umbra.ui.perso

import androidx.compose.foundation.shape.CircleShape
import io.github.mkdevtests.umbra.ui.theme.remoteFriendly
import io.github.mkdevtests.umbra.nas.within
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import io.github.mkdevtests.umbra.library.byTitle
import io.github.mkdevtests.umbra.library.letterPositions
import io.github.mkdevtests.umbra.library.LETTERS
import io.github.mkdevtests.umbra.ui.theme.INDEX_BAR_MIN_ITEMS
import io.github.mkdevtests.umbra.ui.theme.indexBarWidth
import io.github.mkdevtests.umbra.ui.theme.IndexBar
import io.github.mkdevtests.umbra.ui.theme.scaled
import io.github.mkdevtests.umbra.ui.theme.LocalCardScale
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.catalog.CatalogIndex
import io.github.mkdevtests.umbra.catalog.CatalogPerson
import io.github.mkdevtests.umbra.catalog.CatalogProfile
import io.github.mkdevtests.umbra.catalog.CatalogVideo
import io.github.mkdevtests.umbra.player.PersoRequest
import io.github.mkdevtests.umbra.player.PlayerActivity
import io.github.mkdevtests.umbra.ui.theme.GlassButton
import io.github.mkdevtests.umbra.ui.theme.GlowButton
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.focusRing
import java.util.Locale

/** The id of the "Groupes" card: the videos of no profile. */
private const val GROUPS = ""

private enum class ProfileSort(val label: String) { Name("Nom"), Count("Vidéos"), Size("Taille") }

/** The external catalogue's profiles, from the copy on the device; a profile opens its page. */
@Composable
fun ProfilesScreen(app: NyxaraApp) {
    val index by app.catalog.index.collectAsState()
    val status by app.catalog.status.collectAsState()
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val catalog = index
    if (catalog == null) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (status.syncing) CircularProgressIndicator()
            else Text(status.error ?: "Pas encore synchronisé : Réglages › Catalogue externe.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    opened?.let { id ->
        BackHandler { opened = null }
        ProfilePage(app, catalog, id, onBack = { opened = null })
        return
    }
    ProfileGrid(app, catalog, onOpen = { opened = it })
}

@Composable
private fun ProfileGrid(app: NyxaraApp, catalog: CatalogIndex, onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var followed by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(ProfileSort.Name) }
    var categoryMenu by remember { mutableStateOf(false) }
    val shown = remember(catalog, query, category, followed, sort) {
        val needle = query.trim().lowercase()
        catalog.people
            .filter { (needle.isEmpty() || needle in it.name.lowercase()) && (category == null || category in it.categories) && (!followed || it.followed) }
            .let { list ->
                when (sort) {
                    ProfileSort.Name -> byTitle(list) { it.name }
                    ProfileSort.Count -> list.sortedByDescending { it.count }
                    ProfileSort.Size -> list.sortedByDescending { it.size }
                }
            }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it }, singleLine = true, placeholder = { Text("Rechercher un profil") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).remoteFriendly(),
        )
        FlowRow(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                FilterChip(selected = category != null, onClick = { categoryMenu = true }, label = { Text(category ?: "Catégorie") })
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    DropdownMenuItem(text = { Text("Toutes") }, onClick = { category = null; categoryMenu = false })
                    catalog.categories.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { category = name; categoryMenu = false }) }
                }
            }
            FilterChip(selected = followed, onClick = { followed = !followed }, label = { Text("Suivis") })
            ProfileSort.entries.forEach { entry -> FilterChip(selected = sort == entry, onClick = { sort = entry }, label = { Text(entry.label) }) }
        }
        Text(
            "${shown.size} profils",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val scale = LocalCardScale.current
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        val unfiltered = query.isBlank() && category == null && !followed
        // The videos of no profile: a card per folder, then the loose ones, before the profiles.
        val folders = if (unfiltered) catalog.folders else emptyList()
        val groups = catalog.loose.isNotEmpty() && unfiltered
        val before = (if (groups) 1 else 0) + folders.size
        val letters = remember(shown, sort) { if (sort == ProfileSort.Name && shown.size >= INDEX_BAR_MIN_ITEMS) letterPositions(shown.map { it.name }) else null }
        Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 150.dp * scale),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 16.dp + if (letters != null) indexBarWidth() else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp * scale),
            verticalArrangement = Arrangement.spacedBy(18.dp * scale),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(folders, key = { CatalogIndex.FOLDER + it.path }) { folder ->
                ProfileCard(null, folder.name, null, "${folder.videos.size} vidéos · ${size(folder.videos.sumOf { it.size })}", false) { onOpen(CatalogIndex.FOLDER + folder.path) }
            }
            if (groups) {
                item(key = "groups") {
                    ProfileCard(null, "Groupes", null, "${catalog.loose.size} vidéos · ${size(catalog.loose.sumOf { it.size })}", false) { onOpen(GROUPS) }
                }
            }
            items(shown, key = { it.id }) { person ->
                ProfileCard(
                    app.streamServer.remoteImageUrl("p/${person.id}/${person.photo}"), person.name, person.categories.firstOrNull(),
                    "${person.count} vidéos · ${size(person.size)}", person.followed,
                ) { onOpen(person.id) }
            }
        }
        letters?.let { positions ->
            IndexBar(
                LETTERS, positions,
                onJump = { position -> scope.launch { gridState.scrollToItem(position + before) } },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, bottom = 16.dp, end = 4.dp),
            )
        }
        }
    }
}

@Composable
private fun ProfileCard(picture: String?, name: String, category: String?, detail: String, followed: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.focusRing().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (picture != null) AsyncImage(model = picture, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (followed) {
                Icon(
                    NyxaraIcons.Star, contentDescription = "Suivi", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp),
                )
            }
        }
        val scale = LocalCardScale.current
        Text(name, style = MaterialTheme.typography.titleSmall.scaled(scale), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        category?.let { Text(it, style = MaterialTheme.typography.bodySmall.scaled(scale), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        Text(detail, style = MaterialTheme.typography.bodySmall.scaled(scale), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A profile's page: picture, facts, text, then its videos; a long press selects some to play. */
@Composable
private fun ProfilePage(app: NyxaraApp, catalog: CatalogIndex, id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val person: CatalogPerson? = catalog.byId[id]
    val folder = catalog.folders.firstOrNull { CatalogIndex.FOLDER + it.path == id }
    val videos: List<CatalogVideo> = remember(catalog, id) { catalog.videosFor(id).sortedBy { it.path.lowercase() } }
    val paths = remember(videos) { videos.mapNotNull(catalog::nasPath) }
    var profile by remember(id) { mutableStateOf<CatalogProfile?>(null) }
    LaunchedEffect(id) { if (person != null) profile = app.catalog.profile(id) }
    val progress by app.perso.progress.collectAsState()
    val infos by app.persoMedia.infos.collectAsState()
    var selected by remember(id) { mutableStateOf(emptySet<String>()) }
    var textOpen by remember(id) { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    LaunchedEffect(paths) { app.persoMedia.request(videos.mapNotNull { video -> catalog.nasPath(video)?.let { it to video.size } }.take(200)) }

    fun play(shuffle: Boolean, start: String? = null, selection: List<String>? = null) {
        context.startActivity(PlayerActivity.persoIntent(context, PersoRequest("profile:$id", shuffle, start, profile = id, selection = selection)))
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.focusRing(CircleShape)) { Icon(NyxaraIcons.Back, contentDescription = "Retour") }
                Text(person?.name ?: folder?.name ?: "Groupes", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (person != null) {
            item {
                Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage(
                        model = app.streamServer.remoteImageUrl("p/${person.id}/${person.photo}"), contentDescription = person.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(150.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (person.categories.isNotEmpty()) Text(person.categories.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        profile?.aliases?.takeIf { it.isNotEmpty() }?.let { Text("Aussi : " + it.joinToString(", "), style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            profile?.facts.orEmpty().forEach { (label, value) ->
                                Column(modifier = Modifier.width(150.dp)) {
                                    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(value, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            profile?.text?.let { text ->
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = if (textOpen) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { textOpen = !textOpen }) { Text(if (textOpen) "Moins" else "Lire plus") }
                    }
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${videos.size} vidéos · ${size(videos.sumOf { it.size })}", style = MaterialTheme.typography.titleMedium)
                if (catalog.data.root == null) Text("Dossier du catalogue introuvable dans Perso.", color = MaterialTheme.colorScheme.error)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selecting) {
                        GlowButton("Lire (${selected.size})", onClick = { play(false, selection = paths.filter { it in selected }) })
                        GlassButton("Aléatoire", onClick = { play(true, selection = paths.filter { it in selected }) }, icon = NyxaraIcons.Shuffle)
                        TextButton(onClick = { selected = emptySet() }) { Text("Annuler") }
                    } else if (paths.isNotEmpty()) {
                        GlowButton("Aléatoire", onClick = { play(true) }, icon = NyxaraIcons.Shuffle)
                        GlassButton("Dans l'ordre", onClick = { play(false) }, icon = NyxaraIcons.Play)
                    }
                }
                if (!selecting && paths.size > 1) Text("Appui long : choisir des vidéos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        items(videos, key = { it.path }) { video ->
            val path = catalog.nasPath(video)
            val image = video.images.firstOrNull()?.let(catalog::nasPath)
            val duration = path?.let { infos[it]?.duration ?: progress[it]?.duration }
            val seen = path?.let { progress[it] }
            val toggle = { if (path != null) selected = if (path in selected) selected - path else selected + path }
            Row(
                modifier = Modifier.fillMaxWidth().focusRing()
                    .combinedClickable(onLongClick = toggle, onClick = { if (selecting) toggle() else if (path != null) play(false, start = path) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selecting) Checkbox(checked = path in selected, onCheckedChange = { toggle() })
                Box(modifier = Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(if (seen?.watched == true) NyxaraIcons.Check else NyxaraIcons.Play, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (image != null) AsyncImage(model = app.streamServer.imageUrl(image), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(video.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(size(video.size), duration?.let { "${(it / 60).toInt()} min" }, "en cours".takeIf { seen?.inProgress == true }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", bytes / (1L shl 30).toDouble())
    else -> String.format(Locale.FRANCE, "%d Mo", bytes / (1L shl 20))
}
