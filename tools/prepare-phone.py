"""Build the TV's HTTPS setup helper with the pinned Lancert client and Android NDK."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
build = root / "build/phone"
module = json.loads(
    subprocess.check_output(
        ["go", "mod", "download", "-json", "go.lucor.dev/lancert-cli@v0.6.0"]
    )
)
if module["Sum"] != "h1:1JJr0ttYRFCaS8BcERkYPX21k7lVpVnBGSshc3PayQA=":
    raise RuntimeError("Lancert source checksum mismatch")
# Build alongside the pinned CLI so provisioning uses its existing command implementation.
with tempfile.TemporaryDirectory(prefix="tippytv-phone-") as staging:
    source = Path(staging)
    for name in ["go.mod", "go.sum", "LICENSE", "internal"]:
        (source / name).symlink_to(Path(module["Dir"]) / name)
    (source / "cmd").mkdir()
    (source / "cmd/tippytv").symlink_to(root / "phone")
    subprocess.run(["go", "test", "./cmd/tippytv"], cwd=source, check=True)
    # Ship the notices for the Go code actually linked into the helper.
    notices = build / "assets/phone-licenses"
    notices.mkdir(parents=True, exist_ok=True)
    modules = subprocess.check_output(
        [
            "go",
            "list",
            "-deps",
            "-f",
            "{{if .Module}}{{.Module.Path}} {{.Module.Dir}}{{end}}",
            "./cmd/tippytv",
        ],
        cwd=source,
        text=True,
    )
    for entry in set(modules.splitlines()):
        if entry.strip():
            name, directory = entry.split(" ", 1)
            shutil.copyfile(
                Path(directory) / "LICENSE", notices / (name.replace("/", "-") + ".txt")
            )
    goroot = subprocess.check_output(["go", "env", "GOROOT"], text=True).strip()
    shutil.copyfile(Path(goroot) / "LICENSE", notices / "go.txt")
    sdk = Path(
        os.environ.get("ANDROID_HOME")
        or os.environ.get("ANDROID_SDK_ROOT")
        or Path.home() / "Library/Android/sdk"
    )
    prebuilt = sdk / "ndk/28.2.13676358/toolchains/llvm/prebuilt"
    toolchain = next(
        prebuilt.glob(("darwin" if sys.platform == "darwin" else "linux") + "-*/bin")
    )
    for abi, arch, compiler in [
        ("armeabi-v7a", "arm", "armv7a"),
        ("arm64-v8a", "arm64", "aarch64"),
    ]:
        out = build / "jni" / abi / "libphonesetup.so"
        out.parent.mkdir(parents=True, exist_ok=True)
        cc = (
            compiler
            + ("-linux-androideabi" if arch == "arm" else "-linux-android")
            + "23-clang"
        )
        env = dict(
            os.environ,
            GOOS="android",
            GOARCH=arch,
            GOARM="7",
            CGO_ENABLED="1",
            CC=str(toolchain / cc),
        )
        subprocess.run(
            [
                "go",
                "build",
                "-trimpath",
                "-buildmode=pie",
                "-ldflags=-s -w",
                "-o",
                str(out),
                "./cmd/tippytv",
            ],
            cwd=source,
            env=env,
            check=True,
        )
