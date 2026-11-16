#!/usr/bin/env bash
# Build the pinned upstream library with the HLS playlist-gap fix.
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
native_root="$repo_root/build/libvlc"
source_root="$native_root/source"
mkdir -p "$native_root"
if [ ! -d "$source_root/.git" ]; then
    git init "$source_root"
    git -C "$source_root" remote add origin https://code.videolan.org/videolan/libvlcjni.git
    git -C "$source_root" fetch --depth 1 origin 0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6
    git -C "$source_root" checkout --detach FETCH_HEAD
fi
if [ ! -d "$source_root/vlc/.git" ]; then
    git init "$source_root/vlc"
    git -C "$source_root/vlc" remote add origin https://code.videolan.org/videolan/vlc.git
    git -C "$source_root/vlc" fetch --depth 1 origin 84177b2273abc5c4300234e6e18b55a4d2dd5a03
    git -C "$source_root/vlc" checkout --detach FETCH_HEAD
    git -C "$source_root/vlc" config user.name 'TippyTV build'
    git -C "$source_root/vlc" config user.email 'build@localhost'
    (cd "$source_root/vlc" && git am --message-id "$source_root"/libvlc/patches/*.patch)
fi
(cd "$source_root" && ./buildsystem/get-vlc.sh)
if ! git -C "$source_root/vlc" apply --reverse --check "$repo_root/third_party/libvlc/hls-duration.patch" 2>/dev/null; then
    git -C "$source_root/vlc" apply "$repo_root/third_party/libvlc/hls-duration.patch"
fi
for arch in arm arm64; do
    case "$arch" in
        arm) triplet=arm-linux-androideabi; image=vlc-debian-android-3.0:20260701183656@sha256:b3bc028e3c7cb62d2f9910487a8835c02c13c5d2d8dcf09156dd09dd6f2eddca ;;
        arm64) triplet=aarch64-linux-android; image=vlc-debian-android:20260611225331@sha256:5f041ff50465aea803b93855d463f4b98f1d9f3be6628e304af2bf3fded6f98e ;;
    esac
    docker run --rm --platform linux/amd64 -v "$source_root:/work" -w /work \
        -e BUILD_ARCH="$arch" -e BUILD_TRIPLET="$triplet" "registry.videolan.org/$image" sh -ec '
        git config --global --add safe.directory /work
        git config --global --add safe.directory /work/vlc
        contrib_sha=$(cd vlc && extras/ci/get-contrib-sha.sh "android-$BUILD_ARCH")
        export VLC_PREBUILT_CONTRIBS_URL="https://artifacts.videolan.org/vlc-3.0/android-$BUILD_ARCH/vlc-contrib-$BUILD_TRIPLET-$contrib_sha.tar.zst"
        ./buildsystem/compile-libvlc.sh -a "$BUILD_ARCH" --release --license a --with-prebuilt-contribs
    '
done
curl --fail --location --output "$native_root/upstream.aar" \
    https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7.aar
python3 - "$native_root" <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import sys
import hashlib
root = Path(sys.argv[1])
assert hashlib.sha256((root / 'upstream.aar').read_bytes()).hexdigest() == 'b48dab96e0e90e34cce3861963500c144a5aadb1b249d501d6a4b104d849e61b', 'Unexpected upstream AAR'
abis = ('armeabi-v7a', 'arm64-v8a')
libs = root / 'source/libvlc/jni/libs'
for abi in abis:
    assert (libs / abi / 'libvlc.so').is_file(), f'Missing native build: {abi}'
with ZipFile(root / 'upstream.aar') as original, ZipFile(root / 'libvlc-patched.aar', 'w', ZIP_DEFLATED) as output:
    for entry in original.infolist():
        if not entry.filename.startswith('jni/'):
            output.writestr(entry, original.read(entry))
    for abi in abis:
        for library in sorted((libs / abi).glob('*.so')):
            output.write(library, f'jni/{abi}/{library.name}')
print(root / 'libvlc-patched.aar')
PY
