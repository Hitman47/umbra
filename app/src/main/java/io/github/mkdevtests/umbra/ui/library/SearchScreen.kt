package io.github.mkdevtests.umbra.ui.library

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.library.Found
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.SearchKind
import io.github.mkdevtests.umbra.library.decadesOf
import io.github.mkdevtests.umbra.library.genresOf
import kotlinx.coroutines.delay

/** The "Recherche" tab: one field for titles, actors and directors, and filters to browse without typing. */
@Composable
fun SearchScreen(
    library: Library,
    viewModel: LibraryViewModel,
    onOpenMovie: (String) -> Unit,
    onOpenShow: (String) -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val query by viewModel.searchQuery.collectAsState()
    val filters by viewModel.searchFilters.collectAsState()
    val hidden by viewModel.hidden.collectAsState()
    var results by remember { mutableStateOf<List<Found>?>(null) }
    val genres = remember(library) { genresOf(library) }
    val decades = remember(library) { decadesOf(library) }

    LaunchedEffect(query, filters, library, hidden) {
        delay(150) // one search per pause in the typing
        results = viewModel.search(query, filters)
    }

    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { viewModel.searchQuery.value = it }
    }
    val startDictation = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Titre, acteur ou réalisateur")
        try {
            dictate.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Aucune dictée vocale sur cet appareil", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.searchQuery.value = it },
            placeholder = { Text("Titre, acteur, réalisateur…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) TextButton(onClick = { viewModel.searchQuery.value = "" }) { Text("✕") }
                    TextButton(onClick = startDictation) { Text("🎤") }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )
        ChipRow {
            items(SearchKind.entries) { kind ->
                FilterChip(
                    selected = filters.kind == kind,
                    onClick = { viewModel.searchFilters.value = filters.copy(kind = kind) },
                    label = { Text(kind.label) },
                )
            }
            item {
                FilterChip(
                    selected = filters.hidden,
                    onClick = { viewModel.searchFilters.value = filters.copy(hidden = !filters.hidden) },
                    label = { Text("Masqués" + if (hidden.isNotEmpty()) " (${hidden.size})" else "") },
                )
            }
        }
        ChipRow {
            items(genres) { genre ->
                FilterChip(
                    selected = filters.genre == genre,
                    onClick = { viewModel.searchFilters.value = filters.copy(genre = genre.takeIf { it != filters.genre }) },
                    label = { Text(genre) },
                )
            }
        }
        ChipRow {
            items(decades) { decade ->
                FilterChip(
                    selected = filters.decade == decade,
                    onClick = { viewModel.searchFilters.value = filters.copy(decade = decade.takeIf { it != filters.decade }) },
                    label = { Text("Années ${if (decade in 1930..1999) decade % 100 else decade}") },
                )
            }
        }
        val found = results ?: return@Column
        PosterGrid(
            items = found.map { PosterItem(it.key, it.title, it.year, it.poster, it.note) },
            emptyText = when {
                filters.hidden -> "Aucun titre masqué."
                query.isBlank() -> "Aucun titre pour ces filtres."
                else -> "Rien pour « $query »."
            },
            onClick = { key -> if (found.first { it.key == key }.isShow) onOpenShow(key) else onOpenMovie(key) },
        )
    }
}

@Composable
private fun ChipRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(
        modifier = Modifier.padding(top = 6.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
