package io.github.mkdevtests.umbra.ui.settings

import io.github.mkdevtests.umbra.ui.theme.focusRing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.browse.BrowserViewModel
import io.github.mkdevtests.umbra.nas.FolderRole
import io.github.mkdevtests.umbra.nas.looksLikeDocumentaries
import io.github.mkdevtests.umbra.nas.roleOf
import io.github.mkdevtests.umbra.nas.within
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.ScreenTitle
import kotlinx.coroutines.launch

/** A row of the tree: a folder ([sub] of [share], "" for the share itself), how deep, its role. */
private class FolderRow(
    val share: String,
    val sub: String,
    val name: String,
    val depth: Int,
    val role: FolderRole,
    val canOpen: Boolean,
    /** Inside a documentary folder: it can't be Bibliothèque on its own. */
    val inDocumentaries: Boolean,
)

private fun keyOf(share: String, sub: String) = if (sub.isEmpty()) share else "$share\\$sub"

/**
 * Every share of a NAS as a tree, read by the library or not, each folder
 * with its role: Bibliothèque (Films, Séries), Documentaires (the Docs tab),
 * Perso, or Non suivi; subfolders follow their folder. A folder named like
 * documentaries is offered as such in one touch.
 */
@Composable
fun FoldersScreen(source: NasSource, viewModel: BrowserViewModel, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as io.github.mkdevtests.umbra.NyxaraApp
    val settings by app.settings.settings.collectAsState()
    val documentaries = settings.documentaryFolders
    val sources by viewModel.sourceList.collectAsState()
    val current = sources.firstOrNull { it.id == source.id } ?: source
    val scope = rememberCoroutineScope()
    var shares by remember { mutableStateOf<List<String>?>(null) }
    // "share\sub" → its subfolders; absent: not asked yet, null: loading.
    val children = remember { mutableStateMapOf<String, List<String>?>() }
    val expanded = remember { mutableStateListOf<String>() }
    var dismissed by rememberSaveable { mutableStateOf(listOf<String>()) }

    fun load(share: String, sub: String) {
        val key = keyOf(share, sub)
        if (key in children) return
        children[key] = null
        scope.launch { children[key] = viewModel.subfoldersOf(current, share, sub) }
    }

    fun toggle(share: String, sub: String) {
        val key = keyOf(share, sub)
        if (key in expanded) expanded.remove(key) else {
            expanded.add(key)
            load(share, sub)
        }
    }

    // Each share's first level is read at once: a "Documentaires" folder in it is spotted.
    LaunchedEffect(current.id) {
        val list = viewModel.sharesOf(current)
        shares = list
        list.forEach { load(it, "") }
    }

    /** The folders beside the way down to [sub]: left out when its share comes in for it alone. */
    fun siblingsOf(share: String, sub: String): List<String> {
        if (sub.isEmpty()) return emptyList()
        val parts = sub.split('\\')
        return parts.indices.flatMap { i ->
            val parent = parts.take(i).joinToString("\\")
            children[keyOf(share, parent)].orEmpty().filter { it != parts[i] }.map { if (parent.isEmpty()) it else "$parent\\$it" }
        }
    }

    fun setRole(share: String, sub: String, role: FolderRole) {
        scope.launch { viewModel.setRole(current, share, sub, role, siblingsOf(share, sub)) }
    }

    fun pathOf(share: String, sub: String): String? = current.shares.firstOrNull { it.equals(share, ignoreCase = true) }
        ?.let(current::rootOf)?.let { if (sub.isEmpty()) it else "$it\\$sub" }

    val rows = buildList {
        fun visit(share: String, sub: String, name: String, depth: Int) {
            val role = roleOf(current, share, sub, documentaries)
            val followed = current.shares.any { it.equals(share, ignoreCase = true) }
            val canOpen = role == FolderRole.Library || role == FolderRole.Documentaries || !followed
            val path = pathOf(share, sub)
            val inDocs = path != null && documentaries.any { path.within(it) && !path.equals(it, ignoreCase = true) }
            add(FolderRow(share, sub, name, depth, role, canOpen, inDocs))
            if (canOpen && keyOf(share, sub) in expanded) {
                children[keyOf(share, sub)]?.forEach { visit(share, if (sub.isEmpty()) it else "$sub\\$it", it, depth + 1) }
            }
        }
        shares.orEmpty().forEach { visit(it, "", it, 0) }
    }

    // A folder named like documentaries, not one yet: offered in one touch.
    val spotted = shares.orEmpty().flatMap { share ->
        listOf(share to "") + children[keyOf(share, "")].orEmpty().map { share to it }
    }.firstOrNull { (share, sub) ->
        val name = if (sub.isEmpty()) share else sub
        looksLikeDocumentaries(name) && keyOf(share, sub) !in dismissed &&
            roleOf(current, share, sub, documentaries).let { it != FolderRole.Documentaries && it != FolderRole.Personal }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle("Dossiers suivis · ${current.label}", onBack)
        Text(
            "Chaque dossier a un rôle : Bibliothèque (Films et Séries), Documentaires (onglet Docs), Perso (onglet Perso) ou Non suivi. " +
                "Ses sous-dossiers le suivent. Touche un dossier pour voir ce qu'il contient, même dans un partage non suivi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        spotted?.let { (share, sub) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "« ${if (sub.isEmpty()) share else "$share › $sub"} » ressemble à des documentaires : le classer en Documentaires ?",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { dismissed = dismissed + keyOf(share, sub) }) { Text("Ignorer") }
                TextButton(onClick = { setRole(share, sub, FolderRole.Documentaries) }) { Text("Classer") }
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (shares == null) {
                item { Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
            items(rows, key = { keyOf(it.share, it.sub) }) { row ->
                val key = keyOf(row.share, row.sub)
                val open = key in expanded
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRing()
                        .clickable(enabled = row.canOpen) { toggle(row.share, row.sub) }
                        .padding(start = 12.dp + 22.dp * row.depth, end = 12.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when {
                            !row.canOpen -> Unit
                            open && children[key] == null -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else -> Icon(
                                NyxaraIcons.ChevronRight,
                                contentDescription = if (open) "Replier" else "Déplier",
                                modifier = Modifier.rotate(if (open) 90f else 0f),
                            )
                        }
                    }
                    Icon(
                        NyxaraIcons.Folder,
                        contentDescription = null,
                        tint = if (row.role == FolderRole.Off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        row.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).alpha(if (row.role == FolderRole.Off) 0.6f else 1f),
                    )
                    RolePicker(row) { role -> setRole(row.share, row.sub, role) }
                }
            }
            if (shares != null && rows.isEmpty()) {
                item { Text("Aucun dossier.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** The role of a folder, changed from a menu. */
@Composable
private fun RolePicker(row: FolderRow, onPick: (FolderRole) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                row.role.label,
                color = if (row.role == FolderRole.Off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FolderRole.entries.forEach { role ->
                val allowed = !(role == FolderRole.Library && row.inDocumentaries)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(role.label)
                            if (!allowed) Text("Dans un dossier de documentaires", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        open = false
                        if (role != row.role) onPick(role)
                    },
                    enabled = allowed,
                    trailingIcon = if (role == row.role) ({ Icon(NyxaraIcons.Check, contentDescription = null) }) else null,
                )
            }
        }
    }
}
