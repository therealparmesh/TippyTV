# TippyTV

A standalone, Darcula-colored Android TV and Fire OS client for Jellyfin Live TV. It has no movie, show, recording, or library screens. No Google Play Services are required.

## Use it

1. Sideload the APK on an Android-based Fire TV or ARM Android TV device (Android 6/API 23 or later).
2. Open **TippyTV** and enter your server address. A `.local` hostname works when the server advertises it over multicast DNS on your LAN. Choose **Quick Connect** to approve the TV from Jellyfin on a phone or browser, or choose **Use a password**. The app stores the session token with Android Keystore protection.
3. Press Select on a channel to play it. Select the playing channel again to enter full screen, or press Right to focus the video preview and press Select. **Back** returns to the selected channel. Full-screen controls start hidden. Press a direction or Select to show them; they hide after five seconds of playback. Use Play/Pause on the remote or **Pause** in full screen to pause the main picture; **Resume live** returns to the live edge.
4. Hold **Select** on another channel, then choose **Play in inset**. The muted inset occupies one-eighth of the playback area in the bottom-right corner and moves above the controls while they are visible. **Swap** moves audio and the large picture to the other channel. **Close inset** releases the second tuner.
5. **Search** accepts names and channel numbers. **Favorites** uses Jellyfin favorites. **Jump** goes to a letter or to the first or last channel. Press Left from any channel to reach **−50** and **+50**. Press Up to return to the selected channel. Page, channel, and fast-forward or rewind buttons also skip 50 channels where supported.
6. **Guide** shows the selected channel’s schedule. **Earlier** and **Later** step through three-hour windows. **Now** returns to current listings.

Live streams use Jellyfin’s negotiated HLS endpoint instead of bypassing the server with provider URLs. The normal profile allows video/audio stream copy; Jellyfin decides whether the source needs conversion. Closing either picture stops its server remux/encoding job and live-TV lease.

Playback uses **mpv only**, with an independent libmpv instance for each picture. Video is decoded directly into an Android surface, avoiding GPU image-reader and copied-decoder buffers that can produce green padding on TV hardware. Each picture keeps its aspect ratio when resized. Use **Try compatible stream** if the device cannot decode a channel. The initial/rebuffer target is 2.5 seconds; read-ahead targets five seconds and is capped at 16 MiB per picture. There is no disk media cache. FFmpeg retries transient network and HTTP 5xx failures with bounded backoff before app-level session recovery. Swapping changes the pictures' bounds and audio volume without replacing their native rendering surfaces.

Startup with no rendered video is limited to 90 seconds, then shows a retryable error. After playback starts, 30 seconds without another rendered frame requests a fresh Jellyfin session. An ended or failed stream schedules a fresh session after 2.5 seconds, backing off to 30 seconds if repeated attempts cannot play. If Jellyfin itself is unavailable, server retries wait 30 seconds. Recovery releases the old player and tuner before opening another, preserves the other picture, and stops when playback is closed or paused. **Resume live** opens a fresh session at the live edge. **Try compatible stream** explicitly requests H.264/AAC conversion; it requires server transcoding permission. Audio/video output initialization failures show an error instead of endlessly retrying the same decoder.

Loading messages show the current stage and elapsed seconds. Buffering also reports actual cached media seconds; retry waits show a countdown. These details update once a second only while loading.

Playback timing uses separate budgets for separate jobs:

| Timing | Value | Purpose |
| --- | --- | --- |
| Initial/rebuffer target | 2.5 seconds of media | Small cushion before starting or resuming |
| Read-ahead target | 5 seconds, capped at 16 MiB per picture | Absorb short delivery gaps |
| Player status sampling | 500 ms | Observe state and rendered-frame progress |
| Native network retry sleeps | Up to 4 retries, 4 seconds per delay, 10 seconds total | Retry transient transport failures; network I/O time is separate |
| Native network timeout | 60 seconds | Bound an individual stalled network operation |
| No first video frame | 90 seconds after player initialization | Stop and show a retryable error |
| No new frame after playback starts | 30 seconds | Reopen the live session; paused time is excluded |
| Ended-stream retry | 2.5, 5, 10, 15, then 30 seconds | Back off repeated failures; playback resets the delay |
| Jellyfin unavailable during recovery | 30 seconds between attempts | Avoid hammering an unavailable server |
| Jellyfin API timeouts | 6-second connect, 15-second read, 24-second whole call | Bound metadata/session requests separately from media playback |

These are targets and deadlines, not a guaranteed channel-opening time. Server negotiation precedes player startup, and live segments may arrive unevenly. Neither retry waits nor the 90-second startup deadline reserve that much media in memory. A delivery gap longer than the available buffer can still pause playback; reducing the startup cushion does not prevent that.

The app packages unmodified native mpv/FFmpeg binaries from the pinned upstream mpv-android release. The upstream app and its singleton JNI player wrapper are not packaged. Source/build links and licenses are in `third_party/mpv/README.md` and `app/src/main/assets/licenses`.

Channel metadata is loaded in 256-item pages with images and programme data omitted. A recycled list displays it; normalized search keys are computed once per channel and filtering runs off the main thread. Guide requests cover visible rows in batches of at most 16 channels, with a bounded, expiring cache. If the server has no programmes in the current window, the app avoids querying every channel.

## Build

Use JDK 21, Android SDK 37, NDK 28.2.13676358, and CMake 3.22.1. Run `python3 tools/prepare-mpv.py` once to fetch and checksum-verify the pinned ARMv7/ARM64 libraries and CA bundle. Gradle builds the app's small, per-player JNI bridge; subsequent builds reuse the downloaded libraries under `build/mpv`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The release APK in Gradle output is unsigned. The local preview is a signed, optimized build for sideloading. It has no embedded server address, credentials, or QA instrumentation.

## Local QA fixture

`qa/make-media.sh` generates two synthetic HLS feeds with FFmpeg. Run `python3 qa/fixture_server.py` after generating them. The fixture has 1,203 channels, a populated guide for the first two, favorites, and tuner counters at `/qa/status`. On an Android emulator, use server address `http://10.0.2.2:18798`. Set `JELLYFIN_QA_PORT` to use another port. Enter any nonempty fixture username. Never use a real password with the fixture.

With the debug APK installed on a dedicated QA emulator and `adb` on your path, run `python3 qa/check_remote_playback.py emulator-5562` (substitute its serial). This replaces the emulator’s app session with a fixture session and checks two-stream playback, full-screen controls, remote focus, and tuner cleanup. Use `--port` when the fixture runs on another port.

## License

TippyTV is licensed under [GNU GPL version 3](LICENSE) (GPL-3.0-only). Bundled dependencies retain their own copyright notices and licenses; see [native dependency sources and build instructions](third_party/mpv/README.md).
