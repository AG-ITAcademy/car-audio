# Car Audio

A personal Android app. It plays the audio of YouTube videos in the background, with no ads.
It shows in Android Auto. It uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
to search YouTube and to find the audio-only stream, and Media3 (ExoPlayer) to play it.

For personal use only. Do not publish it.

## Get the APK

Each push to `main` builds the APK with GitHub Actions.

- On the phone: open the **Releases** page of this repo, release **Latest build**, and download `app-debug.apk`.
- Or: **Actions** > latest run > artifact `car-audio-apk` (a zip file).

The APK is signed with the fixed key in `app/debug.keystore`, so a new build installs over the old one.

## Install on the Pixel

1. Open the APK. Android asks to allow installs from this source (browser or Files). Allow it.
2. Tap **Install**. If Play Protect warns you, tap **More details** > **Install anyway**.

Or from a PC with USB debugging: `adb install -r app-debug.apk`

## Use

- Type a search. The app searches **Playlists** by default; tap **Videos** to search videos.
- Tap a playlist to open it, then **Play all** or **Shuffle**. Tap ▶ on a playlist to play it at once.
- Tap a video to play it. Tap **+** to add it to the queue.
- In the YouTube app, tap **Share** > **Car Audio** to play that video.
- Lock the screen: the audio continues. Controls are in the notification and on the lock screen.

## Android Auto

Android Auto shows only apps from the Play Store, unless you allow unknown sources:

1. On the phone, open **Settings** > **Connected devices** > **Connection preferences** > **Android Auto**.
2. Scroll down and tap **Version** 10 times. This enables developer mode.
3. Tap the menu (⋮) > **Developer settings** > enable **Unknown sources**.

Then **Car Audio** shows in the car's app list, with **Recently played** and **Last search**.
Voice search also works: "Play *something* on Car Audio". It plays the first playlist found.

Without the step above, the audio still plays in the car through Bluetooth or through the
Android Auto "now playing" screen of the last media app.

## When it stops working

YouTube changes often. First, update `NewPipeExtractor` in `app/build.gradle.kts` to the latest
release. To find the error, connect the phone to a PC and run:

```
adb logcat -s CarAudio ExoPlayerImplInternal
```
