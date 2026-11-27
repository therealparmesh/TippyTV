# Contributing to TippyTV

## Prerequisites

| Tool        | Version                        |
| ----------- | ------------------------------ |
| JDK         | 21                             |
| Android SDK | 37                             |
| Android NDK | 28.2.13676358                  |
| CMake       | 3.22.1                         |
| Go          | 1.27                           |
| Python      | 3                              |
| FFmpeg      | Required for emulator fixtures |

Set `ANDROID_HOME` to your SDK directory and put `adb` on your `PATH`. Run the commands below from the repository root.

## Build

```sh
python3 tools/prepare-mpv.py
python3 tools/prepare-phone.py
./gradlew :app:assembleDebug
```

The preparation scripts verify and extract the pinned mpv libraries, then test and build the phone setup helper for both TV architectures. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. [Native dependency sources and rebuild instructions](third_party/mpv/README.md)

## Test

Run the unit tests and lint for both build types.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease
```

Use a dedicated Android emulator to check category browsing, search, favorites, remote focus, the guide, channel changes, picture-in-picture, and playback recovery. The check replaces its TippyTV session and clears its saved lineup. Generate the local test streams and start the fixture server in one terminal.

```sh
qa/make-media.sh
python3 qa/fixture_server.py
```

In another terminal, replace the example serial with your emulator's serial from `adb devices`.

```sh
adb -s emulator-5562 install -r -g app/build/outputs/apk/debug/app-debug.apk
python3 qa/check_remote_playback.py emulator-5562
```

The emulator reaches the fixture at `http://10.0.2.2:18798`. For another port, set `JELLYFIN_QA_PORT` on the server and pass the same port with `--port` to the check.

## Send a change

Keep each commit focused on one change and use [Conventional Commits](https://www.conventionalcommits.org/). For instance, `fix: preserve focus when closing the guide` describes both the change and where it matters.

## Release

Update `versionName` and increment `versionCode` in `app/build.gradle.kts`. Run the checks, then build the unsigned APK.

```sh
./gradlew :app:assembleRelease
```

The unsigned APK is at `app/build/outputs/apk/release/app-release-unsigned.apk`. Sign it with the key for the destination and verify it with `apksigner verify`.

- **GitHub.** Use the existing sideload signing key so installed copies can update in place. Attach the signed APK to the release for the matching tag.
- **Amazon Appstore.** Use the existing Amazon submission key. Select **Photo & Video → Video Player** and target Fire TVs. Include the public demo and matching source tag in the reviewer instructions. Check the release status in the Developer Console after submitting. [Amazon Appstore categories](https://developer.amazon.com/docs/app-submission/categories)
