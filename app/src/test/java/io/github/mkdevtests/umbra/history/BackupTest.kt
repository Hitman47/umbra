package io.github.mkdevtests.umbra.history

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTest {
    @Test
    fun newerProgressOnly() {
        val local = mapOf("a" to Progress("a", 10.0, 100.0, updatedAt = 50), "b" to Progress("b", 90.0, 100.0, updatedAt = 80))
        val incoming = listOf(BackupProgress("a", 60.0, 100.0, 70), BackupProgress("b", 5.0, 100.0, 20), BackupProgress("c", 1.0, 2.0, 1))
        assertEquals(listOf("a", "c"), newerProgress(local, incoming).map { it.file })
    }

    @Test
    fun preferencesKeepTheirTypes() {
        listOf(true, 10, 5L, 1.5f, "fr,en", setOf("x", "y")).forEach { value ->
            assertEquals(value, decodePref(encodePref(value)!!))
        }
    }
}
