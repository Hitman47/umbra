package io.github.mkdevtests.umbra.ui.settings

import io.github.mkdevtests.umbra.ui.theme.focusRing
import io.github.mkdevtests.umbra.ui.theme.remoteFriendly
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.library.Correction
import io.github.mkdevtests.umbra.library.MatchDecision
import io.github.mkdevtests.umbra.library.correctionsOf
import io.github.mkdevtests.umbra.library.groupLabel
import io.github.mkdevtests.umbra.library.normalizeTitle
import io.github.mkdevtests.umbra.ui.library.LibraryViewModel
import io.github.mkdevtests.umbra.ui.theme.ScreenTitle

/**
 * The match corrections kept, each with what the last scan made of it, then
 * the scan's journal: how every group of episodes got its show.
 */
@Composable
fun CorrectionsScreen(viewModel: LibraryViewModel, onOpenShow: (String) -> Unit, onBack: () -> Unit) {
    val fixes by viewModel.matchFixes.collectAsState()
    val library by viewModel.library.collectAsState()
    val scan by viewModel.scan.collectAsState()
    val decisions by viewModel.decisions.collectAsState()
    val corrections = remember(fixes, library) { correctionsOf(fixes.values, library) }
    val decisionOf = remember(decisions) { decisions.associateBy { it.group } }
    var filter by rememberSaveable { mutableStateOf("") }
    val journal = remember(decisions, filter) {
        val wanted = normalizeTitle(filter)
        decisions.filter { wanted.isEmpty() || wanted in normalizeTitle(groupLabel(it.group)) || wanted in normalizeTitle(it.title) }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle("Corrections de matching", onBack)
        if (scan.running) {
            Note("Analyse en cours : l'état de chaque correction sera à jour à la fin.")
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (corrections.isEmpty()) item { Note("Aucune correction. Sur la fiche d'une série : Corriger.") }
            items(corrections, key = { "fix:" + it.fix.groupKey }) { correction ->
                CorrectionRow(
                    correction,
                    decisionOf[correction.fix.groupKey],
                    onOpen = correction.show?.let { show -> { onOpenShow(show.key) } },
                    onRemove = { viewModel.resetMatch(listOf(correction.fix.groupKey)) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            }
            item {
                Column(modifier = Modifier.padding(top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Journal de la dernière analyse", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Chaque dossier d'épisodes et la série qu'il a reçue : par une correction, parce qu'il était déjà reconnu, ou par une recherche TMDB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(filter, { filter = it }, label = { Text("Filtrer (dossier ou série)") }, singleLine = true, modifier = Modifier.fillMaxWidth().remoteFriendly())
                }
            }
            items(journal.take(MAX_JOURNAL), key = { "log:" + it.group }) { decision ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(groupLabel(decision.group), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${decision.files} fichier${if (decision.files > 1) "s" else ""} → ${decision.show} · ${decision.how}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (journal.size > MAX_JOURNAL) item { Note("… et ${journal.size - MAX_JOURNAL} autres : filtre pour les trouver.") }
        }
    }
}

private const val MAX_JOURNAL = 200

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

@Composable
private fun CorrectionRow(correction: Correction, decision: MatchDecision?, onOpen: (() -> Unit)?, onRemove: () -> Unit) {
    val fix = correction.fix
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.focusRing().clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(correction.label, style = MaterialTheme.typography.bodyLarge)
            val choice = listOfNotNull(
                if (fix.tmdbId == null) "Choix : série à part, sans TMDB" else "Choix : TMDB ${fix.tmdbId}",
                fix.season?.let { "saison $it" + if (fix.firstEpisode > 1) ", à partir de l'épisode ${fix.firstEpisode}" else "" },
            ).joinToString(" · ")
            Text(choice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val show = correction.show
            val year = show?.year?.let { " ($it)" }.orEmpty()
            val (state, color) = when {
                show == null -> "⚠ Aucun fichier trouvé pour ce dossier à la dernière analyse" to MaterialTheme.colorScheme.error
                correction.applied -> "✓ Appliquée : ${show.title}$year · TMDB ${show.tmdbId}" to MaterialTheme.colorScheme.primary
                else -> "⚠ Pas appliquée : ses fichiers sont dans « ${show.title}$year » · TMDB ${show.tmdbId}" to MaterialTheme.colorScheme.error
            }
            Text(state, style = MaterialTheme.typography.bodySmall, color = color)
            decision?.let {
                Text("Dernière analyse : ${it.show} · ${it.how}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onRemove) { Text("Supprimer") }
    }
}
