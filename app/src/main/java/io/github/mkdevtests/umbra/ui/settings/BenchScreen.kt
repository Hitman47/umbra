package io.github.mkdevtests.umbra.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.bench.BenchCheck
import io.github.mkdevtests.umbra.bench.BenchConfig
import io.github.mkdevtests.umbra.bench.Protocol
import io.github.mkdevtests.umbra.bench.ProtocolBench
import io.github.mkdevtests.umbra.bench.benchFiles
import io.github.mkdevtests.umbra.bench.videoFolders
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.player.PlayerActivity
import io.github.mkdevtests.umbra.ui.theme.ScreenTitle
import kotlinx.coroutines.launch

/**
 * Settings of the protocol test: the same folder in SMB, WebDAV and NFS.
 * "Vérifier" reads one file in each; "Lancer" plays a few in each, measured.
 */
@Composable
fun BenchScreen(bench: ProtocolBench, library: Library, onOpenMeasures: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(bench.load()) }
    var password by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf<BenchCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    val folders = remember(library) { videoFolders(library) }
    val count = remember(library, config.smbFolder) { benchFiles(library, config.smbFolder, Int.MAX_VALUE, 0).size }
    val width = Modifier.widthIn(max = 640.dp).fillMaxWidth()

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle("Test des protocoles", onBack)
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Les mêmes vidéos sont lues en SMB, WebDAV et NFS par le lecteur : ouverture, trois sauts, quelques secondes de lecture. " +
                    "Les résultats s'ajoutent aux mesures de lecture, une ligne par protocole.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = width,
            )
            FolderField(config.smbFolder, folders, width) { config = config.copy(smbFolder = it); checks = null }
            Text("$count vidéo${if (count > 1) "s" else ""} de la bibliothèque dans ce dossier", style = MaterialTheme.typography.bodySmall)

            SectionLabel("WebDAV")
            Field("URL du même dossier", config.webdavUrl, width, KeyboardType.Uri) { config = config.copy(webdavUrl = it); checks = null }
            Field("Compte (vide : celui du NAS en SMB)", config.webdavUser, width) { config = config.copy(webdavUser = it); checks = null }
            if (config.webdavUser.isNotBlank()) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; checks = null },
                    label = { Text("Mot de passe WebDAV") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = width,
                )
            }

            SectionLabel("NFS")
            Field("Serveur", config.nfsServer, width, KeyboardType.Uri) { config = config.copy(nfsServer = it); checks = null }
            Field("Dossier exporté", config.nfsExport, width) { config = config.copy(nfsExport = it); checks = null }
            Text(
                "L'export doit accepter les ports non privilégiés (option « insecure ») : une app Android ne peut pas en ouvrir d'autres.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = width,
            )

            SectionLabel("Vidéos testées")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5).forEach { n ->
                    FilterChip(selected = config.files == n, onClick = { config = config.copy(files = n) }, label = { Text("$n vidéos") })
                }
            }

            checks?.let { result ->
                Column(modifier = width, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.file?.let {
                        Text("Vidéo cherchée : ${it.replace("\\", " › ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    result.problems.forEach { (protocol, problem) ->
                        Text(
                            if (problem == null) "✓  ${protocol.label} : accessible" else "✗  ${protocol.label} : $problem",
                            color = if (problem == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    result.folder?.let { folder ->
                        Button(onClick = {
                            config = config.copy(smbFolder = folder)
                            bench.save(config)
                            checks = null
                        }) { Text("Prendre « ${folder.ifEmpty { "racine" }} » comme dossier SMB") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        checking = true
                        bench.save(config)
                        scope.launch {
                            checks = bench.check(config, password)
                            checking = false
                        }
                    },
                    enabled = !checking && count > 0,
                ) { Text(if (checking) "Vérification…" else "Vérifier l'accès") }
                Button(
                    onClick = {
                        bench.save(config)
                        context.startActivity(PlayerActivity.intent(context, bench.queue(config, password)))
                    },
                    enabled = count > 0,
                ) { Text("Lancer le test") }
            }
            TextButton(onClick = onOpenMeasures) { Text("Voir les mesures ›") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}

/** The SMB folder, typed or picked among the library's folders. */
@Composable
private fun FolderField(value: String, folders: List<Pair<String, Int>>, modifier: Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text("Dossier en SMB (partage\\dossier)") },
            singleLine = true,
            trailingIcon = { TextButton(onClick = { open = true }) { Text("▾") } },
            modifier = modifier,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            folders.forEach { (folder, n) ->
                DropdownMenuItem(text = { Text("$folder  ($n)") }, onClick = { open = false; onChange(folder) })
            }
        }
    }
}
