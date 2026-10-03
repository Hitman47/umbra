package io.github.mkdevtests.umbra.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.nas.Protocol
import io.github.mkdevtests.umbra.ui.theme.NyxaraLogo
import kotlinx.coroutines.launch

private enum class SetupStep { Login, Shares }

/**
 * NAS setup in two steps, like Infuse: address and login, then a checklist
 * of the shares the NAS offers. Share names are typed only when the NAS
 * refuses to list them. [onConnect] returns an error message, or null once connected.
 */
@Composable
fun SourceScreen(
    initial: NasSource?,
    onDiscover: suspend (protocol: Protocol, host: String, username: String, password: String) -> ShareDiscovery,
    onConnect: suspend (NasSource) -> String?,
    onCancel: (() -> Unit)?,
) {
    var step by rememberSaveable { mutableStateOf(SetupStep.Login) }
    var protocol by rememberSaveable { mutableStateOf(initial?.protocol ?: Protocol.Smb) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(initial?.host.orEmpty()) }
    var fallbackHost by rememberSaveable { mutableStateOf(initial?.fallbackHost.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var shares by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun discover() {
        busy = true
        error = null
        scope.launch {
            // Editing the same NAS keeps the previous choice; otherwise everything is included.
            val previous = initial?.takeIf { it.protocol == protocol && it.host.equals(host.trim(), ignoreCase = true) }?.shares.orEmpty()
            when (val result = onDiscover(protocol, host.trim(), username.trim(), password)) {
                is ShareDiscovery.Found -> {
                    shares = result.shares
                    selected = result.shares.filter { it in previous }.ifEmpty { result.shares }
                    manual = false
                    step = SetupStep.Shares
                }
                ShareDiscovery.Manual -> {
                    shares = previous
                    selected = previous
                    manual = true
                    step = SetupStep.Shares
                }
                is ShareDiscovery.Failed -> error = result.message
            }
            busy = false
        }
    }

    fun connect() {
        busy = true
        error = null
        scope.launch {
            // The id, root names and excluded folders of an edited source are kept: its titles and history stay its own.
            val source = NasSource(
                host.trim(), shares.filter { it in selected }, username.trim(), password, initial?.domain.orEmpty(),
                id = initial?.id.orEmpty(), name = name.trim(), roots = initial?.roots.orEmpty(), excluded = initial?.excluded.orEmpty(),
                personal = initial?.personal.orEmpty(),
                protocol = protocol,
                fallbackHost = if (protocol == Protocol.WebDav) "" else fallbackHost.trim(),
            )
            error = onConnect(source)
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NyxaraLogo(size = 40.dp, modifier = Modifier.padding(bottom = 12.dp))
        val width = Modifier.widthIn(max = 520.dp).fillMaxWidth()

        when (step) {
            SetupStep.Login -> {
                Text("Connexion au NAS", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Protocol.entries.forEach { entry ->
                        FilterChip(selected = entry == protocol, onClick = { protocol = entry; error = null }, label = { Text(entry.label) })
                    }
                }
                Text(
                    when (protocol) {
                        Protocol.Smb -> "Le partage de fichiers de Windows, proposé par tous les NAS."
                        Protocol.Nfs -> "Sans compte. Dans /etc/exports, l'export doit avoir l'option « insecure »."
                        Protocol.WebDav -> "Le lecteur lit les vidéos en HTTP, directement. L'URL est celle du dossier qui contient tes dossiers de vidéos."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = width,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom (facultatif)") },
                    placeholder = { Text(host.ifBlank { "Zima salon" }) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = width,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(if (protocol == Protocol.WebDav) "URL WebDAV" else "Adresse du NAS") },
                    placeholder = { Text(if (protocol == Protocol.WebDav) "http://192.168.1.20:5005/" else "192.168.1.20 ou nom Tailscale") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = width,
                )
                if (protocol != Protocol.WebDav) {
                    OutlinedTextField(
                        value = fallbackHost,
                        onValueChange = { fallbackHost = it },
                        label = { Text("Adresse Tailscale (facultatif)") },
                        placeholder = { Text("100.x.y.z ou nas.tailnet.ts.net") },
                        supportingText = { Text("Utilisée quand l'adresse locale ne répond pas : une seule source chez toi et ailleurs.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = width,
                    )
                }
                if (protocol != Protocol.Nfs) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Utilisateur (vide = invité)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = width,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Mot de passe") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!busy && host.isNotBlank()) discover() }),
                        modifier = width,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onCancel != null) TextButton(onClick = onCancel, enabled = !busy) { Text("Annuler") }
                    Button(onClick = ::discover, enabled = !busy && host.isNotBlank()) {
                        if (busy) Spinner() else Text("Continuer")
                    }
                }
            }

            SetupStep.Shares -> {
                Text(
                    when (protocol) {
                        Protocol.Smb -> "Partages à inclure dans la bibliothèque"
                        Protocol.Nfs -> "Exports à inclure dans la bibliothèque"
                        Protocol.WebDav -> "Dossiers à inclure dans la bibliothèque"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (manual) {
                        when (protocol) {
                            Protocol.Smb -> "Ce NAS ne donne pas la liste de ses partages : ajoute leur nom tel qu'il apparaît dans ZimaOS."
                            Protocol.Nfs -> "Le NAS ne donne pas la liste de ses exports : ajoute le chemin exporté (/media/sdb1/Vidéos/Films)."
                            Protocol.WebDav -> "Aucun dossier trouvé à cette URL : ajoute le nom d'un dossier."
                        }
                    } else {
                        "Nyxara trouve tout seul les films et les épisodes, quel que soit le rangement des dossiers."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = width,
                )
                Column(modifier = width) {
                    shares.forEach { share ->
                        val checked = share in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = if (checked) selected - share else selected + share },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                            Text(share, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (manual) {
                    var name by rememberSaveable { mutableStateOf("") }
                    fun add() {
                        val share = name.trim().let { if (protocol == Protocol.Nfs) "/" + it.trim('/') else it.trim('/', '\\') }
                        if (share.isNotEmpty() && share !in shares) {
                            shares = shares + share
                            selected = selected + share
                        }
                        name = ""
                    }
                    Row(modifier = width, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Nom d'un partage") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { add() }),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = ::add, enabled = name.isNotBlank()) { Text("Ajouter") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { step = SetupStep.Login; error = null }, enabled = !busy) { Text("Retour") }
                    Button(onClick = ::connect, enabled = !busy && selected.any { it in shares }) {
                        if (busy) Spinner() else Text("Terminer")
                    }
                }
            }
        }
    }
}

@Composable
private fun Spinner() = CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
