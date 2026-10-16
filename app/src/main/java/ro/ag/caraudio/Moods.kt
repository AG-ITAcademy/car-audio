package ro.ag.caraudio

import android.net.Uri

/**
 * Drive moods: one tap gives playlists for a moment of the drive. The same moods show
 * on the phone (Home) and in Android Auto (the "Moods" tab), with the same tiles.
 */
data class Mood(
    val id: String,
    val title: String,
    val subtitle: String,
    /** The YouTube playlist search for this mood. */
    val query: String,
) {
    /** The tile for the phone (Coil loads assets with this URI). */
    val assetUri: String get() = "file:///android_asset/art/mood_$id.jpg"

    /** The tile for Android Auto, which reads images through a content provider. */
    val contentUri: Uri get() = ArtProvider.uri("mood_$id.jpg")
}

object Moods {
    val all = listOf(
        Mood("morning", "Morning Commute", "Bright, upbeat starts", "upbeat morning drive hits playlist"),
        Mood("highway", "Highway Rock", "Guitars for open roads", "classic rock driving songs playlist"),
        Mood("night", "Night Drive", "Synths and city lights", "night drive synthwave playlist"),
        Mood("eighties", "Road Trip 80s", "Sing-along classics", "80s road trip hits playlist"),
        Mood("chill", "Chill Cruise", "Easy, smooth, slow", "chill driving music playlist"),
        Mood("focus", "Focus Flow", "Calm, few words", "calm instrumental driving playlist"),
        Mood("romania", "Hituri Românești", "Romanian hits", "hituri romanesti playlist"),
        Mood("throwback", "Throwback 2000s", "The hits you remember", "2000s hits throwback playlist"),
    )

    fun byId(id: String): Mood? = all.firstOrNull { it.id == id }
}
