# Jellyfin Live TV

A standalone, Darcula-colored Android TV and Fire OS client for Jellyfin Live TV. It has no movie, show, recording, or library screens. No Google Play Services are required.

## Use it

1. Sideload the APK on an Android-based Fire TV or Android TV device (Android 6/API 23 or later).
2. Open **Jellyfin Live TV** and enter your Jellyfin server, username, and password. The app keeps only an Android Keystore-protected session token.
3. Select a channel to tune. **Watch** expands the video. **Back** returns to the list.
4. Hold **Select** on another channel, then choose **Play in inset**. The inset uses half the width and height of the playback area and stays muted. **Swap** moves audio and the large picture to the other channel. **Close inset** releases the second tuner.
5. **Search** accepts names and channel numbers. **Favorites** uses Jellyfin favorites. **Jump** goes to a letter, first/last, or ±50 channels. Page/channel and fast-forward/rewind keys also skip 50 where the remote sends those keys.
6. **Guide** shows the selected channel’s schedule. **Earlier** and **Later** step through three-hour windows.

Direct playback uses Media3/ExoPlayer for HLS and MPEG-TS. A compatible Jellyfin HLS stream is attempted once after a playback error or prolonged buffering. You can also select **Try compatible stream** from channel actions. Conversion depends on server permissions and the source feed. Two-channel viewing stays within the app and requires two available decoder/tuner slots.

Channel metadata is loaded in 256-item pages with images and programme data omitted. A recycled list displays it; normalized search keys are computed once per channel and filtering runs off the main thread. Guide requests cover visible rows in batches of at most 16 channels, with a bounded, expiring cache. If the server has no programmes in the current window, the app avoids querying every channel.

## Build

Use JDK 21 and Android SDK 37.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The release APK in Gradle output is unsigned. The local preview is a signed, optimized build for sideloading. It has no embedded server address, credentials, or QA instrumentation.

## Local QA fixture

`qa/make-media.sh` generates two synthetic HLS feeds with FFmpeg. Run `python3 qa/fixture_server.py` after generating them. The fixture has 1,203 channels, a populated guide for the first two, favorites, and tuner counters at `/qa/status`. On an Android emulator, use server address `http://10.0.2.2:18798` (or set `JELLYFIN_QA_ORIGIN` and use adb reverse). Enter any nonempty fixture username. Never use a real password with the fixture.
