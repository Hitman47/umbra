package io.github.mkdevtests.umbra.ui.settings

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.library.Correction
import io.github.mkdevtests.umbra.library.correctionsOf
import io.github.mkdevtests.umbra.ui.library.LibraryViewModel
import io.github.mkdevtests.umbra.ui.theme.ScreenTitle

/**
 * The match corrections kept, each with what the last scan made of it:
 * applied, or not (its folder is gone, or its files are in another show).
 */
@Composable
fun CorrectionsScreen(viewModel: LibraryViewModel, onOpenShow: (String) -> Unit, onBack: () -> Unit) {
    val fixes by viewModel.matchFixes.collectAsState()
    val library by viewModel.library.collectAsState()
    val scan by viewModel.scan.collectAsState()
    val corrections = remember(fixes, library) { correctionsOf(fixes.values, library) }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle("Corrections de matching", onBack)
        if (scan.running) {
            Text(
                "Analyse en cours : l'état de chaque correction sera à jour à la fin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        if (corrections.isEmpty()) {
            Text(
                "Aucune correction. Sur la fiche d'une série : Corriger.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(corrections, key = { it.fix.groupKey }) { correction ->
                CorrectionRow(
                    correction,
                    onOpen = correction.show?.let { show -> { onOpenShow(show.key) } },
                    onRemove = { viewModel.resetMatch(listOf(correction.fix.groupKey)) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            }
        }
    }
}

@Composable
private fun CorrectionRow(correction: Correction, onOpen: (() -> Unit)?, onRemove: () -> Unit) {
    val fix = correction.fix
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(correction.label, style = MaterialTheme.typography.bodyLarge)
            val choice = listOfNotNull(
                if (fix.tmdbId == null) "Série à part, sans TMDB" else "TMDB n° ${fix.tmdbId}",
                fix.season?.let { "saison $it" + if (fix.firstEpisode > 1) ", à partir de l'épisode ${fix.firstEpisode}" else "" },
            ).joinToString(" · ")
            Text(choice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val show = correction.show
            val (state, color) = when {
                show == null -> "⚠ Aucun fichier trouvé pour ce dossier à la dernière analyse" to MaterialTheme.colorScheme.error
                correction.applied -> "✓ Appliquée : ${show.title}" to MaterialTheme.colorScheme.primary
                else -> "⚠ Pas appliquée : ses fichiers sont dans « ${show.title} »" to MaterialTheme.colorScheme.error
            }
            Text(state, style = MaterialTheme.typography.bodySmall, color = color)
        }
        TextButton(onClick = onRemove) { Text("Supprimer") }
    }
}
