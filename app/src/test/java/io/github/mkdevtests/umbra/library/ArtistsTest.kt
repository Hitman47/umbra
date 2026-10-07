package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistsTest {
    private val roots = listOf("Media\\Spectacles")

    @Test
    fun theFolderBelowTheRoot() {
        assertEquals("Florence Foresti", artistOf("Media\\Spectacles\\Florence Foresti\\Epilogue (2018).mkv", roots))
        assertEquals("Gad Elmaleh", artistOf("Media\\Spectacles\\Gad Elmaleh\\Vieux\\Partie 1.mkv", roots))
    }

    @Test
    fun aFlatFolderNamedArtistDashTitle() {
        assertEquals("Kev Adams", artistOf("Media\\Spectacles\\Kev Adams - Sois 10 ans.mkv", roots))
        assertNull(artistOf("Media\\Spectacles\\Epilogue (2018).mkv", roots))
        assertNull(artistOf("Media\\Spectacles\\2019 - Titre.mkv", roots))
        assertNull(artistOf("Media\\Films\\Kev Adams - Sois 10 ans.mkv", roots))
    }
}
