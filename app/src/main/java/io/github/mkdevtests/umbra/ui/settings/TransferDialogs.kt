package io.github.mkdevtests.umbra.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.transfer.ReceiveState
import io.github.mkdevtests.umbra.transfer.Sealed
import io.github.mkdevtests.umbra.transfer.TRANSFER_PORT
import io.github.mkdevtests.umbra.transfer.TransferReceiver
import io.github.mkdevtests.umbra.transfer.TransferSender
import io.github.mkdevtests.umbra.ui.theme.FormField
import io.github.mkdevtests.umbra.ui.theme.focusRing
import kotlinx.coroutines.launch

/** This device waits for another's settings while the dialog is open: a code and an address on screen. */
@Composable
fun ReceiveSettingsDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as NyxaraApp
    val receiver = remember { TransferReceiver(app) }
    DisposableEffect(receiver) {
        receiver.start()
        onDispose { receiver.stop() }
    }
    val state by receiver.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recevoir les réglages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val current = state) {
                    is ReceiveState.Waiting -> {
                        Text("Sur l'autre appareil : Réglages › Sauvegarde › Envoyer, puis choisis celui-ci (ou tape son adresse) et ce code :")
                        Text(Sealed.spaced(current.code), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        val port = if (current.port == TRANSFER_PORT) "" else ":${current.port}"
                        Text(
                            if (current.addresses.isEmpty()) "Adresse inconnue : vérifie le réseau." else "Adresse : " + current.addresses.joinToString(" ou ") { it + port },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Arrivent : historique, corrections, sources avec leurs mots de passe, recherche et catalogue avec leurs clés. Sur le réseau de la maison seulement, chiffré avec ce code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ReceiveState.Done -> Text(current.message)
                    is ReceiveState.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
                    ReceiveState.Idle -> Text("…")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(RoundedCornerShape(50))) {
                Text(if (state is ReceiveState.Waiting) "Annuler" else "Fermer")
            }
        },
        dismissButton = if (state is ReceiveState.Failed || state is ReceiveState.Done) {
            { TextButton(onClick = receiver::start, modifier = Modifier.focusRing(RoundedCornerShape(50))) { Text("Recommencer") } }
        } else null,
    )
}

/** Sends this device's settings to one waiting on the network (found by itself, or its address typed). */
@Composable
fun SendSettingsDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as NyxaraApp
    val sender = remember { TransferSender(app) }
    DisposableEffect(sender) {
        sender.discover()
        onDispose { sender.stop() }
    }
    val peers by sender.peers.collectAsState()
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Envoyer les réglages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Sur l'autre appareil (la TV) : Réglages › Sauvegarde › Recevoir. Il apparaît ici ; sinon, tape l'adresse qu'il affiche.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                peers.forEach { peer ->
                    val address = "${peer.host}:${peer.port}"
                    TextButton(onClick = { host = address }, modifier = Modifier.focusRing(RoundedCornerShape(50))) {
                        Text((if (host == address) "✓ " else "") + "${peer.name} · ${peer.host}")
                    }
                }
                if (peers.isEmpty()) Text("Recherche des appareils…", style = MaterialTheme.typography.bodySmall)
                FormField(host, { host = it; result = null }, "Adresse", modifier = Modifier.fillMaxWidth(), placeholder = "192.168.1.42")
                FormField(
                    code, { code = it; result = null }, "Code (${Sealed.CODE_DIGITS} chiffres)",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Number,
                    last = true,
                )
                result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && host.isNotBlank() && Sealed.normalized(code).length == Sealed.CODE_DIGITS,
                onClick = {
                    sending = true
                    result = "Envoi…"
                    scope.launch {
                        result = sender.send(host, code)
                        sending = false
                    }
                },
                modifier = Modifier.focusRing(RoundedCornerShape(50)),
            ) { Text("Envoyer") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing(RoundedCornerShape(50))) { Text("Fermer") } },
    )
}
