package ro.ag.caraudio

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Search YouTube and find the audio-only stream of a video.
 * All functions do network work: call them from a background thread.
 */
object YouTube {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private const val ANDROID_USER_AGENT =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 15; US) gzip"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    const val EXTRA_DURATION = "duration"
    const val EXTRA_COUNT = "count"

    /** Stop loading a playlist after this many videos. */
    private const val MAX_PLAYLIST_ITEMS = 500

    /** Stream URLs expire after about 6 hours. */
    private val STREAM_URL_LIFETIME_MS = TimeUnit.HOURS.toMillis(4)
    private val streamUrls = ConcurrentHashMap<String, Pair<String, Long>>()

    private val service get() = ServiceList.YouTube

    enum class SearchType { PLAYLISTS, VIDEOS }

    /**
     * Returns videos (playable) or playlists (browsable). Each mediaId is the YouTube URL.
     */
    fun search(query: String, type: SearchType, artistsOnly: Boolean): List<MediaItem> {
        val filter = when (type) {
            SearchType.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.PLAYLISTS
            SearchType.VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
        }
        val extractor = service.getSearchExtractor(query, listOf(filter), "")
        extractor.fetchPage()
        return extractor.initialPage.items
            .distinctBy { it.url }
            .mapNotNull {
                when (it) {
                    is StreamInfoItem ->
                        if (artistsOnly && !MusicFilter.isArtistSong(it.name, it.uploaderName, it.isUploaderVerified)) null
                        else track(it.url, it.name, it.uploaderName, bestImage(it.thumbnails), it.duration)
                    is PlaylistInfoItem ->
                        if (artistsOnly && MusicFilter.mentionsAi(it.name, it.uploaderName)) null
                        else playlistItem(it.url, it.name, it.uploaderName, bestImage(it.thumbnails), it.streamCount)
                    else -> null
                }
            }
    }

    /**
     * The videos of a playlist. YouTube sends them in pages of about 100. [onPage] gets
     * each page when it arrives, so that the first song can play before all pages load.
     */
    fun playlist(
        url: String,
        artistsOnly: Boolean,
        onPage: ((List<MediaItem>) -> Unit)? = null,
    ): List<MediaItem> {
        val all = mutableListOf<MediaItem>()
        val seen = HashSet<String>()
        fun add(page: List<StreamInfoItem>) {
            val items = page
                .filter { seen.add(it.url) }
                .filter { !artistsOnly || MusicFilter.isArtistSong(it.name, it.uploaderName, it.isUploaderVerified) }
                .map { track(it.url, it.name, it.uploaderName, bestImage(it.thumbnails), it.duration) }
            all += items
            onPage?.invoke(items)
        }
        val info = PlaylistInfo.getInfo(service, url)
        add(info.relatedItems)
        var page: Page? = info.nextPage
        var loaded = info.relatedItems.size
        while (Page.isValid(page) && loaded < MAX_PLAYLIST_ITEMS) {
            val more = PlaylistInfo.getMoreItems(service, url, page)
            add(more.items)
            loaded += more.items.size
            page = more.nextPage
        }
        return all
    }

    /**
     * Returns the item with its audio stream URL and full metadata set.
     * Use it for a single item, for example a link shared from the YouTube app.
     */
    fun resolve(item: MediaItem): MediaItem {
        val info = StreamInfo.getInfo(service, item.mediaId)
        val uri = bestAudio(info.audioStreams)?.content
            ?: throw IOException("No audio stream for ${item.mediaId}")
        streamUrls[item.mediaId] = uri to System.currentTimeMillis()
        if (info.url != item.mediaId) streamUrls[info.url] = uri to System.currentTimeMillis()
        return track(info.url, info.name, info.uploaderName, bestImage(info.thumbnails), info.duration)
            .buildUpon()
            .setUri(uri)
            .build()
    }

    /**
     * Returns the item with the YouTube URL as its URI. The player finds the audio stream
     * only when the item starts to play (see [streamUrl]), so a long playlist starts fast.
     */
    fun lazy(item: MediaItem): MediaItem = item.buildUpon().setUri(item.mediaId).build()

    /** A large thumbnail (480x360) of a video, or null if the URL has no video ID. */
    fun largeThumbnail(videoUrl: String): String? {
        val uri = Uri.parse(videoUrl)
        val id = if (uri.host == "youtu.be") uri.lastPathSegment else uri.getQueryParameter("v")
        return id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    }

    fun isYouTubePage(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    /** The audio stream URL of a video. Uses the cache when the URL is still valid. */
    fun streamUrl(videoUrl: String): String {
        streamUrls[videoUrl]?.let { (url, time) ->
            if (System.currentTimeMillis() - time < STREAM_URL_LIFETIME_MS) return url
        }
        val info = StreamInfo.getInfo(service, videoUrl)
        val url = bestAudio(info.audioStreams)?.content
            ?: throw IOException("No audio stream for $videoUrl")
        streamUrls[videoUrl] = url to System.currentTimeMillis()
        return url
    }

    /** Forget the cached stream URL, for example after an HTTP 403 error. */
    fun invalidate(videoUrl: String) {
        streamUrls.remove(videoUrl)
    }

    /** A video item with the artist and song name taken from the YouTube title. */
    fun track(url: String, videoTitle: String?, channel: String?, artwork: String?, seconds: Long): MediaItem {
        val (artist, song) = MusicFilter.artistAndSong(videoTitle.orEmpty(), channel)
        return item(url, song, artist, artwork, seconds)
    }

    fun item(url: String, title: String?, artist: String?, artwork: String?, seconds: Long): MediaItem =
        MediaItem.Builder()
            .setMediaId(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artwork?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                    .setExtras(Bundle().apply { putLong(EXTRA_DURATION, seconds) })
                    .build()
            )
            .build()

    fun playlistItem(url: String, title: String?, artist: String?, artwork: String?, count: Long): MediaItem =
        MediaItem.Builder()
            .setMediaId(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artwork?.let(Uri::parse))
                    .setIsPlayable(false)
                    .setIsBrowsable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setExtras(Bundle().apply { putLong(EXTRA_COUNT, count) })
                    .build()
            )
            .build()

    /**
     * Headers for requests to the stream server. The "c" query parameter tells which
     * YouTube client the URL was made for; the server expects a matching user agent.
     */
    fun streamHeaders(uri: Uri): Map<String, String> {
        if (uri.host?.endsWith("googlevideo.com") != true) return emptyMap()
        val client = uri.getQueryParameter("c").orEmpty().uppercase()
        return when {
            client.startsWith("ANDROID") -> mapOf("User-Agent" to ANDROID_USER_AGENT)
            client.startsWith("IOS") -> mapOf("User-Agent" to IOS_USER_AGENT)
            else -> mapOf(
                "User-Agent" to BROWSER_USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/",
            )
        }
    }

    /** Highest bitrate, single-file stream, original language track. */
    private fun bestAudio(streams: List<AudioStream>): AudioStream? =
        streams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .sortedWith(
                compareBy<AudioStream> { it.audioTrackType?.name in setOf("DUBBED", "DESCRIPTIVE") }
                    .thenByDescending { it.averageBitrate }
            )
            .firstOrNull()

    private fun bestImage(images: List<Image>): String? =
        images.maxByOrNull { it.height }?.url
}
