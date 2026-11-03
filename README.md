# Peugeot Car Music

Android app that plays your local playlist automatically when the phone connects to your car over Bluetooth, and pauses it when the car disconnects.

## How it behaves

- **Start once:** open the app, grant permissions, choose the paired car, add songs and press **Start auto-play**. The app then watches for that exact Bluetooth device (by address); other speakers or headsets never trigger it.
- **Car connects:** when the car's audio link comes up, the playlist starts by itself (retrying a few times if the car is not ready yet).
- **Car disconnects:** playback pauses and the current song and position are saved.
- **Car reconnects:** playback resumes from the same position.
- The app is woken by Android when the car connects, even if its service was stopped. Pressing **Stop** ends monitoring.
- Plays local audio files you pick; it does not control Spotify or YouTube.

## Other features

- Add multiple songs, reorder (long-press and drag), delete (swipe left).
- Play / pause, previous, next, seek bar, shuffle, repeat (off / all / one).
- Five-second crossfade between tracks.
- Notification controls (previous, play/pause, next).
- **Event log** button: shows recent Bluetooth and playback events to diagnose problems.

## Keep it working on OPPO / ColorOS

Press **Background setup** in the app and:

1. Allow the battery exemption (the app asks once when you press Start).
2. Allow **Auto-launch** (Auto startup) for the app.
3. Set battery usage to **Allow background activity**, and lock the app in the recent apps list.

After restarting the phone, open the app once and press **Start auto-play** again.

## Build

Open the folder in Android Studio (SDK 36, JDK 17) and run **Build > Build APK(s)**, or push to GitHub: the workflow builds `app-debug.apk` (Actions > Build CarMusic APK > Artifacts > CarMusic-debug-apk).

## Credits

The Peugeot emblem comes from [Stellantis Japan's brand announcement](https://www.stellantis.jp/news/20210225_peugeot_new_ci).
