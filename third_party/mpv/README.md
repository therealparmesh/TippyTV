# Native mpv dependency

TippyTV embeds independent libmpv handles through `app/src/main/cpp/mpv.c`.
There is no dependency on the upstream Android app or its global/singleton JNI wrapper.

`python3 tools/prepare-mpv.py` downloads the ARMv7 and ARM64 APKs from the
[mpv-android 2026-09-17 release](https://github.com/mpv-android/mpv-android/releases/tag/2026-09-17),
verifies their SHA-256 digests, and extracts the unmodified mpv/FFmpeg shared libraries
and CA certificate bundle. `libplayer.so` is deliberately excluded. The APKs are
build inputs only; neither their app code nor their resources are bundled.

Pinned source revisions:

- [mpv](https://github.com/mpv-player/mpv/tree/0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6)
- [FFmpeg](https://github.com/FFmpeg/FFmpeg/tree/094a2f8a2a5e7fa64736e067de224ce28fdf5979)
- [Android build scripts and dependency manifest](https://github.com/mpv-android/mpv-android/tree/fdf74f6830c47dbaa8a22ac79726e8303f1db5af/buildscripts)

To rebuild the upstream native libraries, check out the pinned mpv-android revision,
follow its `buildscripts/README.md`, run `download.sh`, and build the `armv7l` and
`arm64` targets with `buildall.sh`. The release notes list every native dependency
revision. Place the resulting shared libraries under `build/mpv/jni/<abi>`;
TippyTV's JNI bridge builds with the Android NDK via CMake.

The upstream native build enables GPL/version3 in FFmpeg. The native distribution
therefore uses GPLv3 terms; it is not an LGPL-only build. See the bundled GPL-3.0
license, upstream copyright notices and source/build links in
`app/src/main/assets/licenses/mpv.txt`. The client API header retains its own
permissive license notice. The app-specific JNI bridge is maintained here.
