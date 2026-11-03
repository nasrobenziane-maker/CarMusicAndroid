# Peugeot Car Music

An Android app that plays a local playlist when the phone connects to the paired car selected in the app. The app matches the saved Bluetooth device address so another speaker or headset will not trigger playback. It plays local audio files selected by the user; it does not control Spotify or YouTube.

## Features

- English dark interface with the official Peugeot emblem and app icon.
- Add multiple songs, remove tracks, and move tracks up or down in the playlist.
- Play, pause, previous, next, and a seek bar with elapsed and total time.
- Shuffle and repeat modes (off, all tracks, or one track).
- Five-second audio crossfade between tracks, including manual previous/next. The next track is prepared and starts fading in during the final five seconds of the current track; playback also continues to the next track if the fade player is not ready in time. The current track and position are saved every few seconds and when playback pauses or the car disconnects; playback resumes from the saved position when the car reconnects. Starting auto-play also checks whether the selected car is already connected. Add songs supports multi-select. Playlist songs are numbered; swipe left to reveal the red delete button. Playlist rows show song names, reorder by long-press and drag, and remove a song with a left swipe.
- Foreground playback notification with previous, play/pause, and next controls.
- Automatic playback when the selected car connects.
- Incoming and outgoing cellular calls are detected through Android call-state monitoring. Playback pauses, crossfade is canceled, the current position is saved, and voice listening pauses. Playback resumes after the call only if it was playing before the interruption. For VoIP calls, the app also checks audio focus and communication audio mode, but some VoIP apps/devices do not expose a detectable signal.

## Use

1. Open the app and grant Nearby devices, notification, and Phone state permissions. Phone state is used only to detect cellular call state; the app does not read or store caller numbers.
2. Pair the car in Android Bluetooth settings. If Bluetooth is off, approve Android's system prompt to turn it on.
3. Refresh paired devices and select the car.
4. Add audio files, then press **Start auto-play**. The new songs append to the playlist; use the arrows to reorder them.
5. Playback starts when the selected car connects. The app remembers that paired car by its Bluetooth address, checks its A2DP connection when Bluetooth is turned on, and watches for its connection event. Android's Bluetooth system performs the actual reconnection for paired devices; if Android does not reconnect it, connect the car from Bluetooth settings. **Play playlist now** starts it manually.

Android 13 and later do not allow ordinary apps targeting API 33+ to toggle Bluetooth directly. If the car disconnects for 60 seconds, the app offers a notification shortcut to Bluetooth settings; turn Bluetooth off there yourself.

## Build APK

Open this folder in Android Studio and wait for Gradle sync, then choose **Build > Build APK(s)**. The project needs Android SDK 36 and JDK 17. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions also builds the debug APK on every push to `main` or `master`. Download it from **Actions > Build CarMusic APK > Artifacts > CarMusic-debug-apk**.

## Android notes

- Start monitoring while the app is open. Android keeps the foreground service visible with a notification while it waits for the car.
- After restarting the phone, open the app and start monitoring again.
- Battery management on some phones may stop background services; allow the app to run unrestricted if that happens.
- Music is selected using Android's file picker; the app does not request access to the entire music library.

## Peugeot emblem

The emblem image in the app resources is sourced from [Stellantis Japan's official Peugeot brand announcement](https://www.stellantis.jp/news/20210225_peugeot_new_ci).

## Offline voice commands (version 1.3)

- While the app is open, tap **Enable voice commands** and grant microphone permission. Android requires a visible, user-initiated start for reliable microphone foreground access on recent Android versions.
- The app listens only while the selected car is connected and recognizes the fixed English phrases **car play**, **car stop**, and **car next**. Recognition is processed locally by Vosk; audio is not uploaded.
- **car play** resumes the current track, **car stop** pauses and saves its position, and **car next** plays the next track.
- A short vibration confirms a recognized command. Recognized phrases and confidence decisions appear in **Event log**.
- Adjust the command confidence threshold if the cabin noise causes missed commands or accidental recognition.
- Gradle downloads the Vosk small English model from the official Vosk model host and prepares it during Android Studio or GitHub Actions builds. The model is generated under `app/build` and is not committed to the repository.
- The model requires substantial phone memory and voice accuracy depends on the car microphone routing, music volume, cabin noise, and accent. Test it in the actual car. Oppo/Realme battery settings can still stop background services.
