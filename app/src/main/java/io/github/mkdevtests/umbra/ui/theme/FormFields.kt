package io.github.mkdevtests.umbra.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.settings.Address
import io.github.mkdevtests.umbra.settings.parseAddress
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * A text field a remote can leave: ▲ ▼ go to the field above or below (a
 * text field keeps the arrows for itself otherwise, and the remote is stuck
 * in it), and the field shown in full once it has the focus.
 */
fun Modifier.remoteFriendly(): Modifier = composed {
    val focus = LocalFocusManager.current
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val tv = isTv()
    var typing by remember { mutableStateOf(false) }
    this
        .bringIntoViewRequester(bring)
        .onFocusEvent { state ->
            if (state.isFocused) {
                scope.launch { bring.bringIntoView() }
                // On a TV, going over a field doesn't open the keyboard: OK does.
                if (tv && !typing) scope.launch { repeat(4) { keyboard?.hide(); delay(60) } }
            } else {
                typing = false
            }
        }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (tv && !typing) {
                    typing = true
                    keyboard?.show()
                    true
                } else {
                    false
                }
                Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                else -> false
            }
        }
}

/** A one-line field of a form: the keyboard's ↵ goes to the next one ([last]: closes it), the remote can leave it. A secret one can be shown, to check it. */
@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    last: Boolean = false,
) {
    val focus = LocalFocusManager.current
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        trailingIcon = if (secret) ({ androidx.compose.material3.TextButton(onClick = { shown = !shown }) { Text(if (shown) "Masquer" else "Afficher") } }) else null,
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { focus.clearFocus() }),
        modifier = modifier.remoteFriendly(),
    )
}

/**
 * A service's address without typing "http://": http or https in one touch,
 * the host (an IP of the NAS in one touch, from [suggestions]), the port
 * ([defaultPort] when left empty).
 */
@Composable
fun AddressField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    defaultPort: Int?,
    suggestions: List<Pair<String, String>> = emptyList(),
    last: Boolean = false,
) {
    var address by remember { mutableStateOf(parseAddress(value)) }
    fun update(changed: Address) {
        address = changed
        onValueChange(changed.url(defaultPort))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            FilterChip(selected = !address.secure, onClick = { update(address.copy(secure = false)) }, label = { Text("http") })
            FilterChip(selected = address.secure, onClick = { update(address.copy(secure = true)) }, label = { Text("https") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField(
                address.host, { typed ->
                    // "192.168.1.10:9696" or "http://…" typed whole: taken apart.
                    val parsed = parseAddress(if ("://" in typed) typed else "http://$typed")
                    update(address.copy(host = parsed.host, port = parsed.port.ifEmpty { address.port }, secure = if ("://" in typed) parsed.secure else address.secure))
                }, "Adresse IP ou nom",
                Modifier.weight(1f), placeholder = "192.168.1.10", keyboardType = KeyboardType.Uri,
            )
            FormField(
                address.port, { update(address.copy(port = it.filter(Char::isDigit))) }, "Port",
                Modifier.width(110.dp), placeholder = defaultPort?.toString(), keyboardType = KeyboardType.Number, last = last,
            )
        }
        val offered = suggestions.filter { (host, _) -> host.isNotBlank() && !host.equals(address.host, ignoreCase = true) }.distinctBy { it.first.lowercase() }
        if (offered.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offered.forEach { (host, why) ->
                    AssistChip(onClick = { update(address.copy(host = host)) }, label = { Text("$why : $host") })
                }
            }
        }
    }
}
