package com.parmesh.livetv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class Jellyfin {
    private static final MediaType JSON = MediaType.get("application/json");
    private static final RequestBody EMPTY = RequestBody.create(new byte[0], null);
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .dns(new LocalDns())
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(24, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();

    record Session(String server, String token, String user, String device) {}
    record Page(List<Channels.Channel> channels, int consumed, int total) {}
    record Stream(HttpUrl url, String liveId, String playId, boolean compatible) {}
    record QuickConnect(String code, String secret) {}

    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("Jellyfin returned HTTP " + status); this.status = status; }
    }

    static final class NoCompatibleStream extends IOException {
        NoCompatibleStream() { super("No compatible stream for this channel."); }
    }

    final Session session;
    private final HttpUrl base;

    Jellyfin(Session session) {
        this.session = session;
        this.base = serverUrl(session.server);
    }

    static HttpUrl serverUrl(String value) {
        String address = value.trim();
        if (!address.contains("://")) address = "http://" + address;
        HttpUrl url = HttpUrl.parse(address);
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("Enter the Jellyfin server address, including its port if needed.");
        }
        return url;
    }

    private HttpUrl.Builder url(String path) {
        HttpUrl.Builder b = base.newBuilder();
        if (base.encodedPath().endsWith("/")) b.removePathSegment(base.pathSize() - 1);
        for (String part : path.split("/")) if (!part.isEmpty()) b.addPathSegment(part);
        return b;
    }

    private String authorization() {
        return "MediaBrowser Client=\"TippyTV\", Device=\"Android TV\", DeviceId=\""
                + session.device + "\", Version=\"" + BuildConfig.VERSION_NAME + "\""
                + (session.token.isEmpty() ? "" : ", Token=\"" + session.token + "\"");
    }

    private static boolean sameOrigin(HttpUrl a, HttpUrl b) {
        return a.scheme().equals(b.scheme()) && a.host().equals(b.host()) && a.port() == b.port();
    }

    HttpUrl playbackUrl(HttpUrl endpoint) throws IOException {
        if (sameOrigin(base, endpoint)) {
            endpoint = endpoint.newBuilder().setQueryParameter("api_key", session.token).build();
        }
        // Native playback uses platform DNS. Resolve HTTP .local hosts through our mDNS
        // resolver; keep HTTPS hostnames intact for certificate verification.
        if (endpoint.scheme().equals("http") && endpoint.host().endsWith(".local")) {
            endpoint = endpoint.newBuilder().host(new LocalDns().lookup(endpoint.host()).get(0).getHostAddress()).build();
        }
        return endpoint;
    }

    private JSONObject request(HttpUrl endpoint, String method, JSONObject body) throws IOException {
        RequestBody data = body != null ? RequestBody.create(body.toString(), JSON)
                : method.equals("POST") ? EMPTY : null;
        Request req = new Request.Builder().url(endpoint).header("Authorization", authorization())
                .method(method, data).build();
        try (Response response = HTTP.newCall(req).execute()) {
            if (!response.isSuccessful()) throw new HttpFailure(response.code());
            String raw = response.body() == null ? "" : response.body().string();
            return raw.isBlank() ? new JSONObject() : new JSONObject(raw);
        } catch (JSONException malformed) {
            throw new IOException("Jellyfin returned an invalid response.");
        }
    }

    static Session login(String server, String user, String password, String device) throws IOException, JSONException {
        Jellyfin client = signInClient(server, device);
        return client.authenticate("Users/AuthenticateByName", new JSONObject().put("Username", user).put("Pw", password));
    }

    static Jellyfin signInClient(String server, String device) {
        return new Jellyfin(new Session(server, "", "", device));
    }

    QuickConnect startQuickConnect() throws IOException, JSONException {
        JSONObject result = request(url("QuickConnect/Initiate").build(), "POST", null);
        String code = result.getString("Code"), secret = result.getString("Secret");
        if (code.isBlank() || secret.isBlank()) throw new IOException("Jellyfin returned an incomplete Quick Connect request.");
        return new QuickConnect(code, secret);
    }

    boolean quickConnectAuthorized(QuickConnect attempt) throws IOException {
        return request(url("QuickConnect/Connect").addQueryParameter("secret", attempt.secret()).build(), "GET", null)
                .optBoolean("Authenticated");
    }

    Session finishQuickConnect(QuickConnect attempt) throws IOException, JSONException {
        return authenticate("Users/AuthenticateWithQuickConnect", new JSONObject().put("Secret", attempt.secret()));
    }

    private Session authenticate(String endpoint, JSONObject body) throws IOException, JSONException {
        JSONObject result = request(url(endpoint).build(), "POST", body);
        String token = result.getString("AccessToken");
        String userId = result.getJSONObject("User").getString("Id");
        if (token.isBlank() || userId.isBlank()) throw new IOException("Jellyfin returned an incomplete sign-in.");
        return new Session(base.toString(), token, userId, session.device());
    }

    Page channels(int start, int limit) throws IOException {
        JSONObject result = request(url("LiveTv/Channels").addQueryParameter("userId", session.user)
                .addQueryParameter("startIndex", "" + start).addQueryParameter("limit", "" + limit)
                .addQueryParameter("enableImages", "false").addQueryParameter("addCurrentProgram", "false")
                .addQueryParameter("enableUserData", "true").addQueryParameter("sortBy", "SortName")
                .build(), "GET", null);
        JSONArray items = result.optJSONArray("Items");
        if (items == null) throw new IOException("Jellyfin returned an invalid channel page.");
        List<Channels.Channel> channels = new ArrayList<>(items.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || str(item, "Id").isBlank()) continue;
            JSONObject userData = item.optJSONObject("UserData");
            String name = str(item, "Name");
            channels.add(new Channels.Channel(str(item, "Id"), name.isBlank() ? "Untitled channel" : name,
                    str(item, "ChannelNumber").isBlank() ? str(item, "Number") : str(item, "ChannelNumber"),
                    userData != null && userData.optBoolean("IsFavorite")));
        }
        return new Page(channels, items.length(), result.optInt("TotalRecordCount", -1));
    }

    void favorite(String channel, boolean value) throws IOException {
        request(url("Users").addPathSegment(session.user).addPathSegment("FavoriteItems")
                .addPathSegment(channel).build(), value ? "POST" : "DELETE", null);
    }

    void logout() throws IOException {
        request(url("Sessions/Logout").build(), "POST", null);
    }

    boolean hasGuide(long time, int hours) throws IOException {
        JSONObject result = request(url("LiveTv/Programs").addQueryParameter("userId", session.user)
                .addQueryParameter("minEndDate", Instant.ofEpochMilli(time).toString())
                .addQueryParameter("maxStartDate", Instant.ofEpochMilli(time + hours * 3_600_000L).toString())
                .addQueryParameter("limit", "1").addQueryParameter("enableImages", "false")
                .addQueryParameter("enableUserData", "false").addQueryParameter("enableTotalRecordCount", "false")
                .build(), "GET", null);
        JSONArray items = result.optJSONArray("Items");
        if (items == null) throw new IOException("Jellyfin returned an invalid guide response.");
        return items.length() != 0;
    }

    Map<String, List<Channels.Program>> programs(List<String> ids, long time, int hours) throws IOException {
        Map<String, List<Channels.Program>> programs = new HashMap<>();
        for (String id : ids) programs.put(id, new ArrayList<>());
        if (ids.isEmpty()) return programs;
        int pageSize = 160;
        for (int start = 0; start < 4 * pageSize; start += pageSize) {
            JSONObject result = request(url("LiveTv/Programs").addQueryParameter("userId", session.user)
                    .addQueryParameter("channelIds", String.join(",", ids))
                    .addQueryParameter("minEndDate", Instant.ofEpochMilli(time).toString())
                    .addQueryParameter("maxStartDate", Instant.ofEpochMilli(time + hours * 3_600_000L).toString())
                    .addQueryParameter("sortBy", "StartDate").addQueryParameter("sortOrder", "Ascending")
                    .addQueryParameter("startIndex", "" + start).addQueryParameter("limit", "" + pageSize)
                    .addQueryParameter("enableImages", "false").addQueryParameter("enableUserData", "false")
                    .addQueryParameter("enableTotalRecordCount", "false").build(), "GET", null);
            JSONArray items = result.optJSONArray("Items");
            if (items == null) throw new IOException("Jellyfin returned an invalid guide page.");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                List<Channels.Program> row = programs.get(str(item, "ChannelId"));
                long begin = Channels.timestamp(str(item, "StartDate")), end = Channels.timestamp(str(item, "EndDate"));
                if (row != null && begin >= 0 && end > begin) {
                    row.add(new Channels.Program(str(item, "Name"), begin, end));
                }
            }
            if (items.length() < pageSize) break;
        }
        programs.replaceAll((id, row) -> Channels.sortedPrograms(row));
        return programs;
    }

    Stream open(String channel, boolean fallback, boolean inset) throws IOException, JSONException {
        int bitrate = fallback ? (inset ? 2_000_000 : 6_000_000) : 200_000_000;
        int audioChannels = fallback ? 2 : 8;
        JSONObject hls = new JSONObject().put("Type", "Video").put("Container", "ts")
                .put("Protocol", "hls").put("Context", "Streaming")
                .put("VideoCodec", fallback ? "h264" : "h264,hevc,mpeg2video")
                .put("AudioCodec", fallback ? "aac" : "aac,ac3,eac3,mp3,mp2")
                .put("MaxAudioChannels", Integer.toString(audioChannels)).put("MinSegments", 2);
        // Let Jellyfin own the provider connection and negotiate HLS, including stream copy.
        JSONObject body = new JSONObject().put("UserId", session.user).put("AutoOpenLiveStream", true)
                .put("EnableDirectPlay", false).put("EnableDirectStream", true).put("EnableTranscoding", fallback)
                .put("AllowVideoStreamCopy", !fallback).put("AllowAudioStreamCopy", !fallback)
                .put("MaxStreamingBitrate", bitrate).put("MaxAudioChannels", audioChannels)
                .put("DeviceProfile", new JSONObject().put("Name", "TippyTV")
                        .put("MaxStreamingBitrate", bitrate)
                        .put("TranscodingProfiles", new JSONArray().put(hls)));
        JSONObject result = request(url("Items").addPathSegment(channel).addPathSegment("PlaybackInfo").build(), "POST", body);
        JSONArray sources = result.optJSONArray("MediaSources");
        JSONObject source = sources == null ? null : sources.optJSONObject(0);
        if (source == null) throw new NoCompatibleStream();
        String liveId = str(source, "LiveStreamId");
        String playId = str(result, "PlaySessionId");
        try {
            String address = str(source, "TranscodingUrl");
            if (address.isBlank()) throw new NoCompatibleStream();
            HttpUrl endpoint = base.resolve(address);
            if (endpoint == null || !sameOrigin(base, endpoint)) throw new IOException("Invalid stream address.");
            return new Stream(playbackUrl(endpoint), liveId, playId, fallback);
        } catch (IOException invalid) {
            close(new Stream(base, liveId, playId, fallback));
            throw invalid;
        }
    }

    void close(Stream stream) {
        if (!stream.playId.isBlank()) {
            try {
                request(url("Videos/ActiveEncodings").addQueryParameter("deviceId", session.device)
                        .addQueryParameter("playSessionId", stream.playId).build(), "DELETE", null);
            } catch (IOException ignored) { /* Remuxer or encoder may already have stopped. */ }
        }
        if (!stream.liveId.isBlank()) {
            try {
                request(url("LiveStreams/Close").addQueryParameter("liveStreamId", stream.liveId).build(), "POST", null);
            } catch (IOException ignored) { /* Server can expire a disconnected tuner. */ }
        }
    }

    private static String str(JSONObject item, String key) {
        return item.isNull(key) ? "" : item.optString(key, "");
    }
}
