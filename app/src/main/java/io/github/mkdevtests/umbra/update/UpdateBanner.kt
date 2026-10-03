package io.github.mkdevtests.umbra.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A slim bar above the library when a new version is out; nothing otherwise. */
@Composable
fun UpdateBanner(updater: Updater) {
    val state by updater.state.collectAsState()
    var showNotes by rememberSaveable { mutableStateOf(false) }
    val release = when (val current = state) {
        UpdateState.None -> return
        is UpdateState.Available -> current.release
        is UpdateState.Downloading -> current.release
        is UpdateState.Installing -> current.release
        is UpdateState.Failed -> current.release
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val text = when (val current = state) {
                    is UpdateState.Downloading -> "Téléchargement de Nyxara ${release.version}… ${(current.progress * 100).toInt()} %"
                    is UpdateState.Installing -> "Installation de Nyxara ${release.version}…"
                    is UpdateState.Failed -> current.message
                    else -> "Nyxara ${release.version} est disponible"
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (state is UpdateState.Available || state is UpdateState.Failed) {
                    if (release.notes.isNotEmpty()) TextButton(onClick = { showNotes = true }) { Text("Nouveautés") }
                    TextButton(onClick = { updater.install(release) }) {
                        Text(if (state is UpdateState.Failed) "Réessayer" else "Installer")
                    }
                }
            }
            (state as? UpdateState.Downloading)?.let {
                LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
            }
        }
    }

    if (showNotes) {
        AlertDialog(
            onDismissRequest = { showNotes = false },
            title = { Text("Nyxara ${release.version}") },
            text = {
                Text(release.notes, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { showNotes = false; updater.install(release) }) { Text("Installer") }
            },
            dismissButton = { TextButton(onClick = { showNotes = false }) { Text("Plus tard") } },
        )
    }
}
