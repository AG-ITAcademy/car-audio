package ro.ag.caraudio

import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory

/**
 * Not a normal test: it loads real playlists from YouTube with the NewPipeExtractor
 * version in the build, and prints which ones load. It runs only with CAR_AUDIO_LIVE=1
 * (the "Live playlist check" workflow). It never fails the build: it reports.
 */
class LivePlaylistCheck {

    @Test
    fun moodPlaylistsLoad() {
        assumeTrue(System.getenv("CAR_AUDIO_LIVE") == "1")
        NewPipe.init(OkHttpDownloader(OkHttpClient()))
        val queries = Moods.all.map { it.query } + "Chill Cruise"
        var total = 0
        var failed = 0
        for (query in queries) {
            val playlists = try {
                val search = ServiceList.YouTube.getSearchExtractor(
                    query, listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS), ""
                )
                search.fetchPage()
                search.initialPage.items.filterIsInstance<PlaylistInfoItem>().take(PlaylistPicker.MAX_TRIES)
            } catch (e: Exception) {
                println("SEARCH-FAIL \"$query\": $e")
                continue
            }
            println("SEARCH \"$query\": ${playlists.size} playlists")
            for (p in playlists) {
                total++
                try {
                    val info = PlaylistInfo.getInfo(ServiceList.YouTube, p.url)
                    println("  OK   ${info.relatedItems.size} videos  ${p.url}  ${p.name}")
                } catch (e: Exception) {
                    failed++
                    println("  FAIL ${p.url}  ${p.name}: $e")
                    e.stackTrace.take(4).forEach { println("         at $it") }
                }
            }
        }
        println("LIVE-RESULT: $failed of $total playlists failed")
    }
}
