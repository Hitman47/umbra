package io.github.mkdevtests.umbra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mkdevtests.umbra.browse.BrowserScreen
import io.github.mkdevtests.umbra.browse.BrowserViewModel
import io.github.mkdevtests.umbra.browse.SourceScreen
import io.github.mkdevtests.umbra.player.PlayerActivity
import io.github.mkdevtests.umbra.ui.theme.UmbraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UmbraTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    UmbraRoot()
                }
            }
        }
    }
}

/** NAS setup until a source works, then the folder browser. */
@Composable
private fun UmbraRoot(viewModel: BrowserViewModel = viewModel()) {
    val context = LocalContext.current
    var editingSource by rememberSaveable { mutableStateOf(!viewModel.hasSource) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) context.startActivity(PlayerActivity.intent(context, uri.toString(), uri.lastPathSegment))
    }

    if (editingSource) {
        SourceScreen(
            initial = viewModel.source,
            onConnect = { source ->
                viewModel.connect(source).also { error -> if (error == null) editingSource = false }
            },
            onCancel = if (viewModel.hasSource) ({ editingSource = false }) else null,
        )
    } else {
        BrowserScreen(
            viewModel = viewModel,
            onEditSource = { editingSource = true },
            onPickLocalFile = { pickFile.launch(arrayOf("video/*")) },
        )
    }
}
