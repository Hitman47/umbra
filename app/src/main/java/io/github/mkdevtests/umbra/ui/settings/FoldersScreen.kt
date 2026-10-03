package io.github.mkdevtests.umbra.ui.settings

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.browse.BrowserViewModel
import io.github.mkdevtests.umbra.nas.NasEntry
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.ScreenTitle
import kotlinx.coroutines.launch

/** A row of the tree: a folder, how deep, whether it is followed. */
private class FolderRow(val path: String, val name: String, val depth: Int, val excluded: Boolean)

/**
 * The folders of a source as a tree, each with a switch, like Infuse's
 * folder settings: switched off, a folder leaves the library (not scanned,
 * browsed nor played); switched on again, the next scan finds it back.
 */
@Composable
fun FoldersScreen(
    source: NasSource,
    viewModel: BrowserViewModel,
    onExcluded: () -> Unit,
    onIncluded: () -> Unit,
    onBack: () -> Unit,
) {
    val sources by viewModel.sourceList.collectAsState()
    val current = sources.firstOrNull { it.id == source.id } ?: source
    val scope = rememberCoroutineScope()
    // Folder → its subfolders; absent: not asked yet, null: loading.
    val children = remember { mutableStateMapOf<String, List<NasEntry>?>() }
    val expanded = remember { mutableStateListOf<String>() }

    fun toggle(path: String) {
        if (path in expanded) {
            expanded.remove(path)
            return
        }
        expanded.add(path)
        if (path !in children) {
            children[path] = null
            scope.launch { children[path] = viewModel.folders(path) }
        }
    }

    val rows = buildList {
        fun visit(path: String, name: String, depth: Int) {
            val excluded = current.isExcluded(path)
            add(FolderRow(path, name, depth, excluded))
            if (!excluded && path in expanded) children[path]?.forEach { visit(it.path, it.name, depth + 1) }
        }
        viewModel.rootsOf(current).forEach { visit(it, it, 0) }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle("Dossiers suivis · ${current.label}", onBack)
        Text(
            "Désactive un dossier pour le retirer de la bibliothèque : il n'est plus analysé, affiché ni lu. " +
                "Touche un dossier pour voir ce qu'il contient.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.path }) { row ->
                val open = row.path in expanded
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !row.excluded) { toggle(row.path) }
                        .padding(start = 12.dp + 22.dp * row.depth, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        when {
                            row.excluded -> Unit
                            open && children[row.path] == null -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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
                        tint = if (row.excluded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        row.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).alpha(if (row.excluded) 0.5f else 1f),
                    )
                    // A whole share is followed or not from the source itself (Modifier).
                    if (row.depth > 0) {
                        Switch(
                            checked = !row.excluded,
                            onCheckedChange = { follow ->
                                if (follow) {
                                    viewModel.include(current, row.path)
                                    onIncluded()
                                } else {
                                    expanded.remove(row.path)
                                    viewModel.exclude(row.path)
                                    onExcluded()
                                }
                            },
                        )
                    }
                }
            }
            if (children.values.all { it != null } && rows.isEmpty()) {
                item { Text("Aucun dossier.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
