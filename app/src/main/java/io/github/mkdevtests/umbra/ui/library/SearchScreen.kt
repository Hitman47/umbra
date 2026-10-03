package io.github.mkdevtests.umbra.ui.library

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import io.github.mkdevtests.umbra.library.SearchFilters
import io.github.mkdevtests.umbra.library.SearchKind
import io.github.mkdevtests.umbra.library.decadeLabel
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
    val history by viewModel.history.collectAsState()
    var results by remember { mutableStateOf<List<Found>?>(null) }
    val genres = remember(library) { genresOf(library) }
    val decades = remember(library) { decadesOf(library) }

    LaunchedEffect(query, filters, library, hidden, history) {
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
            items(listOf(true to "Vus", false to "Non vus")) { (seen, label) ->
                FilterChip(
                    selected = filters.seen == seen,
                    onClick = { viewModel.searchFilters.value = filters.copy(seen = seen.takeIf { it != filters.seen }) },
                    label = { Text(label) },
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
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Dropdown(
                text = filters.genre ?: "Genre",
                active = filters.genre != null,
                options = listOf<Pair<String?, String>>(null to "Tous les genres") + genres.map { (genre, count) -> genre to "$genre ($count)" },
                onSelect = { viewModel.searchFilters.value = filters.copy(genre = it) },
            )
            Dropdown(
                text = filters.decade?.let(::decadeLabel) ?: "Années",
                active = filters.decade != null,
                options = listOf<Pair<Int?, String>>(null to "Toutes les années") + decades.map { (decade, count) -> decade to "${decadeLabel(decade)} ($count)" },
                onSelect = { viewModel.searchFilters.value = filters.copy(decade = it) },
            )
            if (filters != SearchFilters()) {
                TextButton(onClick = { viewModel.searchFilters.value = SearchFilters() }) { Text("Tout effacer") }
            }
        }
        val found = results ?: return@Column
        PosterGrid(
            items = found.map { PosterItem(it.key, it.title, it.year, it.poster, it.note, it.badge) },
            emptyText = when {
                filters.hidden -> "Aucun titre masqué."
                query.isBlank() -> "Aucun titre pour ces filtres."
                else -> "Rien pour « $query »."
            },
            onClick = { key -> if (found.first { it.key == key }.isShow) onOpenShow(key) else onOpenMovie(key) },
        )
    }
}

/** A filter chip opening its list of choices: "Genre ▾". */
@Composable
private fun <T> Dropdown(text: String, active: Boolean, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = active, onClick = { open = true }, label = { Text("$text  ▾") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(value) })
            }
        }
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
