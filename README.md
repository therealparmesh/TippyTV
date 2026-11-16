# TippyTV

A standalone, Darcula-colored Android TV and Fire OS client for Jellyfin Live TV. It has no movie, show, recording, or library screens. No Google Play Services are required.

## Use it

1. Sideload the APK on an Android-based Fire TV or ARM Android TV device (Android 6/API 23 or later).
2. Open **TippyTV** and enter your server address. A `.local` hostname works when the server advertises it over multicast DNS on your LAN. Choose **Quick Connect** to approve the TV from Jellyfin on a phone or browser, or choose **Use a password**. The app stores the session token with Android Keystore protection.
3. Press Select on a channel to play it. Select the playing channel again to enter full screen, or press Right to focus the video preview and press Select. **Back** returns to the selected channel. Full-screen controls start hidden. Press a direction or Select to show them; they hide after five seconds of playback. Use Play/Pause on the remote or **Pause** in full screen to pause the main picture; **Resume live** returns to the live edge.
4. Hold **Select** on another channel, then choose **Play in inset**. The muted inset occupies one-eighth of the playback area in the bottom-right corner and moves above the controls while they are visible. **Swap** moves audio and the large picture to the other channel. **Close inset** releases the second tuner.
5. **Search** accepts names and channel numbers. **Favorites** uses Jellyfin favorites. **Jump** goes to a letter or to the first or last channel. Press Left from any channel to reach **−50** and **+50**. Press Up to return to the selected channel. Page, channel, and fast-forward or rewind buttons also skip 50 channels where supported.
6. **Guide** shows the selected channel’s schedule. **Earlier** and **Later** step through three-hour windows. **Now** returns to current listings.

Playback uses **mpv only**, with an independent libmpv instance for each picture. Hardware decoding is preferred, with software decoding available when a codec cannot use the device decoder. The initial/rebuffer target is two seconds; read-ahead targets six seconds and is capped at 16 MiB per picture. There is no disk media cache. FFmpeg retries transient network and HTTP 5xx failures with bounded backoff before app-level session recovery. Swapping changes the pictures' bounds and audio volume without replacing their native rendering surfaces.

After playback starts, 30 seconds without another rendered frame requests a fresh Jellyfin session. An ended or failed stream schedules a fresh session after 30 seconds. Recovery releases the old player and tuner before opening another, preserves the other picture, and stops when playback is closed or paused. **Resume live** opens a fresh session at the live edge. **Try compatible stream** asks Jellyfin for H.264/AAC HLS; conversion depends on server permissions and the source feed.

The app packages unmodified native mpv/FFmpeg binaries from the pinned upstream mpv-android release. The upstream app and its singleton JNI player wrapper are not packaged. Source/build links and licenses are in `third_party/mpv/README.md` and `app/src/main/assets/licenses`.

Channel metadata is loaded in 256-item pages with images and programme data omitted. A recycled list displays it; normalized search keys are computed once per channel and filtering runs off the main thread. Guide requests cover visible rows in batches of at most 16 channels, with a bounded, expiring cache. If the server has no programmes in the current window, the app avoids querying every channel.

## Build

Use JDK 21, Android SDK 37, NDK 28.2.13676358, and CMake 3.22.1. Run `python3 tools/prepare-mpv.py` once to fetch and checksum-verify the pinned ARMv7/ARM64 libraries and CA bundle. Gradle builds the app's small, per-player JNI bridge; subsequent builds reuse the downloaded libraries under `build/mpv`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The release APK in Gradle output is unsigned. The local preview is a signed, optimized build for sideloading. It has no embedded server address, credentials, or QA instrumentation.

## Local QA fixture

`qa/make-media.sh` generates two synthetic HLS feeds with FFmpeg. Run `python3 qa/fixture_server.py` after generating them. The fixture has 1,203 channels, a populated guide for the first two, favorites, and tuner counters at `/qa/status`. On an Android emulator, use server address `http://10.0.2.2:18798` (or set `JELLYFIN_QA_ORIGIN` and use adb reverse). Enter any nonempty fixture username. Never use a real password with the fixture.

With the debug APK installed on a dedicated QA emulator and `adb` on your path, run `python3 qa/check_remote_playback.py emulator-5562` (substitute its serial). This replaces the emulator’s app session with a fixture session and checks two-stream playback, full-screen controls, remote focus, and tuner cleanup. Use `--port` when the fixture runs on another port.
