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

final class Jellyfin implements TvSource {
    private static final MediaType JSON = MediaType.get("application/json");
    private static final RequestBody EMPTY = RequestBody.create(new byte[0], null);
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .dns(new LocalDns())
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(24, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();

    record Session(String server, String token, String user, String device, String name, String playlist, String guide) {
        Session(String server, String token, String user, String device) {
            this(server, token, user, device, user, "", "");
        }
        boolean direct() { return !playlist.isEmpty(); }
        String label() { return direct() ? name : name + " · " + server; }
    }
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
        return nativePlaybackUrl(endpoint);
    }

    static HttpUrl nativePlaybackUrl(HttpUrl endpoint) throws IOException {
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
        return new Session(base.toString(), token, userId, session.device(), result.getJSONObject("User").optString("Name", userId), "", "");
    }

    public Page channels(int start) throws IOException {
        int limit = 256;
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
        int next = start + items.length();
        int total = result.optInt("TotalRecordCount", -1);
        boolean more = items.length() > 0 && (total >= 0 ? next < total : items.length() >= limit);
        return new Page(channels, more ? next : -1);
    }

    public void favorite(Channels.Channel channel, boolean value) throws IOException {
        request(url("Users").addPathSegment(session.user).addPathSegment("FavoriteItems")
                .addPathSegment(channel.id()).build(), value ? "POST" : "DELETE", null);
    }

    public void logout() throws IOException {
        request(url("Sessions/Logout").build(), "POST", null);
    }

    private HttpUrl.Builder guideQuery(long time, int hours) {
        return url("LiveTv/Programs").addQueryParameter("userId", session.user)
                .addQueryParameter("minEndDate", Instant.ofEpochMilli(time).toString())
                .addQueryParameter("maxStartDate", Instant.ofEpochMilli(time + hours * 3_600_000L).toString())
                .addQueryParameter("enableImages", "false").addQueryParameter("enableUserData", "false")
                .addQueryParameter("enableTotalRecordCount", "false");
    }

    private JSONArray guideItems(HttpUrl.Builder query) throws IOException {
        JSONArray items = request(query.build(), "GET", null).optJSONArray("Items");
        if (items == null) throw new IOException("Jellyfin returned an invalid guide response.");
        return items;
    }

    public boolean hasGuide(long time, int hours) throws IOException {
        JSONArray items = guideItems(guideQuery(time, hours).addQueryParameter("limit", "1"));
        return items.length() != 0;
    }

    public Map<String, List<Channels.Program>> programs(List<String> ids, long time, int hours) throws IOException {
        Map<String, List<Channels.Program>> programs = new HashMap<>();
        for (String id : ids) programs.put(id, new ArrayList<>());
        if (ids.isEmpty()) return programs;
        int pageSize = 160;
        for (int start = 0; start < 4 * pageSize; start += pageSize) {
            JSONArray items = guideItems(guideQuery(time, hours)
                    .addQueryParameter("channelIds", String.join(",", ids))
                    .addQueryParameter("sortBy", "StartDate").addQueryParameter("sortOrder", "Ascending")
                    .addQueryParameter("startIndex", Integer.toString(start)).addQueryParameter("limit", Integer.toString(pageSize)));
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

    public Stream open(Channels.Channel channel, boolean fallback, boolean inset, boolean recovering) throws IOException, JSONException {
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
        JSONObject result = request(url("Items").addPathSegment(channel.id()).addPathSegment("PlaybackInfo").build(), "POST", body);
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

    public void close(Stream stream) {
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
