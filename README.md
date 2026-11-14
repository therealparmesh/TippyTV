# Jellyfin Live TV

A standalone, Darcula-colored Android TV and Fire OS client for Jellyfin Live TV. It has no movie, show, recording, or library screens. No Google Play Services are required.

## Use it

1. Sideload the APK on an Android-based Fire TV or Android TV device (Android 6/API 23 or later).
2. Open **Jellyfin Live TV** and enter your server address. A `.local` hostname works when the server advertises it over multicast DNS on your LAN. Choose **Quick Connect** to approve the TV from Jellyfin on a phone or browser, or choose **Use a password**. The app stores the session token with Android Keystore protection.
3. Press Select on a channel to play it. Press Right from the channel list to reach playback controls, then choose **Full screen**. **Back** returns to the selected channel. Full-screen controls hide after five seconds of playback. Press a direction or Select to show them again. Use Play/Pause on the remote or **Pause** in full screen to pause the main picture; **Resume live** returns to the live edge.
4. Hold **Select** on another channel, then choose **Play in inset**. The inset uses half the width and height of the playback area and stays muted. **Swap** moves audio and the large picture to the other channel. **Close inset** releases the second tuner.
5. **Search** accepts names and channel numbers. **Favorites** uses Jellyfin favorites. **Jump** goes to a letter or to the first or last channel. Use **−50** and **+50** to move through long lineups. Page, channel, and fast-forward or rewind buttons also skip 50 channels where supported.
6. **Guide** shows the selected channel’s schedule. **Earlier** and **Later** step through three-hour windows. **Now** returns to current listings.

Direct playback uses Media3/ExoPlayer for HLS and MPEG-TS. If playback falls behind the live window, the player first returns to the live edge on the same stream. A compatible Jellyfin HLS stream is attempted once after other playback errors, failed recovery, or prolonged buffering. You can also select **Try compatible stream** from channel actions. Conversion depends on server permissions and the source feed. Two-channel viewing stays within the app and requires two available decoder/tuner slots.

Channel metadata is loaded in 256-item pages with images and programme data omitted. A recycled list displays it; normalized search keys are computed once per channel and filtering runs off the main thread. Guide requests cover visible rows in batches of at most 16 channels, with a bounded, expiring cache. If the server has no programmes in the current window, the app avoids querying every channel.

## Build

Use JDK 21 and Android SDK 37.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The release APK in Gradle output is unsigned. The local preview is a signed, optimized build for sideloading. It has no embedded server address, credentials, or QA instrumentation.

## Local QA fixture

`qa/make-media.sh` generates two synthetic HLS feeds with FFmpeg. Run `python3 qa/fixture_server.py` after generating them. The fixture has 1,203 channels, a populated guide for the first two, favorites, and tuner counters at `/qa/status`. On an Android emulator, use server address `http://10.0.2.2:18798` (or set `JELLYFIN_QA_ORIGIN` and use adb reverse). Enter any nonempty fixture username. Never use a real password with the fixture.

With the debug APK installed on a dedicated QA emulator and `adb` on your path, run `python3 qa/check_remote_playback.py emulator-5562` (substitute its serial). This replaces the emulator’s app session with a fixture session and checks two-stream playback, full-screen controls, remote focus, and tuner cleanup. Use `--port` when the fixture runs on another port.
