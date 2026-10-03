package io.github.mkdevtests.umbra.perso

import io.github.mkdevtests.umbra.nas.NasSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PersoOrderTest {
    private val files = (1..20).map { "Media\\Clips\\clip$it.mp4" }

    @Test
    fun shuffle_plays_each_video_once_per_round_and_loops() {
        val order = PersoOrder(files, shuffle = true, random = Random(1))
        val first = List(files.size) { order.next() }
        assertEquals(files.toSet(), first.toSet())
        // A new round: everything again, never the closing video first.
        val second = List(files.size) { order.next() }
        assertEquals(files.toSet(), second.toSet())
        assertNotEquals(first.last(), second.first())
    }

    @Test
    fun shuffle_round_carries_over_sessions() {
        val played = files.take(15)
        val order = PersoOrder(files, shuffle = true, played = played, random = Random(2))
        val next = List(5) { order.next() }
        assertEquals(files.drop(15).toSet(), next.toSet())
    }

    @Test
    fun in_order_loops_after_the_last() {
        val order = PersoOrder(files, shuffle = false)
        order.playing(files.last())
        assertEquals(files.first(), order.next())
        assertEquals(files[1], order.next())
    }

    @Test
    fun starts_where_it_stopped() {
        val state = PersoFolderState(last = files[4], played = listOf(files[4]))
        val unfinished = mapOf(files[4] to PersoProgress(120.0, 600.0))
        val order = PersoOrder(files, shuffle = true, played = state.played, random = Random(3))
        assertEquals(files[4], firstOf(order, files, true, null, state) { unfinished[it] })
        // The video then isn't played again in this round.
        assertFalse(List(files.size - 1) { order.next() }.contains(files[4]))
    }

    @Test
    fun in_order_goes_on_after_a_finished_video() {
        val state = PersoFolderState(last = files[4])
        val finished = mapOf(files[4] to PersoProgress(598.0, 600.0))
        val order = PersoOrder(files, shuffle = false)
        assertEquals(files[5], firstOf(order, files, false, null, state) { finished[it] })
        assertEquals(files[6], order.next())
    }

    @Test
    fun the_asked_video_comes_first() {
        val order = PersoOrder(files, shuffle = false)
        assertEquals(files[9], firstOf(order, files, false, files[9], PersoFolderState(last = files[2])) { null })
        assertEquals(files[10], order.next())
    }

    @Test
    fun short_clips_end_near_their_end() {
        assertTrue(PersoProgress(55.0, 60.0).watched)
        assertTrue(PersoProgress(20.0, 60.0).inProgress)
        assertFalse(PersoProgress(5.0, 60.0).inProgress)
    }

    @Test
    fun perso_folders_leave_the_library_but_stay_readable() {
        val source = NasSource("nas", listOf("Media"), excluded = listOf("Media\\Divers"), personal = listOf("Media\\Divers\\Clips"))
        assertTrue(source.isPersonal("Media\\Divers\\Clips\\a.mp4"))
        assertFalse(source.isPersonal("Media\\Divers\\Clipsx"))
        // Excluded around it, readable in it.
        assertTrue(source.isExcluded("Media\\Divers\\autre.mkv"))
        assertFalse(source.isExcluded("Media\\Divers\\Clips\\a.mp4"))
    }
}
