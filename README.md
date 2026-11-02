# Peugeot Car Music

An Android app that plays a local playlist when the phone connects to the paired car selected in the app. The app matches the saved Bluetooth device address so another speaker or headset will not trigger playback. It plays local audio files selected by the user; it does not control Spotify or YouTube.

## Features

- English dark interface with the official Peugeot emblem and app icon.
- Add multiple songs, remove tracks, and move tracks up or down in the playlist.
- Play, pause, previous, next, and a seek bar with elapsed and total time.
- Shuffle and repeat modes (off, all tracks, or one track).
- Five-second audio crossfade between tracks, including manual previous/next. Playlist rows show song names, reorder by long-press and drag, and remove a song with a left swipe.
- Foreground playback notification with previous, play/pause, and next controls.
- Automatic playback when the selected car connects.

## Use

1. Open the app and grant Nearby devices and notification permissions.
2. Pair the car in Android Bluetooth settings. If Bluetooth is off, approve Android's system prompt to turn it on.
3. Refresh paired devices and select the car.
4. Add audio files, then press **Start auto-play**. The new songs append to the playlist; use the arrows to reorder them.
5. Playback starts when the selected car connects. **Play playlist now** starts it manually.

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
