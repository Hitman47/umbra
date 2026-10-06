package io.github.mkdevtests.umbra.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle

/** The size of every card (posters, folders, profiles, Perso videos) against the usual one: Réglages › Appli. */
val LocalCardScale = staticCompositionLocalOf { 1f }

/** The sizes offered, and their names. */
val CARD_SCALES = listOf(0.8f to "Très petite", 0.9f to "Petite", 1f to "Normale", 1.15f to "Grande", 1.3f to "Très grande")

/** [this] text style at a card's [scale]: the text under a card grows and shrinks with it. */
fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
