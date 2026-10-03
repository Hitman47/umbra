package io.github.mkdevtests.umbra.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.download.DownloadState
import io.github.mkdevtests.umbra.library.formatSize
import io.github.mkdevtests.umbra.ui.library.LibraryViewModel
import io.github.mkdevtests.umbra.ui.library.downloadLabel
import io.github.mkdevtests.umbra.ui.theme.ScreenTitle

/** The videos copied to the device: progress, room taken, delete or try again. */
@Composable
fun DownloadsScreen(viewModel: LibraryViewModel, onBack: () -> Unit) {
    val all by viewModel.downloads.collectAsState()
    // Perso's own are listed in the Perso tab, behind its lock.
    val downloads = all.filterNot { it.key.startsWith("perso:") }
    val used = remember(downloads) { downloads.sumOf { if (it.state == DownloadState.Done) it.size else it.done } }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle("Téléchargements", onBack)
        Text(
            "${formatSize(used)} sur la tablette · libre : ${formatSize(viewModel.freeSpace())}. Lus même sans le NAS ; supprimés avec l'application.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (downloads.isEmpty()) {
            Text(
                "Aucun téléchargement. Sur la fiche d'un film : Télécharger ; pour un épisode : appui long.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(downloads, key = { it.key }) { download ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(download.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${downloadLabel(download)} · ${formatSize(download.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (download.state == DownloadState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (download.state == DownloadState.Running || download.state == DownloadState.Queued) {
                            LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (download.state == DownloadState.Failed) TextButton(onClick = { viewModel.retryDownload(download.key) }) { Text("Réessayer") }
                    TextButton(onClick = { viewModel.removeDownload(download.key) }) { Text(if (download.state == DownloadState.Done) "Supprimer" else "Annuler") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            }
        }
    }
}
