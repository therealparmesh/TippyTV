"""Check fullscreen focus and two-stream controls on an explicitly selected QA emulator.

Requires the debug APK installed and fixture_server.py running. Never targets a TV.
Pass --release-apk to test a release APK signed with the same key after seeding.
"""

import argparse
import json
import subprocess
import struct
import re
import time
import urllib.request
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial", help="QA emulator serial, for example emulator-5562")
parser.add_argument("--port", type=int, default=18798)
parser.add_argument(
    "--release-apk", help="Install this APK after seeding the test session"
)
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("Use a dedicated QA emulator. This check replaces its app session.")


def adb(*command, data=None):
    return subprocess.check_output(
        ["adb", "-s", args.serial, *command], input=data, timeout=25
    )


def request(path, data=None):
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(
        f"http://127.0.0.1:{args.port}{path}",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=10) as response:
        body = response.read()
        return json.loads(body) if body else None


def key(code):
    adb("shell", "input", "keyevent", str(code))
    time.sleep(0.3)


def nodes():
    for _ in range(3):
        result = adb("shell", "uiautomator", "dump", "/sdcard/live-tv-check.xml")
        if b"UI hierchary dumped to" in result:
            return list(
                ET.fromstring(adb("shell", "cat", "/sdcard/live-tv-check.xml")).iter(
                    "node"
                )
            )
        time.sleep(1)
    raise AssertionError("Could not capture a fresh UI hierarchy")


def expect_text(*labels):
    for _ in range(10):
        tree = nodes()
        visible = {node.get("text") for node in tree}
        if visible.issuperset(labels):
            return next(node for node in tree if node.get("text") == labels[0])
    raise AssertionError(f"Expected {labels}, saw {sorted(filter(None, visible))}")


def tap_text(label):
    node = expect_text(label)
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def cold_start():
    adb("shell", "am", "force-stop", "com.parmesh.livetv")
    adb("shell", "am", "start", "-n", "com.parmesh.livetv/.MainActivity")


def label(node):
    return (
        node.get("text")
        or node.get("content-desc")
        or next(
            (child.get("text") for child in node.iter("node") if child.get("text")), ""
        )
    )


def focused(text):
    tree = nodes()
    actual = [label(node) for node in tree if node.get("focused") == "true"]
    assert actual == [text], (text, actual)


def streams(expected):
    for _ in range(30):
        state = request("/qa/status")
        if state["open"] == expected and (expected != 0 or state["encodings"] == 0):
            return
        time.sleep(0.5)
    raise AssertionError(state)


def screenshot():
    raw = adb("exec-out", "screencap")
    width, height, pixel_format = struct.unpack_from("<III", raw)
    assert pixel_format == 1, ("Unexpected screenshot pixel format", pixel_format)
    return width, height, memoryview(raw)[-width * height * 4 :]


def pictures_visible(inset_aspect=16 / 9, *, preview=None):
    # Resizing can temporarily precede the next GPU buffer. Require real pixels
    # within a bounded interval, rather than accepting only a player status.
    for attempt in range(6):
        try:
            if preview is None:
                check_pictures(inset_aspect)
            else:
                width, _, pixels = screenshot()
                check_geometry(width, pixels, preview, 16 / 9)
            return
        except AssertionError:
            if attempt == 5:
                raise
            time.sleep(1)


def check_geometry(width, pixels, bounds, aspect):
    left, top, right, bottom = bounds
    w, h = right - left, bottom - top
    content_w, content_h = min(w, h * aspect), min(h, w / aspect)
    # Both fixture feeds carry a magenta marker near the top-right of the image.
    x = int(left + (w - content_w) / 2 + content_w * 0.94)
    y = int(top + (h - content_h) / 2 + content_h * 0.10)
    red, green, blue = pixels[(y * width + x) * 4 : (y * width + x) * 4 + 3]
    assert red > 160 and blue > 160 and green < 100, (
        "Picture edge is cropped, padded, or incorrectly scaled"
    )
    if content_w < w * 0.9:
        x, y = int(left + (w - content_w) / 4), int(top + h * 0.4)
        assert max(pixels[(y * width + x) * 4 : (y * width + x) * 4 + 3]) < 45, (
            "Pillarbox contains video or stale pixels"
        )


def check_pictures(inset_aspect):
    # Assert pixels, not just the player's "Live" status: detached native surfaces
    # can keep decoding while rendering an entirely black picture.
    width, height, pixels = screenshot()
    tree = nodes()
    inset = next(n for n in tree if n.get("content-desc") == "Muted inset")
    left, top, right, bottom = map(int, re.findall(r"\d+", inset.get("bounds")))
    assert abs((right - left) * (bottom - top) / (width * height) - 0.125) < 0.005, (
        "Inset is not one-eighth of playback area"
    )
    assert 0 < width - right < width * 0.03, "Inset is not near the right edge"
    if not any(n.get("text") == "Channels" for n in tree):
        assert 0 < height - bottom < height * 0.03, (
            "Inset did not return to the bottom corner"
        )
    check_geometry(width, pixels, (left, top, right, bottom), inset_aspect)
    region = (
        (left + 8) / width,
        (top + 8) / height,
        (right - 8) / width,
        (bottom - 8) / height,
    )
    for name, region in (("main", (0.1, 0.1, 0.45, 0.4)), ("inset", region)):
        left, top, right, bottom = region
        colorful = 0
        for y in range(int(height * top), int(height * bottom), 8):
            for x in range(int(width * left), int(width * right), 8):
                rgb = pixels[(y * width + x) * 4 : (y * width + x) * 4 + 3]
                colorful += max(rgb) > 180 and max(rgb) - min(rgb) > 120
        assert colorful > 30, (name, "picture is black or missing", colorful)


def controls_hidden():
    for _ in range(6):
        time.sleep(6)
        if not any(node.get("text") == "Channels" for node in nodes()):
            return
    raise AssertionError("Fullscreen controls did not hide after playback settled")


def preview_bounds():
    preview = next(n for n in nodes() if n.get("content-desc") == "Full screen")
    return tuple(map(int, re.findall(r"\d+", preview.get("bounds"))))


def preview_letterbox():
    left, top, right, bottom = preview_bounds()
    bar = ((bottom - top) - (right - left) * 9 / 16) / 2
    assert bar > 8, "Fixture needs a letterboxed preview"
    width, _, pixels = screenshot()
    for y in (int(top + bar / 2), int(bottom - bar / 2)):
        for x in range(left + (right - left) // 4, right - (right - left) // 4, 8):
            rgb = pixels[(y * width + x) * 4 : (y * width + x) * 4 + 3]
            assert max(rgb) < 45, "Preview letterbox contains colored or stale pixels"


def preview_blank_during_tune():
    left, top, right, bottom = preview_bounds()
    width, _, pixels = screenshot()
    for y in range(top + (bottom - top) // 2, bottom - 12, 8):
        for x in range(left + (right - left) // 4, right - (right - left) // 4, 8):
            rgb = pixels[(y * width + x) * 4 : (y * width + x) * 4 + 3]
            assert max(rgb) < 45, (
                "Outgoing picture is still visible during a channel change"
            )


request("/qa/guide_failure?enabled=1", {})
session = request(
    "/Users/AuthenticateByName", {"Username": "remote-check", "Pw": "fixture"}
)
seed = json.dumps(
    {
        "base": f"http://10.0.2.2:{args.port}",
        "token": session["AccessToken"],
        "userId": session["User"]["Id"],
    }
).encode()
adb("shell", "am", "force-stop", "com.parmesh.livetv")
adb("shell", "run-as", "com.parmesh.livetv", "mkdir", "-p", "files")
adb(
    "shell",
    "run-as",
    "com.parmesh.livetv",
    "sh",
    "-c",
    "'cat > files/qa-session.json'",
    data=seed,
)
assert b"INSTRUMENTATION_CODE: -1" in adb(
    "shell", "am", "instrument", "-w", "com.parmesh.livetv/.SeedSession"
), "Failed to seed the fixture account"
adb("shell", "run-as", "com.parmesh.livetv", "sh", "-c", "'rm -f cache/lineup-v1-*'")
if args.release_apk:
    adb("install", "--no-incremental", "-r", args.release_apk)
try:
    request("/qa/channel_failure?mode=hold", {})
    cold_start()
    expect_text("Channels", "Loading channels…")
finally:
    request("/qa/channel_failure?enabled=0", {})
expect_text("1203 channels")

key(84)
adb("shell", "input", "text", "zzzznomatch")
key(4)
focused("zzzznomatch")
key(20)
focused("Search")
key(23)
focused("Clear search")
assert not any(node.get("text") in ("−50", "+50") for node in nodes()), (
    "Empty list still has paging controls"
)
key(23)
focused("1  Amber One")
key(19)
focused("Search")
key(22)
key(23)
focused("All channels")
key(23)
focused("1  Amber One")
print("PASS: search and favorites empty-state recovery", flush=True)
key(21)
focused("−50")
key(22)
key(23)
focused("+50")
key(21)
key(23)
focused("−50")
key(19)
focused("1  Amber One")
print("PASS: skip controls retain focus until Up returns to the channel", flush=True)
adb("shell", "input", "keyevent", "--longpress", "23")
focused("Play channel")
key(20)
focused("Guide")
key(23)
focused("Later")
key(19)
focused("Try again")
request("/qa/guide_failure?enabled=0", {})
key(23)
assert any(node.get("text") == "Morning magazine" for node in nodes()), (
    "Guide retry did not restore listings"
)
key(23)  # Later.
key(21)
focused("Now")
key(23)
focused("Later")
assert any(node.get("text") == "Morning magazine" for node in nodes()), (
    "Now did not restore current listings"
)
key(4)
focused("1  Amber One")
print("PASS: guide failure, in-place retry, and focus return", flush=True)
key(92)
focused("1  Amber One")
# Slow but progressing media must not be torn down by a buffering deadline.
request("/qa/slow_start?enabled=1", {})
slow_baseline = request("/qa/status")["opened"]
key(23)
time.sleep(45)
assert request("/qa/status")["opened"] == slow_baseline + 1, (
    "Slow startup repeatedly reopened the channel"
)
assert (
    "main status=LIVE "
    in adb("shell", "dumpsys", "activity", "com.parmesh.livetv").decode()
), "Slow startup never reached playback"
preview_letterbox()
request("/qa/slow_start?enabled=0", {})
print(
    "PASS: slow startup finishes on the original session through Jellyfin HLS",
    flush=True,
)
request("/qa/open_delay?ms=8000", {})
switch_opened = request("/qa/status")["opened"]
try:
    key(20)
    focused("2  Blue Two")
    key(23)
    preview_blank_during_tune()
    assert request("/qa/status")["opened"] == switch_opened, (
        "Fixture did not delay the channel switch"
    )
finally:
    request("/qa/open_delay?ms=0", {})
streams(1)
pictures_visible(preview=preview_bounds())
key(4)
streams(0)
key(19)
focused("1  Amber One")
print(
    "PASS: a channel switch clears the previous frame before the new tune", flush=True
)

request("/qa/open_delay?ms=8000", {})
key(23)
pending = nodes()
assert any(
    "Opening channel" in n.get("text", "")
    and re.search(r"\d+ s elapsed", n.get("text", ""))
    for n in pending
), "Pending stream request has no elapsed status"
assert request("/qa/status")["open"] == 0, "Fixture did not delay the tune response"
key(127)  # Pause while PlaybackInfo is still pending, before a player exists.
streams(1)
request("/qa/open_delay?ms=0", {})
time.sleep(4)
assert any("Paused" in node.get("text", "") for node in nodes()), (
    "Pause during a pending tune was ignored"
)
key(22)
key(23)
focused("Channels")
assert any(node.get("text") == "Resume live" for node in nodes()), (
    "Entering fullscreen hid the paused-stream controls"
)
key(4)
focused("1  Amber One")
key(85)
key(20)
focused("2  Blue Two")
assert any(node.get("text") == "Watching · 1  Amber One" for node in nodes()), (
    "Preview identifies the selected channel instead of the playing channel"
)
request("/qa/open_failure?status=503", {})
before_requests = request("/qa/status")["open_requests"]
key(82)
focused("Play channel")
key(20)
focused("Play in inset")
key(23)
for _ in range(10):
    if any("Could not open this channel" in node.get("text", "") for node in nodes()):
        break
else:
    raise AssertionError("Fixture did not exercise the inset tune failure")
streams(1)
assert request("/qa/status")["open_requests"] == before_requests + 1, (
    "Server error triggered unnecessary compatible-stream negotiation"
)
request("/qa/open_failure?status=0", {})
key(82)
focused("Play channel")
key(20)
focused("Retry inset")
key(23)
streams(2)
time.sleep(4)
key(19)
focused("1  Amber One")
key(22)
focused("Full screen")
key(23)
controls_hidden()
pictures_visible()


def both_live():
    key(19)
    dump = adb("shell", "dumpsys", "activity", "com.parmesh.livetv").decode()
    return (
        len(re.findall(r"TippyMPV (?:main|inset) status=LIVE(?:_COMPATIBLE)? ", dump))
        == 2
    )


def swap_from_fullscreen():
    key(19)
    key(22)
    key(22)
    focused("Swap")
    key(23)
    controls_hidden()


geometry_opened = request("/qa/status")["opened"]
for rendition, aspect in (
    ("_hd", 16 / 9),
    ("_720", 16 / 9),
    ("_43", 4 / 3),
    ("", 16 / 9),
):
    request("/qa/rendition?name=" + rendition, {})
    time.sleep(12)
    controls_hidden()
    for attempt in range(12):
        try:
            w, h, pixels = screenshot()
            check_geometry(w, pixels, (0, 0, w, h), aspect)
            break
        except AssertionError:
            if attempt == 11:
                raise
            time.sleep(1)
    pictures_visible()
    if rendition == "_43":
        swap_from_fullscreen()
        pictures_visible(inset_aspect=4 / 3)
        swap_from_fullscreen()
        pictures_visible()
assert request("/qa/status")["opened"] == geometry_opened, (
    "Resolution change reopened a stream"
)
print(
    "PASS: 1080p → 720p → 4:3 → 16:9 preserve full picture, aspect ratio and PiP",
    flush=True,
)

before_opened = request("/qa/status")["opened"]
request("/qa/advance_live_window", {})
time.sleep(20)
assert both_live(), "Playback did not recover from an expired live window"
assert request("/qa/status")["opened"] == before_opened, (
    "Live-window recovery opened a new server stream"
)
print("PASS: stale live window recovers without a new server stream", flush=True)

request("/qa/media_failure?status=503", {})
time.sleep(3)
request("/qa/media_failure?status=0", {})
# Let cached media drain so stale Live status cannot conceal a failed reconnect.
time.sleep(10)
for _ in range(12):
    if both_live():
        break
    time.sleep(1)
else:
    raise AssertionError("Playback did not resume after a brief outage")
assert request("/qa/status")["opened"] == before_opened, "Brief outage reopened streams"
print("PASS: brief media outage resumes on existing streams", flush=True)

# Persistent failures must back off rather than continuously reopening tuners.
backoff_before = request("/qa/status")
request("/qa/media_failure?status=403", {})
time.sleep(35)
backoff_after = request("/qa/status")
assert backoff_after["media_failures"] > backoff_before["media_failures"]
assert backoff_after["open_requests"] - backoff_before["open_requests"] <= 8, (
    "Persistent failure caused a busy retry loop"
)
request("/qa/media_failure?status=0", {})
for _ in range(15):
    if both_live():
        break
    time.sleep(2)
else:
    raise AssertionError("Playback did not resume after backed-off retries")
assert (
    request("/qa/status")["converted_requests"] == backoff_before["converted_requests"]
)
streams(2)
print(
    "PASS: persistent failures back off and resume without enabling transcoding",
    flush=True,
)


# Only the old lease fails: recovery must obtain a fresh URL while the inset plays.
for mode in ("503", "403", "ended", "empty"):
    baseline = request("/qa/status")
    interrupted_at = time.monotonic()
    request("/qa/interrupt?channel=a&mode=" + mode, {})
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        state = request("/qa/status")
        if state["opened"] > baseline["opened"] and both_live():
            break
        time.sleep(2)
    else:
        raise AssertionError(("No fresh-session recovery", mode, state))
    assert state["opened"] == baseline["opened"] + 1, (
        "Other stream reopened",
        mode,
        state,
    )
    assert state["closed"] == baseline["closed"] + 1, ("Old lease leaked", mode, state)
    assert state["converted_requests"] == baseline["converted_requests"], (
        "Network recovery requested transcoding"
    )
    assert state["open"] == 2 and state["peak_open"] == 2, (
        "Recovery exceeded two tuner leases"
    )
    if mode == "ended":
        assert time.monotonic() - interrupted_at < 30, (
            "Ended stream waited too long to recover"
        )
    print(
        "PASS: fresh-session recovery for "
        + mode
        + ", inset preserved, no transcoding or tuner leak",
        flush=True,
    )

# A server outage during renegotiation must keep waiting, then recover.
baseline = request("/qa/status")
request("/qa/open_failure?status=503", {})
request("/qa/interrupt?channel=a&mode=403", {})
deadline = time.monotonic() + 90
while request("/qa/status")["open_requests"] == baseline["open_requests"]:
    assert time.monotonic() < deadline, "No attempt to refresh interrupted stream"
    time.sleep(2)
request("/qa/open_failure?status=0", {})
deadline = time.monotonic() + 60
while time.monotonic() < deadline:
    if request("/qa/status")["open"] == 2 and both_live():
        break
    time.sleep(2)
else:
    raise AssertionError("Recovery stopped after a failed PlaybackInfo request")
assert request("/qa/status")["converted_requests"] == baseline["converted_requests"]
print("PASS: server outage during recovery retries without transcoding", flush=True)
controls_hidden()

key(23)
focused("Channels")  # The reveal press must not also activate Channels.
key(85)  # Physical Play/Pause must pause the main picture, preserving the inset.
time.sleep(6)
assert any(node.get("text") == "Resume live" for node in nodes()), (
    "Paused playback lost its resume control"
)
streams(2)
key(3)
streams(0)
adb("shell", "am", "start", "-n", "com.parmesh.livetv/.MainActivity")
streams(2)
assert any(node.get("text") == "Resume live" for node in nodes()), (
    "Returning to the app resumed paused playback"
)
key(22)
focused("Resume live")
key(23)
key(22)
key(23)  # Swap without leaving fullscreen or opening another tuner.
streams(2)
controls_hidden()
pictures_visible()
key(19)
focused("Channels")
key(22)
key(22)
key(22)
focused("Close inset")
key(23)  # Close inset; focus must move to a surviving control.
focused("Channels")
streams(1)
controls_hidden()
key(4)  # Back also works while all controls are hidden.
focused("1  Amber One")
key(4)
streams(0)
print(
    "PASS: two streams, pause/resume, auto-hide, reveal without activation, swap, close inset, Back, tuner cleanup"
)

key(20)
key(20)
before_requests = request("/qa/status")["open_requests"]
key(23)  # The fixture has no media source for channel 3.
for _ in range(10):
    if any("Could not open this channel" in node.get("text", "") for node in nodes()):
        break
else:
    raise AssertionError("Fixture did not exercise failed tuning")
assert request("/qa/status")["open_requests"] == before_requests + 2, (
    "Missing media source did not try the compatible profile"
)
key(22)
key(23)
focused("Channels")
assert any(node.get("text") == "Try again" for node in nodes()), (
    "Entering fullscreen hid failed-stream recovery"
)
key(4)
key(4)
streams(0)
print("PASS: entering fullscreen preserves paused and failed-stream controls")

# Cancel recovery while paused or stopped; delayed work must never resurrect playback.
key(92)
key(23)
streams(1)
time.sleep(4)
request("/qa/interrupt?channel=a&mode=503", {})
key(127)
baseline = request("/qa/status")["open_requests"]
time.sleep(35)
assert request("/qa/status")["open_requests"] == baseline, "Paused stream reconnected"
key(126)
deadline = time.monotonic() + 75
while request("/qa/status")["open_requests"] == baseline:
    assert time.monotonic() < deadline, "Resuming a stalled stream did not reconnect"
    time.sleep(2)
streams(1)
request("/qa/interrupt?channel=a&mode=503", {})
time.sleep(10)
key(4)
streams(0)
baseline = request("/qa/status")["open_requests"]
time.sleep(35)
assert request("/qa/status")["open_requests"] == baseline, (
    "Stopped stream resurrected from pending recovery"
)
print(
    "PASS: pause cancels recovery, resume reconnects, stop cancels pending recovery",
    flush=True,
)

# Exercise overlapping native startup and channel replacement, then background
# while the last player is still initializing. Only the selected player may survive.
cold_start()
expect_text("1203 channels")
key(20)
focused("1  Amber One")
before_requests = request("/qa/status")["open_requests"]
adb("shell", "input", "keyevent", *(["23", "20", "23", "19"] * 4))
key(3)
time.sleep(3)
streams(0)
assert request("/qa/status")["open_requests"] > before_requests, (
    "Rapid tuning did not exercise startup"
)
crashes = adb("logcat", "-b", "crash", "-d")
assert b"Fatal signal" not in crashes and b"FATAL EXCEPTION" not in crashes, (
    "Player crashed during release regression"
)
print(
    "PASS: rapid channel replacement and background during startup release every tuner without a crash",
    flush=True,
)

# Data can trickle forever without producing a frame. Startup must end in a
# usable retry state, not an endless buffering/reconnect loop.
cold_start()
expect_text("1203 channels")
request("/qa/silent_start", {})
key(20)
key(23)
opened_before_timeout = request("/qa/status")["opened"]
time.sleep(95)
assert any("Could not play this stream" in n.get("text", "") for n in nodes()), (
    "Silent startup never reached an error state"
)
streams(0)
assert request("/qa/status")["opened"] == opened_before_timeout, (
    "Silent startup kept reopening sessions"
)
request("/qa/silent_start?enabled=0", {})
key(23)
streams(1)
time.sleep(6)
key(3)
streams(0)
print(
    "PASS: silent startup times out, releases its resources, and remains retryable",
    flush=True,
)


# A failed or incomplete refresh must keep the last complete lineup on disk.
try:
    for fault in ("empty", "repeat", "shrunk", "outage"):
        request("/qa/channel_failure?mode=" + fault, {})
        cold_start()
        expect_text("1203 channels · Refresh needed", "1  Amber One")
    request("/qa/channel_failure?mode=hold", {})
    cold_start()
    expect_text("1203 channels · Refreshing…")
    tap_text("Jump")
    tap_text("First channel")
    focused("1  Amber One")
    request("/qa/channel_failure?mode=reordered", {})
    expect_text("1203 channels")
    focused("1  Amber One")
finally:
    request("/qa/channel_failure?enabled=0", {})
print(
    "PASS: failed refreshes preserve the lineup, and a reorder preserves focus",
    flush=True,
)

# Playlist and Xtream profiles share a snapshot format containing stream and guide IDs.
tap_text("Profile")
tap_text("Switch profile")
tap_text("M3U playlist")
base = f"http://10.0.2.2:{args.port}/qa"
for label, value in (
    ("Profile name", "Fixture"),
    ("Playlist URL or Gist", base + "/channels.m3u"),
    ("Guide URL or Gist (optional)", base + "/guide.xml"),
):
    tap_text(label)
    adb("shell", "input", "text", value)
    if b"mInputShown=true" in adb("shell", "dumpsys", "input_method"):
        key(4)
tap_text("Continue")
expect_text("Playlist One", "Now · First program")


def playlist_generation(expected="LIVE", after=0):
    for _ in range(50):
        status = adb("shell", "dumpsys", "activity", "com.parmesh.livetv")
        match = re.search(rb"TippyMPV main status=([A-Z_]+) generation=(\d+)", status)
        if match and (expected is None or match[1].decode() == expected):
            generation = int(match[2])
            if generation > after:
                return generation
        time.sleep(0.5)
    raise AssertionError(
        f"Playlist playback did not reach {expected} after generation {after}"
    )


try:
    request("/qa/channel_failure?mode=hold", {})
    cold_start()
    expect_text("1 channel · Refreshing…", "Playlist One")
    tap_text("Playlist One")
    request("/qa/channel_failure?mode=outage", {})
    expect_text("1 channel · Refresh needed", "Now · Updated program")
    generation = playlist_generation()
    playlist_requests = request("/qa/status")["playlist_requests"]
    request("/qa/playlist_eof", {})
    generation = playlist_generation(after=generation)
    request("/qa/playlist_eof", {})
    playlist_generation("RETRY_WAIT", after=generation - 1)
    wait_started = time.monotonic()
    generation = playlist_generation(None, after=generation)
    assert time.monotonic() - wait_started >= 4, (
        "A brief recovery reset the retry delay"
    )
    playlist_generation(after=generation - 1)
    assert request("/qa/status")["playlist_requests"] == playlist_requests, (
        "Reopening a known URL after EOF tried to download the playlist"
    )

    tap_text("Favorites")
    expect_text("No favorites yet. Add a channel to find it here.")
    tap_text("All channels")
    key(82)
    tap_text("Add favorite")
    expect_text("★\u00a0\u00a0Playlist One")
    request("/qa/channel_failure?mode=changed", {})
    tap_text("Profile")
    tap_text("Refresh channels")
    expect_text("Replacement One", "Select a channel to start watching")
    tap_text("Favorites")
    expect_text("No favorites yet. Add a channel to find it here.")
    tap_text("All channels")
    expect_text("Replacement One")

    request("/qa/channel_failure?mode=hold", {})
    tap_text("Profile")
    tap_text("Switch profile")
    tap_text(f"{session['User']['Id']} · http://10.0.2.2:{args.port}")
    expect_text("1203 channels · Refreshing…")
    tap_text("Profile")
    expect_text("Switch profile")
    request("/qa/channel_failure?mode=expired", {})
    expect_text("Add a profile", "M3U playlist")
finally:
    request("/qa/channel_failure?enabled=0", {})
print(
    "PASS: cached playlist plays during refresh; replaced channels lose stale playback and favorites",
    flush=True,
)
