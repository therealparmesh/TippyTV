# Contributing to TippyTV

## Build locally

Install JDK 21, Android SDK 37, NDK 28.2.13676358, CMake 3.22.1, Go 1.27, and Python 3. Set `ANDROID_HOME` to your Android SDK directory. From the repository root:

```sh
python3 tools/prepare-mpv.py
python3 tools/prepare-phone.py
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The preparation scripts verify the pinned native dependencies, run the phone helper’s submission test, and prepare libraries for both TV architectures. See the [playback dependency guide](third_party/mpv/README.md) for upstream revisions and rebuild instructions.

## Run the checks

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease
```

To exercise remote navigation, guide access, stream recovery, and two-channel playback, install the debug APK on a dedicated emulator. The check replaces its app session. With FFmpeg installed, generate sample streams and start the fixture:

```sh
qa/make-media.sh
python3 qa/fixture_server.py
```

In another terminal, replace the example serial with the one from `adb devices`:

```sh
adb -s emulator-5562 install -r app/build/outputs/apk/debug/app-debug.apk
python3 qa/check_remote_playback.py emulator-5562
```

The default fixture address inside the emulator is `http://10.0.2.2:18798`. To use another port, set `JELLYFIN_QA_PORT` when starting the server and pass `--port` to the check.

## Commit changes

Use one [Conventional Commit](https://www.conventionalcommits.org/) per logical change. For example, `fix: preserve focus when returning to the guide` or `docs: clarify playlist setup`.

## Build a release

Update `versionName` and increment `versionCode` in `app/build.gradle.kts`. Run the checks, then build the unsigned release APK:

```sh
./gradlew :app:assembleRelease
```

Sign the APK with the existing distribution key so installed copies can update in place. Verify it with `apksigner verify`, then attach the signed APK to the matching GitHub release.
