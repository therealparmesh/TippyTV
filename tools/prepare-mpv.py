#!/usr/bin/env python3
"""Fetch checksum-pinned upstream native binaries; do not bundle the singleton app wrapper."""

from pathlib import Path
from urllib.request import urlopen
from zipfile import ZipFile
import hashlib

root = Path(__file__).resolve().parent.parent / "build/mpv"
root.mkdir(parents=True, exist_ok=True)
checksums = {
    "armeabi-v7a": "30c63f5528530f56cdece5d6466c749976f01bdcd8c8e67ae76e70a54b7243aa",
    "arm64-v8a": "0b0e0a534dd6b8d2b117f06952e0600064ad97702e48bff02543e3e32107803d",
}
for abi, checksum in checksums.items():
    filename = f"app-default-{abi}-release.apk"
    apk = root / filename
    if not apk.exists() or hashlib.sha256(apk.read_bytes()).hexdigest() != checksum:
        with urlopen(
            f"https://github.com/mpv-android/mpv-android/releases/download/2026-09-17/{filename}",
            timeout=60,
        ) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != checksum:
            raise RuntimeError(f"Checksum mismatch: {filename}")
        apk.write_bytes(data)
    with ZipFile(apk) as archive:
        dest = root / "jni" / abi
        dest.mkdir(parents=True, exist_ok=True)
        for entry in archive.namelist():
            if (
                entry.startswith(f"lib/{abi}/")
                and entry.endswith(".so")
                and not entry.endswith("/libplayer.so")
            ):
                (dest / Path(entry).name).write_bytes(archive.read(entry))
        assets = root / "assets"
        assets.mkdir(exist_ok=True)
        (assets / "cacert.pem").write_bytes(archive.read("assets/cacert.pem"))
    print(f"Prepared pinned mpv libraries for {abi}")
