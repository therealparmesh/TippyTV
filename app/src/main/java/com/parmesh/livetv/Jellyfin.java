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
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(24, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();

    record Session(String server, String token, String user, String device) {}
    record Page(List<Channels.Channel> channels, int consumed, int total) {}
    record Stream(HttpUrl url, boolean hls, String liveId, String playId, boolean converted) {}

    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("Jellyfin returned HTTP " + status); this.status = status; }
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
        return "MediaBrowser Client=\"Jellyfin Live TV\", Device=\"Android TV\", DeviceId=\""
                + session.device + "\", Version=\"0.1.0\""
                + (session.token.isEmpty() ? "" : ", Token=\"" + session.token + "\"");
    }

    private static boolean sameOrigin(HttpUrl a, HttpUrl b) {
        return a.scheme().equals(b.scheme()) && a.host().equals(b.host()) && a.port() == b.port();
    }

    OkHttpClient streamClient() {
        return HTTP.newBuilder().callTimeout(0, TimeUnit.SECONDS)
                .followRedirects(true).followSslRedirects(true)
                .addNetworkInterceptor(chain -> {
                    Request request = chain.request();
                    Request.Builder clean = request.newBuilder().removeHeader("Authorization").removeHeader("X-Emby-Token");
                    if (sameOrigin(base, request.url())) clean.header("Authorization", authorization());
                    return chain.proceed(clean.build());
                }).build();
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
        Jellyfin unauthenticated = new Jellyfin(new Session(serverUrl(server).toString(), "", "", device));
        JSONObject result = unauthenticated.request(unauthenticated.url("Users/AuthenticateByName").build(), "POST",
                new JSONObject().put("Username", user).put("Pw", password));
        String token = result.getString("AccessToken");
        String userId = result.getJSONObject("User").getString("Id");
        if (token.isBlank() || userId.isBlank()) throw new IOException("Jellyfin returned an incomplete sign-in.");
        return new Session(unauthenticated.base.toString(), token, userId, device);
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
        JSONObject body = new JSONObject().put("UserId", session.user).put("AutoOpenLiveStream", true)
                .put("EnableDirectPlay", !fallback).put("EnableDirectStream", true).put("EnableTranscoding", fallback);
        if (fallback) {
            int bitrate = inset ? 2_000_000 : 6_000_000;
            JSONObject hls = new JSONObject().put("Type", "Video").put("Container", "ts")
                    .put("Protocol", "hls").put("Context", "Streaming").put("VideoCodec", "h264")
                    .put("AudioCodec", "aac").put("MaxAudioChannels", "2").put("MinSegments", 2);
            body.put("MaxStreamingBitrate", bitrate).put("MaxAudioChannels", 2)
                    .put("DeviceProfile", new JSONObject().put("Name", "Jellyfin Live TV")
                            .put("MaxStreamingBitrate", bitrate)
                            .put("TranscodingProfiles", new JSONArray().put(hls)));
        }
        JSONObject result = request(url("Items").addPathSegment(channel).addPathSegment("PlaybackInfo").build(), "POST", body);
        JSONArray sources = result.optJSONArray("MediaSources");
        JSONObject source = sources == null ? null : sources.optJSONObject(0);
        if (source == null) throw new IOException("Jellyfin could not open this channel.");
        String liveId = str(source, "LiveStreamId");
        String playId = str(result, "PlaySessionId");
        String container = str(source, "Container").split(",")[0];
        if (!container.matches("[a-zA-Z0-9]{1,12}")) container = "ts";
        String transcode = str(source, "TranscodingUrl");
        try {
            if (fallback && !transcode.isBlank()) {
                HttpUrl endpoint = base.resolve(transcode);
                if (endpoint == null || !sameOrigin(base, endpoint)) throw new IOException("Invalid fallback stream address.");
                return new Stream(endpoint, endpoint.encodedPath().endsWith(".m3u8"), liveId, playId, true);
            }
            if (fallback || !source.optBoolean("SupportsDirectPlay")) throw new IOException("No compatible stream for this channel.");
            HttpUrl remote = source.optBoolean("IsRemote") ? HttpUrl.parse(str(source, "Path")) : null;
            if (remote != null && !remote.host().equals("localhost") && !remote.host().equals("127.0.0.1")
                    && !remote.host().equals("::1")) {
                return new Stream(remote, isHls(container, remote), liveId, playId, false);
            }
            HttpUrl endpoint = url("Videos").addPathSegment(channel).addPathSegment("stream." + container)
                    .addQueryParameter("static", "true").addQueryParameter("mediaSourceId", str(source, "Id"))
                    .addQueryParameter("liveStreamId", liveId).addQueryParameter("playSessionId", playId).build();
            return new Stream(endpoint, isHls(container, endpoint), liveId, playId, false);
        } catch (IOException invalid) {
            close(new Stream(base, false, liveId, playId, fallback));
            throw invalid;
        }
    }

    private static boolean isHls(String container, HttpUrl url) {
        return container.equalsIgnoreCase("hls") || url.encodedPath().toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8");
    }

    void close(Stream stream) {
        if (stream.converted && !stream.playId.isBlank()) {
            try {
                request(url("Videos/ActiveEncodings").addQueryParameter("deviceId", session.device)
                        .addQueryParameter("playSessionId", stream.playId).build(), "DELETE", null);
            } catch (IOException ignored) { /* Encoder may already have stopped. */ }
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
