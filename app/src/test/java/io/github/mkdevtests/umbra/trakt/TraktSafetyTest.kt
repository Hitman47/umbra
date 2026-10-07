package io.github.mkdevtests.umbra.trakt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Nyxara must never make the user lose their Trakt history. These checks fail
 * the build if Nyxara gains a way to remove, reset or rewrite anything there.
 */
class TraktSafetyTest {

    private val sources = File("src/main/java").walk().filter { it.extension == "kt" }.toList()
    private val traktSources = sources.filter { it.parentFile.name == "trakt" }

    /** Source lines without comments: the documentation may name what is forbidden. */
    private fun File.code() = readLines().withIndex().filterNot { (_, line) ->
        line.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
    }

    @Test
    fun onlyTheseEndpoints() {
        val endpoints = TraktEndpoint.entries.map { "${it.method} ${it.path}" }.toSet()
        assertEquals(
            setOf(
                "POST oauth/device/code",
                "POST oauth/device/token",
                "POST oauth/token",
                "GET sync/last_activities",
                "GET sync/watched/movies",
                "GET sync/watched/shows",
                "GET sync/playback/movies",
                "GET sync/playback/episodes",
                "GET sync/watchlist/movies",
                "GET sync/watchlist/shows",
                "GET search/tmdb/{id}",
                "GET shows/{id}/seasons/{season}/episodes/{episode}",
                "GET shows/{id}/progress/watched",
                "POST scrobble/start",
                "POST scrobble/pause",
                "POST scrobble/stop",
            ),
            endpoints,
        )
    }

    @Test
    fun nothingThatRemovesOrResets() {
        val forbidden = Regex("""sync/history|/remove|revoke|/reset|"DELETE"|"PUT"|"PATCH"|\.(delete|put|patch)\(""", RegexOption.IGNORE_CASE)
        val api = traktSources.single { it.name == "TraktApi.kt" }
        val hits = api.code().filter { (_, line) -> forbidden.containsMatchIn(line) }.map { "${it.index + 1}: ${it.value.trim()}" }
        assertEquals(emptyList<String>(), hits)
        // Elsewhere in Trakt code, reset_at is only read; no other Trakt path is ever written.
        val paths = Regex("""sync/history|/remove|revoke|oauth/revoke""")
        val elsewhere = traktSources.flatMap { file -> file.code().filter { (_, line) -> paths.containsMatchIn(line) }.map { "${file.name}:${it.index + 1}" } }
        assertEquals(emptyList<String>(), elsewhere)
    }

    @Test
    fun onlyTraktApiTalksToTrakt() {
        val callers = sources.filter { file -> file.code().any { (_, line) -> "api.trakt.tv" in line || "trakt.tv" in line && "https" in line } }
        assertEquals(listOf("TraktApi.kt"), callers.map { it.name })
        val requests = traktSources.filter { file -> file.code().any { (_, line) -> "Request.Builder" in line } }
        assertEquals(listOf("TraktApi.kt"), requests.map { it.name })
    }
}
