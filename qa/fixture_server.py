from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
from datetime import datetime, timezone
from pathlib import Path
import json, time, threading, os, secrets

ROOT = Path(os.environ.get("JELLYFIN_QA_MEDIA", "/tmp/jellyfin-live-fixture"))
PORT = int(os.environ.get("JELLYFIN_QA_PORT", "18798"))
start = time.time()
state = {
    "open": {},
    "encodings": set(),
    "opened": 0,
    "closed": 0,
    "guide": 0,
    "favorites": set(),
    "writes": 0,
    "tokens": set(),
    "logins": 0,
    "logouts": 0,
    "open_at_logout": None,
    "replacement": False,
    "guide_failure": False,
    "channel_failure": "",
    "playlist_requests": 0,
    "playlist_eof": False,
    "sequence_offset": 0,
    "rendition": "",
    "silent_start": False,
    "media_failure": 0,
    "media_failures": 0,
    "open_delay_ms": 0,
    "open_failure": 0,
    "open_requests": 0,
    "converted_requests": 0,
    "interrupted": {},
    "peak_open": 0,
    "slow_start": False,
    "slow_leases": set(),
}
quick_connect = {}
lock = threading.Lock()
channel_changes = threading.Condition(lock)
channels = [
    {"Id": "a", "Name": "Amber One", "ChannelNumber": "1"},
    {"Id": "b", "Name": "Blue Two", "ChannelNumber": "2"},
]
channels += [
    {"Id": f"c{i}", "Name": f"Channel {i:04d}", "ChannelNumber": str(i)}
    for i in range(3, 1204)
]


def iso(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).isoformat()


def timestamp(query, key, default):
    value = query.get(key, [None])[0]
    return (
        datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
        if value
        else default
    )


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def result(self, status, data=None, kind="application/json"):
        if data is None:
            raw = b""
        elif isinstance(data, bytes):
            raw = data
        else:
            raw = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        u = urlsplit(self.path)
        q = parse_qs(u.query)
        path = u.path
        if path == "/qa/status":
            with lock:
                out = {
                    **state,
                    "open": len(state["open"]),
                    "encodings": len(state["encodings"]),
                    "favorites": sorted(state["favorites"]),
                    "tokens": len(state["tokens"]),
                    "slow_leases": len(state["slow_leases"]),
                }
            return self.result(200, out)
        if path == "/QuickConnect/Connect":
            with lock:
                attempt = quick_connect.get(q.get("secret", [""])[0])
            return self.result(200, attempt) if attempt else self.result(404)
        if path == "/qa/channels.m3u":
            with channel_changes:
                state["playlist_requests"] += 1
                channel_changes.wait_for(
                    lambda: state["channel_failure"] != "hold", timeout=20
                )
                fault = state["channel_failure"]
            if fault in ("outage", "hold"):
                return self.result(503)
            lineup = (
                b'#EXTM3U\n#EXTINF:-1 tvg-id="a",Replacement One\n/b.m3u8\n'
                if fault == "changed"
                else b'#EXTM3U\n#EXTINF:-1 tvg-id="a",Playlist One\n/a.m3u8?direct=1\n'
            )
            return self.result(200, lineup, "audio/x-mpegurl")
        if path == "/qa/guide.xml":
            now = time.time()

            def stamp(offset):
                return time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(now + offset))

            title = (
                "Updated program"
                if state["channel_failure"] in ("hold", "outage")
                else "First program"
            )
            guide = f'<tv><programme channel="a" start="{stamp(-600)}" stop="{stamp(600)}"><title>{title}</title></programme></tv>'
            return self.result(200, guide.encode(), "application/xml")
        if path == "/LiveTv/Channels":
            index = int(q.get("startIndex", ["0"])[0])
            count = int(q.get("limit", ["256"])[0])
            with channel_changes:
                channel_changes.wait_for(
                    lambda: state["channel_failure"] != "hold", timeout=20
                )
                token = (
                    self.headers.get("Authorization", "")
                    .split('Token="')[-1]
                    .rstrip('"')
                )
                valid = token in state["tokens"]
                fault = state["channel_failure"]
                lineup = (
                    [dict(channels[0], Id="replacement"), *channels[1:]]
                    if state["replacement"]
                    else channels
                )
                if fault == "reordered":
                    lineup = [lineup[1], lineup[0], *lineup[2:]]
                rows = [
                    {**x, "UserData": {"IsFavorite": x["Id"] in state["favorites"]}}
                    for x in lineup[index : index + count]
                ]
            if not valid or fault == "expired":
                return self.result(401)
            if fault in ("outage", "hold"):
                return self.result(503, {})
            if index and fault == "shrunk":
                return self.result(200, {"Items": [], "TotalRecordCount": index})
            if index and fault == "empty":
                rows = []
            if index + count >= len(lineup) and fault == "repeat":
                rows = lineup[: len(rows)]
            return self.result(200, {"Items": rows, "TotalRecordCount": len(lineup)})
        if path == "/LiveTv/Programs":
            with lock:
                state["guide"] += 1
                failure = state["guide_failure"]
            if failure:
                return self.result(503, {})
            ids = q.get("channelIds", ["a,b"])[0].split(",")
            now = time.time()
            begin = (int(now) // 1800) * 1800
            first = timestamp(q, "minEndDate", float("-inf"))
            last = timestamp(q, "maxStartDate", float("inf"))
            rows = [
                {
                    "ChannelId": x,
                    "Name": ("Morning magazine" if x == "a" else "Live bulletin")
                    if n == 0
                    else f"Upcoming show {n}",
                    "StartDate": iso(begin + 1800 * n),
                    "EndDate": iso(begin + 1800 * (n + 1)),
                }
                for n in range(7)
                for x in ids
                if x in ("a", "b")
                and begin + 1800 * n < last
                and begin + 1800 * (n + 1) > first
            ]
            page = int(q.get("startIndex", ["0"])[0])
            size = int(q.get("limit", ["160"])[0])
            return self.result(200, {"Items": rows[page : page + size]})
        if path.endswith((".m3u8", ".ts")):
            if not self.headers.get("User-Agent", "").startswith("TippyTV/"):
                return self.result(403, {})
            with lock:
                interruption = state["interrupted"].get(q.get("lease", [""])[0])
                failure = (
                    interruption
                    if isinstance(interruption, int)
                    else state["media_failure"]
                )
                if failure:
                    state["media_failures"] += 1
            if failure:
                return self.result(failure, {})
        if path in ("/a.m3u8", "/b.m3u8"):
            with lock:
                if q.get("lease"):
                    state["encodings"].add(q["lease"][0])
                offset = state["sequence_offset"]
                playlist_eof = q.get("direct") == ["1"] and state["playlist_eof"]
                if playlist_eof:
                    state["playlist_eof"] = False
            n = max(4, int((time.time() - start) / 2)) + offset
            file = path[1]
            if file == "a":
                with lock:
                    file += state["rendition"]
            lines = [
                "#EXTM3U",
                "#EXT-X-VERSION:3",
                "#EXT-X-TARGETDURATION:2",
                f"#EXT-X-MEDIA-SEQUENCE:{n - 4}",
                f"#EXT-X-DISCONTINUITY-SEQUENCE:{(n + 7) // 12}",
            ]
            for i in range(n - 4, n + 1):
                if i % 12 == 0:
                    lines.append("#EXT-X-DISCONTINUITY")
                lines.extend(
                    [
                        "#EXTINF:2.0,",
                        f"{file}{i % 12}.ts?lease={q.get('lease', [''])[0]}",
                    ]
                )
            if interruption == "ended" or playlist_eof:
                lines.append("#EXT-X-ENDLIST")
            return self.result(
                200, ("\n".join(lines) + "\n").encode(), "application/vnd.apple.mpegurl"
            )
        name = path.lstrip("/")
        if "/" not in name and name.endswith(".ts") and (ROOT / name).is_file():
            if interruption == "empty":
                return self.result(
                    200,
                    (bytes([0x47, 0x1F, 0xFF, 0x10]) + bytes([0xFF]) * 184) * 100,
                    "video/mp2t",
                )
            data = (ROOT / name).read_bytes()
            lease = q.get("lease", [""])[0]
            with lock:
                slow = (
                    state["slow_start"] or state["silent_start"]
                ) and lease not in state["slow_leases"]
                delay = 110 if state["silent_start"] else 35
                if slow:
                    state["slow_leases"].add(lease)
            if slow:
                self.send_response(200)
                self.send_header("Content-Type", "video/mp2t")
                self.send_header("Content-Length", str(len(data) + 188 * delay))
                self.end_headers()
                try:
                    for _ in range(delay):
                        self.wfile.write(
                            bytes([0x47, 0x1F, 0xFF, 0x10]) + bytes([0xFF]) * 184
                        )
                        self.wfile.flush()
                        time.sleep(1)
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            return self.result(200, data, "video/mp2t")
        return self.result(404, {})

    def do_POST(self):
        u = urlsplit(self.path)
        q = parse_qs(u.query)
        path = u.path
        size = int(self.headers.get("Content-Length", "0"))
        payload = self.rfile.read(size) if size else b""
        if path == "/QuickConnect/Initiate":
            secret = secrets.token_hex(24)
            with lock:
                attempt = {
                    "Code": f"{secrets.randbelow(1000000):06d}",
                    "Secret": secret,
                    "Authenticated": False,
                }
                quick_connect[secret] = attempt
            return self.result(200, attempt)
        if path == "/qa/approve":
            code = q.get("code", [""])[0]
            with lock:
                match = next(
                    (a for a in quick_connect.values() if a["Code"] == code), None
                )
                if match:
                    match["Authenticated"] = True
            return self.result(204 if match else 404)
        if path in ("/Users/AuthenticateByName", "/Users/AuthenticateWithQuickConnect"):
            fields = json.loads(payload)
            if path == "/Users/AuthenticateByName" and fields.get("Pw") == "reject":
                return self.result(401)
            with lock:
                if path == "/Users/AuthenticateWithQuickConnect":
                    secret = fields.get("Secret")
                    if not quick_connect.get(secret, {}).get("Authenticated"):
                        return self.result(401)
                    del quick_connect[secret]
                state["logins"] += 1
                token = "fixture-token-" + str(state["logins"])
                state["tokens"].add(token)
            return self.result(
                200, {"AccessToken": token, "User": {"Id": "fixture-user"}}
            )
        if path == "/Sessions/Logout":
            token = (
                self.headers.get("Authorization", "").split('Token="')[-1].rstrip('"')
            )
            with lock:
                valid = token in state["tokens"]
                if valid:
                    state["tokens"].remove(token)
                    state["logouts"] += 1
                    state["open_at_logout"] = len(state["open"])
            return self.result(204 if valid else 401)
        if path == "/qa/advance_live_window":
            with lock:
                state["sequence_offset"] += 60
            return self.result(200, {})
        if path == "/qa/open_delay":
            delay = int(q.get("ms", ["0"])[0])
            if not 0 <= delay <= 10_000:
                return self.result(400, {})
            with lock:
                state["open_delay_ms"] = delay
            return self.result(204)
        if path == "/qa/open_failure":
            status = int(q.get("status", ["0"])[0])
            if status not in (0, 503):
                return self.result(400, {})
            with lock:
                state["open_failure"] = status
            return self.result(204)
        if path == "/qa/rendition":
            name = q.get("name", [""])[0]
            if name not in ("", "_hd", "_720", "_43"):
                return self.result(400, {})
            with lock:
                state["rendition"] = name
                state["sequence_offset"] += 60
            return self.result(204)
        if path == "/qa/silent_start":
            with lock:
                state["silent_start"] = q.get("enabled", ["1"])[0] == "1"
            return self.result(204)
        if path == "/qa/slow_start":
            with lock:
                state["slow_start"] = q.get("enabled", ["1"])[0] == "1"
            return self.result(204)
        if path == "/qa/interrupt":
            channel = q.get("channel", ["a"])[0]
            mode = q.get("mode", ["503"])[0]
            if mode not in ("403", "503", "ended", "empty"):
                return self.result(400, {})
            with lock:
                for lease, playing in state["open"].items():
                    if playing == channel:
                        state["interrupted"][lease] = (
                            mode if mode in ("ended", "empty") else int(mode)
                        )
            return self.result(204)
        if path == "/qa/media_failure":
            status = int(q.get("status", ["0"])[0])
            if status not in (0, 403, 503):
                return self.result(400, {})
            with lock:
                state["media_failure"] = status
            return self.result(204)
        if path == "/qa/channel_failure":
            mode = q.get("mode", ["outage"])[0] if q.get("enabled") != ["0"] else ""
            if mode not in (
                "",
                "outage",
                "empty",
                "repeat",
                "hold",
                "expired",
                "changed",
                "reordered",
                "shrunk",
            ):
                return self.result(400, {})
            with channel_changes:
                state["channel_failure"] = mode
                channel_changes.notify_all()
            return self.result(204)
        if path == "/qa/playlist_eof":
            with lock:
                state["playlist_eof"] = True
            return self.result(204)
        if path == "/qa/guide_failure":
            with lock:
                state["guide_failure"] = q.get("enabled", ["1"])[0] == "1"
            return self.result(200, {})
        if path == "/qa/replacement":
            with lock:
                state["replacement"] = q.get("enabled", ["1"])[0] == "1"
            return self.result(204)
        if path.startswith("/Items/") and path.endswith("/PlaybackInfo"):
            profile = json.loads(payload)
            copy = not profile.get("EnableTranscoding")
            if (
                profile.get("EnableDirectPlay") is not False
                or profile.get("AllowVideoStreamCopy") != copy
                or profile.get("AllowAudioStreamCopy") != copy
            ):
                return self.result(400, {})
            if not profile.get("DeviceProfile", {}).get("TranscodingProfiles"):
                return self.result(400, {})
            with lock:
                state["open_requests"] += 1
                if profile.get("EnableTranscoding"):
                    state["converted_requests"] += 1
                delay = state["open_delay_ms"]
                failure = state["open_failure"]
            time.sleep(delay / 1000)
            if failure:
                return self.result(failure, {})
            channel = path.split("/")[2]
            if channel not in ("a", "b"):
                return self.result(200, {"MediaSources": []})
            with lock:
                state["opened"] += 1
                key = str(state["opened"])
                state["open"][key] = channel
                state["peak_open"] = max(state["peak_open"], len(state["open"]))
            return self.result(
                200,
                {
                    "PlaySessionId": key,
                    "MediaSources": [
                        {
                            "Id": channel,
                            "IsRemote": True,
                            "Container": "hls",
                            "SupportsDirectPlay": False,
                            "RequiredHttpHeaders": {
                                "User-Agent": "TippyTV-Fixture-Provider"
                            },
                            "LiveStreamId": key,
                            "Path": "https://provider.invalid/do-not-bypass-jellyfin.m3u8",
                            "TranscodingUrl": f"/{channel}.m3u8?lease={key}",
                        }
                    ],
                },
            )
        if path == "/LiveStreams/Close":
            key = q.get("liveStreamId", [""])[0]
            with lock:
                if state["open"].pop(key, None) is not None:
                    state["closed"] += 1
            return self.result(204)
        if "/FavoriteItems/" in path:
            with lock:
                state["favorites"].add(path.split("/")[-1])
                state["writes"] += 1
            return self.result(200, {})
        return self.result(404, {})

    def do_DELETE(self):
        parsed = urlsplit(self.path)
        path = parsed.path
        if path == "/Videos/ActiveEncodings":
            with lock:
                state["encodings"].discard(
                    parse_qs(parsed.query).get("playSessionId", [""])[0]
                )
        if "/FavoriteItems/" in path:
            with lock:
                state["favorites"].discard(path.split("/")[-1])
                state["writes"] += 1
        return self.result(204)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
