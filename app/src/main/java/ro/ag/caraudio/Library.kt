package ro.ag.caraudio

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import org.json.JSONArray
import org.json.JSONObject

/** Recently played items (kept on disk) and the last search results (kept in memory). */
object Library {
    private const val MAX_RECENT = 50
    private lateinit var prefs: SharedPreferences

    @Volatile
    var recent: List<MediaItem> = emptyList()
        private set

    @Volatile
    var lastSearch: List<MediaItem> = emptyList()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        recent = runCatching { fromJson(prefs.getString("recent", "[]")!!) }.getOrDefault(emptyList())
    }

    fun addRecent(item: MediaItem) {
        if (item.mediaId.isEmpty()) return
        val stored = YouTube.item(
            item.mediaId,
            item.mediaMetadata.title?.toString(),
            item.mediaMetadata.artist?.toString(),
            item.mediaMetadata.artworkUri?.toString(),
            item.mediaMetadata.extras?.getLong(YouTube.EXTRA_DURATION) ?: 0,
        )
        recent = (listOf(stored) + recent.filter { it.mediaId != item.mediaId }).take(MAX_RECENT)
        prefs.edit().putString("recent", toJson(recent)).apply()
    }

    fun find(mediaId: String): MediaItem? =
        recent.firstOrNull { it.mediaId == mediaId } ?: lastSearch.firstOrNull { it.mediaId == mediaId }

    private fun toJson(items: List<MediaItem>): String = JSONArray().apply {
        items.forEach {
            put(JSONObject().apply {
                put("id", it.mediaId)
                put("title", it.mediaMetadata.title?.toString())
                put("artist", it.mediaMetadata.artist?.toString())
                put("art", it.mediaMetadata.artworkUri?.toString())
                put("duration", it.mediaMetadata.extras?.getLong(YouTube.EXTRA_DURATION) ?: 0)
            })
        }
    }.toString()

    private fun fromJson(json: String): List<MediaItem> {
        val array = JSONArray(json)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            YouTube.item(
                o.getString("id"),
                o.optString("title").ifEmpty { null },
                o.optString("artist").ifEmpty { null },
                o.optString("art").ifEmpty { null },
                o.optLong("duration"),
            )
        }
    }
}
