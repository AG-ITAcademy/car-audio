package ro.ag.caraudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PlaylistPickerTest {

    private val urls = listOf("p1", "p2", "p3", "p4", "p5", "p6")

    /** Like NewPipe when YouTube sends a layout that it cannot read. */
    private fun broken(): Nothing = throw NullPointerException("JsonObject.getObject on a null object reference")

    @Test
    fun firstPlaylistThrows_secondPlaylistPlays() {
        val failed = mutableListOf<String>()
        val result = PlaylistPicker.forMood(
            urls, "Chill Cruise",
            load = { url -> if (url == "p1") broken() else listOf("$url-a", "$url-b") },
            searchSongs = { fail("no fallback expected"); emptyList() },
            onFailure = { url, _ -> failed += url },
        )
        assertEquals(listOf("p2-a", "p2-b"), result)
        assertEquals(listOf("p1"), failed)
    }

    @Test
    fun emptyPlaylistIsSkipped() {
        val result = PlaylistPicker.forSearch(
            urls, "Queen",
            load = { url -> if (url == "p1") emptyList() else listOf("$url-a") },
            searchVideos = { fail("no fallback expected"); emptyList() },
        )
        assertEquals(listOf("p2-a"), result)
    }

    @Test
    fun atMostFivePlaylistsAreTried() {
        val tried = mutableListOf<String>()
        PlaylistPicker.forSearch(
            urls, "x",
            load = { url -> tried += url; broken() },
            searchVideos = { listOf("v1") },
        )
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), tried)
    }

    @Test
    fun allPlaylistsThrow_moodPlaysAllSongsOfTheVideoSearch() {
        val result = PlaylistPicker.forMood(
            urls, "Chill Cruise",
            load = { broken() },
            searchSongs = { listOf("v1", "v2", "v3") },
        )
        assertEquals(listOf("v1", "v2", "v3"), result)
    }

    @Test
    fun allPlaylistsThrow_searchPlaysTheFirstVideo() {
        val result = PlaylistPicker.forSearch(
            urls, "Chill Cruise",
            load = { broken() },
            searchVideos = { listOf("v1", "v2", "v3") },
        )
        assertEquals(listOf("v1"), result)
    }

    @Test
    fun noPlaylistsAtAll_usesTheFallback() {
        val result = PlaylistPicker.forMood(
            emptyList(), "Night Drive",
            load = { fail("nothing to load"); emptyList<String>() },
            searchSongs = { listOf("v1") },
        )
        assertEquals(listOf("v1"), result)
    }

    @Test
    fun fallbackAlsoFails_throwsNoPlayablePlaylist() {
        try {
            PlaylistPicker.forMood(
                urls, "Chill Cruise",
                load = { broken() },
                searchSongs = { throw IOException("no network") },
            )
            fail("expected an IOException")
        } catch (e: IOException) {
            assertEquals("No playable playlist for Chill Cruise", e.message)
            assertTrue(e.cause is IOException)
        }
    }

    @Test
    fun fallbackFindsNothing_throwsNoPlayablePlaylist() {
        try {
            PlaylistPicker.forSearch(urls, "Queen", load = { broken() }, searchVideos = { emptyList() })
            fail("expected an IOException")
        } catch (e: IOException) {
            assertEquals("No playable playlist for Queen", e.message)
        }
    }
}
