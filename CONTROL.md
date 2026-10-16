# Control Car Audio from another app

Car Audio has a standard Android **media browser service**. Another app on the same phone
can connect to it and start or control playback, with no screen opening. Android Auto,
Google Assistant and the steering wheel use the same service.

| | |
|---|---|
| Package | `ro.ag.caraudio` |
| Service | `ro.ag.caraudio.PlaybackService` |
| Interface | `MediaBrowserCompat` / `MediaControllerCompat` (androidx.media), or media3 `MediaBrowser` |

## Who may connect

Only trusted apps (see `TRUSTED_APPS` in `PlaybackService.kt`): Car Audio itself,
**RoadGuide (`ro.roadguide`)**, Android Auto, the Google app / Assistant, and apps that are
part of the phone's system. Other apps are refused (the connection fails; logcat tag
`CarAudio` shows `Connection refused: <package>`). To allow another app, add its package
name to `TRUSTED_APPS`.

## Commands

All commands go through `MediaControllerCompat.getTransportControls()`.

| What | Call |
|---|---|
| A playlist for words ("Queen", "rock driving songs") | `playFromSearch("Queen", Bundle.EMPTY)` |
| One **song**, then other songs found | `playFromSearch("Bohemian Rhapsody", songExtras)` (below) |
| A drive mood | `playFromMediaId("mood:night", null)` |
| A YouTube video | `playFromMediaId("https://www.youtube.com/watch?v=VIDEO_ID", null)` |
| A YouTube playlist | `playFromMediaId("https://www.youtube.com/playlist?list=LIST_ID", null)` |
| Continue the last song | `playFromSearch("", Bundle.EMPTY)` |
| Play / pause | `play()` / `pause()` |
| Next / previous | `skipToNext()` / `skipToPrevious()` (previous: start of song; again within 4 s: previous song) |

**Song extras** (standard Android keys, `android.provider.MediaStore`):

```kotlin
val songExtras = Bundle().apply {
    putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE) // "a song"
    putString(MediaStore.EXTRA_MEDIA_TITLE, "Bohemian Rhapsody")   // optional
    putString(MediaStore.EXTRA_MEDIA_ARTIST, "Queen")              // optional
}
```

When title or artist are given, Car Audio searches for "artist title"; else it uses the
query. Any other focus (or none) means "a playlist".

The **Artists only** filter of the app applies to all of these.

## Drive moods

| id | Name |
|---|---|
| `morning` | Morning Commute |
| `highway` | Highway Rock |
| `night` | Night Drive |
| `eighties` | Road Trip 80s |
| `chill` | Chill Cruise |
| `focus` | Focus Flow |
| `romania` | Hituri Românești |
| `throwback` | Throwback 2000s |

The list is also in the browse tree: `subscribe("moods", …)` returns one item per mood
(media id `mood:<id>`, title, subtitle). The source is `Moods.kt`.

## What plays now

Register a `MediaControllerCompat.Callback`:

- `onMetadataChanged`: `METADATA_KEY_TITLE` = song, `METADATA_KEY_ARTIST` = artist
  (taken from "Artist - Song" titles, else the channel name).
- `onPlaybackStateChanged`: `STATE_PLAYING`, `STATE_PAUSED`, `STATE_BUFFERING`,
  `STATE_ERROR`.

## Errors

When nothing can play (no result, all songs filtered out, no network), playback does not
start and the state does not change to `STATE_PLAYING`. Treat "no `STATE_PLAYING` within
about 15 seconds" as a failure. The reason is in logcat: `adb logcat -s CarAudio`.

## Audio focus

Car Audio uses normal audio focus. When another app takes focus for speech, Car Audio
lowers its volume (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) or pauses and then continues
(`AUDIOFOCUS_GAIN_TRANSIENT`). It stops for `AUDIOFOCUS_GAIN` (another music app).
