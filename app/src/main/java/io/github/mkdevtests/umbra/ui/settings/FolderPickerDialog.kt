package io.github.mkdevtests.umbra.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons

/** Chooses a folder of the NAS, from its shares down; [listFolders] gives the folders below a path ("" for the shares). */
@Composable
fun FolderPickerDialog(title: String, listFolders: suspend (String) -> List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(path) {
        folders = null
        folders = runCatching { listFolders(path) }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (path.isEmpty()) title else path.substringAfterLast('\\'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (path.isNotEmpty()) Text(path.replace("\\", " › "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                    val list = folders
                    when {
                        list == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        list.isEmpty() -> Text("Aucun sous-dossier.", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> LazyColumn {
                            items(list, key = { it }) { folder ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { path = folder }.padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(NyxaraIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Text(folder.substringAfterLast('\\'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }, enabled = path.isNotEmpty()) { Text("Choisir ce dossier") } },
        dismissButton = {
            Row {
                if (path.isNotEmpty()) TextButton(onClick = { path = if ('\\' in path) path.substringBeforeLast('\\') else "" }) { Text("Remonter") }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}
