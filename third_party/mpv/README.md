# Native playback dependencies

TippyTV uses libmpv and FFmpeg from the pinned mpv-android release. The app owns a separate libmpv handle for each picture through its JNI bridge. [JNI bridge](../../app/src/main/cpp/mpv.c)

## Prepare a build

From the repository root, run `python3 tools/prepare-mpv.py`. The script downloads the ARMv7 and ARM64 release APKs, verifies their SHA-256 checksums, extracts the unmodified shared libraries into `build/mpv/jni/<abi>`, and copies the CA certificate bundle to `build/mpv/assets`. It skips the upstream app’s singleton JNI wrapper, `libplayer.so`. Gradle uses CMake and the Android NDK to build TippyTV’s bridge. [Preparation script](../../tools/prepare-mpv.py) · [Pinned upstream release](https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17)

## Source revisions

| Component                                         | Revision                                                                                                         |
| ------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| mpv                                               | [0b7ed67](https://github.com/mpv-player/mpv/tree/0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6)                       |
| FFmpeg                                            | [094a2f8](https://github.com/FFmpeg/FFmpeg/tree/094a2f8a2a5e7fa64736e067de224ce28fdf5979)                        |
| mpv-android build scripts and dependency manifest | [fdf74f6](https://github.com/mpv-android/mpv-android/tree/fdf74f6830c47dbaa8a22ac79726e8303f1db5af/buildscripts) |

## Rebuild the libraries

Check out the pinned mpv-android revision and follow its `buildscripts/README.md`. Run `download.sh`, then build the `armv7l` and `arm64` targets using `buildall.sh`. Place the resulting libraries in `build/mpv/jni/armeabi-v7a` and `build/mpv/jni/arm64-v8a`, respectively. Keep the CA bundle in the assets directory.

## License

The upstream FFmpeg configuration enables GPL and version 3, so the native distribution uses GPLv3 terms. The mpv client API header carries its own permissive notice. [Native notices and source links](../../app/src/main/assets/licenses/mpv.txt) · [GPL text](../../app/src/main/assets/licenses/GPL-3.0.txt) · [Client header](client.h)
