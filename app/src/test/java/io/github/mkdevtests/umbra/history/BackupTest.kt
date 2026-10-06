package io.github.mkdevtests.umbra.history

import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.nas.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun knownSourcesAreNotAddedTwice() {
        val local = listOf(
            NasSource("192.168.1.10", listOf("Media"), "me", "", id = "a"),
            NasSource("nas.local", listOf("Films"), "me", "secret", id = "b"),
        )
        val incoming = listOf(
            BackupSource("x", host = "192.168.1.10", shares = listOf("Media"), username = "me", password = "pw"), // same address, another id
            BackupSource("b", host = "nas.local", shares = listOf("Films"), password = "other"), // has its password already
            BackupSource("c", host = "192.168.1.11", shares = listOf("Docs"), protocol = "Nfs", personal = listOf("Docs\\Mine")),
        )
        val merge = mergeSources(local, incoming)
        assertEquals(listOf("c"), merge.added.map { it.id })
        assertEquals(Protocol.Nfs, merge.added.single().protocol)
        assertEquals(listOf("Docs\\Mine"), merge.added.single().personal)
        assertEquals(listOf("a" to "pw"), merge.updated.map { it.id to it.password })
    }

    @Test
    fun aFileKeepsNoSecret() {
        val services = BackupServices(prowlarrUrl = "http://nas:9696", prowlarrKey = "k", qbitPassword = "p", catalogPassword = "c").public()
        assertEquals("http://nas:9696", services.prowlarrUrl)
        assertTrue(listOf(services.prowlarrKey, services.qbitPassword, services.qbitKey, services.catalogPassword).all { it.isEmpty() })
    }
}
