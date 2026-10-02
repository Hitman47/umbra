package io.github.mkdevtests.umbra.browse

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import io.github.mkdevtests.umbra.nas.SmbSource
import kotlinx.coroutines.launch

/** NAS connection form. [onConnect] returns an error message, or null once connected. */
@Composable
fun SourceScreen(
    initial: SmbSource?,
    onConnect: suspend (SmbSource) -> String?,
    onCancel: (() -> Unit)?,
) {
    var host by rememberSaveable { mutableStateOf(initial?.host.orEmpty()) }
    var share by rememberSaveable { mutableStateOf(initial?.share.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        connecting = true
        error = null
        scope.launch {
            error = onConnect(SmbSource(host.trim(), share.trim(), username.trim(), password))
            connecting = false
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
        Text("Umbra", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Text("Connexion au NAS (SMB)", style = MaterialTheme.typography.titleMedium)

        val fieldModifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Adresse du NAS") },
            placeholder = { Text("192.168.1.20 ou nom Tailscale") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = fieldModifier,
        )
        OutlinedTextField(
            value = share,
            onValueChange = { share = it },
            label = { Text("Partage") },
            placeholder = { Text("Media") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = fieldModifier,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Utilisateur (vide = invité)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = fieldModifier,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Mot de passe") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = fieldModifier,
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onCancel != null) TextButton(onClick = onCancel, enabled = !connecting) { Text("Annuler") }
            Button(onClick = ::connect, enabled = !connecting && host.isNotBlank() && share.isNotBlank()) {
                if (connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Se connecter")
                }
            }
        }
    }
}
