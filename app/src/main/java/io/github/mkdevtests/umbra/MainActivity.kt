package io.github.mkdevtests.umbra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.player.PlayerActivity
import io.github.mkdevtests.umbra.ui.theme.UmbraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UmbraTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen()
                }
            }
        }
    }
}

// Public sample, used to check playback before the NAS is wired in.
private const val SAMPLE_URL = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_5MB.mp4"

/** Prototype home: play a URL or a file picked on the device. */
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf(SAMPLE_URL) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) context.startActivity(PlayerActivity.intent(context, uri.toString(), uri.lastPathSegment))
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Umbra", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Text("v${BuildConfig.VERSION_NAME} · prototype lecteur", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Adresse de la vidéo") },
            singleLine = true,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        )
        Button(onClick = { context.startActivity(PlayerActivity.intent(context, url.trim())) }, enabled = url.isNotBlank()) {
            Text("Lire l'adresse")
        }
        OutlinedButton(onClick = { pickFile.launch(arrayOf("video/*")) }) {
            Text("Choisir un fichier sur l'appareil")
        }
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    UmbraTheme { HomeScreen() }
}
