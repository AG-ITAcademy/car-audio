package ro.ag.caraudio

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

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

    private val service get() = ServiceList.YouTube

    /** Returns playable items. Each mediaId is the video URL. */
    fun search(query: String): List<MediaItem> {
        val extractor = service.getSearchExtractor(
            query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""
        )
        extractor.fetchPage()
        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .distinctBy { it.url }
            .map { item(it.url, it.name, it.uploaderName, bestImage(it.thumbnails), it.duration) }
    }

    /** Returns the item with its audio stream URL set, so that the player can play it. */
    fun resolve(item: MediaItem): MediaItem {
        val info = StreamInfo.getInfo(service, item.mediaId)
        val stream = bestAudio(info.audioStreams)
        val uri = stream?.content ?: info.hlsUrl.takeIf { !it.isNullOrEmpty() }
            ?: throw IOException("No audio stream for ${item.mediaId}")
        return item(info.url, info.name, info.uploaderName, bestImage(info.thumbnails), info.duration)
            .buildUpon()
            .setUri(uri)
            .build()
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
