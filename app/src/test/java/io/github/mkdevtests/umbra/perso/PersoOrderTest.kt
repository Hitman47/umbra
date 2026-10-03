package io.github.mkdevtests.umbra.perso

import io.github.mkdevtests.umbra.nas.NasRouter
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.nas.SmbNas
import io.github.mkdevtests.umbra.player.PlayOrder
import io.github.mkdevtests.umbra.player.Repeat
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
        val order = PlayOrder(files, shuffle = true, random = Random(1))
        val first = List(files.size) { order.next()!! }
        assertEquals(files.toSet(), first.toSet())
        // A new round: everything again, never the closing video first.
        val second = List(files.size) { order.next()!! }
        assertEquals(files.toSet(), second.toSet())
        assertNotEquals(first.last(), second.first())
    }

    @Test
    fun shuffle_round_carries_over_sessions() {
        val played = files.take(15)
        val order = PlayOrder(files, shuffle = true, played = played, random = Random(2))
        val next = List(5) { order.next()!! }
        assertEquals(files.drop(15).toSet(), next.toSet())
    }

    @Test
    fun in_order_loops_after_the_last() {
        val order = PlayOrder(files, shuffle = false)
        order.playing(files.last())
        assertEquals(files.first(), order.next())
        assertEquals(files[1], order.next())
    }

    @Test
    fun starts_where_it_stopped() {
        val state = PersoFolderState(last = files[4], played = listOf(files[4]))
        val unfinished = mapOf(files[4] to PersoProgress(120.0, 600.0))
        val order = PlayOrder(files, shuffle = true, played = state.played, random = Random(3))
        assertEquals(files[4], firstOf(order, null, state) { unfinished[it] })
        // The video then isn't played again in this round.
        assertFalse(List(files.size - 1) { order.next()!! }.contains(files[4]))
    }

    @Test
    fun in_order_goes_on_after_a_finished_video() {
        val state = PersoFolderState(last = files[4])
        val finished = mapOf(files[4] to PersoProgress(598.0, 600.0))
        val order = PlayOrder(files, shuffle = false)
        assertEquals(files[5], firstOf(order, null, state) { finished[it] })
        assertEquals(files[6], order.next())
    }

    @Test
    fun the_asked_video_comes_first() {
        val order = PlayOrder(files, shuffle = false)
        assertEquals(files[9], firstOf(order, files[9], PersoFolderState(last = files[2])) { null })
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

    @Test
    fun a_share_given_whole_to_perso_leaves_the_library_roots() {
        val source = NasSource("nas", listOf("Films", "Clips"), id = "a", personal = listOf("Clips"))
        val router = NasRouter(listOf(SmbNas(source)))
        assertEquals(listOf("Films"), router.list("").map { it.path })
        assertEquals(listOf("Clips", "Films"), router.list("", withPersonal = true).map { it.path })
        assertEquals(listOf("Films"), router.libraryRootsOf(source))
        assertTrue(router.isPersonal("Clips\\2024\\a.mp4"))
    }

    @Test
    fun asked_next_then_removed_and_no_loop_without_repeat() {
        val order = PlayOrder(files.take(4), shuffle = false, repeat = Repeat.None)
        order.playing(files[0])
        order.playNext(files[3])
        order.remove(files[1])
        assertEquals(listOf(files[3], files[2]), order.upcoming())
        assertEquals(files[3], order.next())
        assertEquals(files[2], order.next())
        assertEquals(null, order.next())
    }

    @Test
    fun shuffle_switched_on_and_off_while_playing() {
        val order = PlayOrder(files, shuffle = false, random = Random(4))
        order.playing(files[0])
        order.setShuffle(true)
        val shuffled = order.upcoming()
        assertEquals(files.size - 1, shuffled.size)
        assertFalse(files[0] in shuffled)
        order.setShuffle(false)
        assertEquals(files[1], order.peek())
    }

    @Test
    fun ten_thousand_videos_stay_quick() {
        val many = (1..12_000).map { "Media\\Clips\\clip$it.mp4" }
        val order = PlayOrder(many, shuffle = true, random = Random(5))
        val started = System.nanoTime()
        repeat(2_000) { order.next() }
        repeat(50) { order.upcoming() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertEquals(2_000, order.played.toSet().size)
    }
}
