# Car Audio

A personal Android app. It plays the audio of YouTube videos in the background, with no ads.
It shows in Android Auto. It uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
to search YouTube and to find the audio-only stream, and Media3 (ExoPlayer) to play it.

For personal use only. Do not publish it.

## Get the APK

### ⬇️ [Download the latest APK](https://github.com/AG-ITAcademy/car-audio/releases/download/latest/app-debug.apk)

This link does not change. It always gives the newest build.
You must be signed in to GitHub, because the repo is private.

Each push to `main` builds the APK with GitHub Actions and replaces the file at this link.
Other places to get it:

- The release page: [Latest build](https://github.com/AG-ITAcademy/car-audio/releases/tag/latest)
- **Actions** > latest run > artifact `car-audio-apk` (a zip file)

The APK is signed with the fixed key in `app/debug.keystore`, so a new build installs over the old one.

## Install on the Pixel

1. Open the APK. Android asks to allow installs from this source (browser or Files). Allow it.
2. Tap **Install**. If Play Protect warns you, tap **More details** > **Install anyway**.

Or from a PC with USB debugging: `adb install -r app-debug.apk`

If Android Auto does not show the app (no "Unknown sources" setting), install it from the PC
with the Play Store as the installer:

```
adb install -r -i com.android.vending app-debug.apk
```

## Use

- Type a search. The app searches **Playlists** by default; tap **Videos** to search videos.
- Tap a playlist to play it from the first song. Its songs show, so you can also tap a song,
  **Play all** or **Shuffle**. Tap **+** on a playlist to add all its songs to the queue.
- Tap a video to play it. Tap **+** to add it to the queue.
- **Artists only** (on by default) hides AI-generated music. It keeps only videos with an
  "Artist - Song" title or from an official artist channel, and hides anything that mentions AI,
  Suno or Udio. It is a best guess: YouTube does not mark AI music.
- **Back** goes to the start of the song. Press it again within 4 seconds to go to the previous
  song. **Next** goes to the next song in the queue.
- When a new song starts, a short pop-up shows the artist and song name, also on top of Waze.
  To turn it off: Android Settings > Apps > Car Audio > Notifications > "New song pop-up".
- In the YouTube app, tap **Share** > **Car Audio** to play that video.
- Lock the screen: the audio continues. Controls are in the notification and on the lock screen.

## Android Auto

Android Auto shows only apps from the Play Store. There are two ways to show this app.

**A. Install with the Play Store as the installer (from a PC).** Uninstall first: an update
keeps the old installer record.

```
adb uninstall ro.ag.caraudio
adb install -i com.android.vending app-debug.apk
adb shell pm list packages -i ro.ag.caraudio
adb shell am force-stop com.google.android.projection.gearhead
```

The third command must show `installer=com.android.vending`. The last one restarts Android Auto.
Use `adb install -r -i com.android.vending app-debug.apk` for later updates.

**B. Allow unknown sources in Android Auto.**

1. On the phone, open **Settings** > **Connected devices** > **Connection preferences** > **Android Auto**.
2. Scroll to the bottom and tap **Version** 10 times. Tap **OK**.
3. Tap **⋮** (top-right) > **Developer settings**. Scroll down and enable **Unknown sources**.

Then, in the Android Auto settings, open **Customize launcher** and make sure **Car Audio** is on.
**Car Audio** shows in the car's app list, with **Recently played** and **Last search**.
Tap the search icon (🔍) on the car screen to search. Android Auto lets you type only when the
car is parked; while you drive, it uses voice input.
Voice search also works: "Play *something* on Car Audio". It plays the first playlist found.

## When it stops working

YouTube changes often. First, update `NewPipeExtractor` in `app/build.gradle.kts` to the latest
release. To find the error, connect the phone to a PC and run:

```
adb logcat -s CarAudio ExoPlayerImplInternal
```
