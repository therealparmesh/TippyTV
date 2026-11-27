package com.parmesh.livetv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.Xml;

import okhttp3.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

final class PlaylistSource implements TvSource {
    private static final long RECOVERY_REFRESH_MS = 30_000;
    private static final OkHttpClient HTTP =
            new OkHttpClient.Builder()
                    .dns(new LocalDns())
                    .callTimeout(45, TimeUnit.SECONDS)
                    .build();
    private static final Pattern RELATIVE_MEDIA =
            Pattern.compile("(?i)[^<>]+\\.(?:m3u8|ts|mp4|aac|mp3)(?:[?#].*)?");
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern HLS = Pattern.compile("(?m)^#EXT-X-(STREAM-INF|TARGETDURATION):");
    private static final Pattern MARKUP = Pattern.compile("(?m)^[\\t ]*<");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
    private final Jellyfin.Session profile;
    private final SharedPreferences stars;

    record Entry(
            String name,
            String number,
            String guide,
            String url,
            boolean adult,
            List<String> categories) {}

    private volatile Map<String, Entry> entries = Map.of();
    private final Object lineupLock = new Object();
    private volatile Map<String, Entry> lastDownload;
    private long nextRecoveryRefresh;
    private Map<String, List<Channels.Program>> listings = Map.of();
    private volatile Set<String> guideIds = Set.of();
    private Set<String> loadedGuideIds;
    private long guideExpires;
    private final Object guideLock = new Object();

    // Setup can recreate the activity when switching profiles. Keep the validated lineup
    // only until the new profile consumes it, without putting it into saved instance state.
    public static final class CheckedPlaylist extends androidx.lifecycle.ViewModel {
        private Jellyfin.Session profile;
        private Map<String, Entry> entries;

        void set(Jellyfin.Session session, Map<String, Entry> channels) {
            profile = session;
            entries = channels;
        }

        Map<String, Entry> take(Jellyfin.Session session) {
            Map<String, Entry> channels = session.equals(profile) ? entries : null;
            profile = null;
            entries = null;
            return channels;
        }
    }

    private Map<String, Entry> initial;

    PlaylistSource(Context context, Jellyfin.Session profile, Map<String, Entry> initial) {
        this.initial = initial;
        this.profile = profile;
        stars = context.getSharedPreferences("stars-" + profile.user(), Context.MODE_PRIVATE);
    }

    private static Response fetch(HttpUrl url) throws IOException {
        Response r = HTTP.newCall(new Request.Builder().url(url).build()).execute();
        if (r.isSuccessful() && r.body() != null) return r;
        r.close();
        throw new IOException("Could not download source (HTTP " + r.code() + ").");
    }

    static String text(HttpUrl url, int limit) throws IOException {
        try (Response r = fetch(url)) {
            return body(r, limit);
        }
    }

    private static String body(Response r, int limit) throws IOException {
        if (r.body().source().request(limit + 1L)) throw new IOException("File is too large.");
        return r.body().string();
    }

    static Map<String, Entry> download(Jellyfin.Session profile) throws IOException {
        return download(profile, false);
    }

    private static Map<String, Entry> download(Jellyfin.Session profile, boolean hadCategories)
            throws IOException {
        HttpUrl playlist = HttpUrl.get(profile.playlist());
        if (profile.xtream()) {
            try {
                return xtreamChannels(
                        playlist,
                        playlist.queryParameter("username"),
                        playlist.queryParameter("password"),
                        hadCategories);
            } catch (CategoryFailure incomplete) {
                throw incomplete;
            } catch (IOException unavailable) {
                // Some providers expose only the M3U export.
            }
        }
        try (Response r = fetch(playlist)) {
            return parse(body(r, 16_000_000), r.request().url(), profile.name());
        }
    }

    private static Map<String, Entry> xtreamChannels(
            HttpUrl playlist, String user, String password, boolean hadCategories)
            throws IOException {
        HttpUrl endpoint =
                playlist.resolve("player_api.php")
                        .newBuilder()
                        .addQueryParameter("username", user)
                        .addQueryParameter("password", password)
                        .addQueryParameter("action", "get_live_streams")
                        .build();
        HttpUrl streamBase =
                playlist.resolve("live/")
                        .newBuilder()
                        .addPathSegment(user)
                        .addPathSegment(password)
                        .build();
        List<Entry> channels = new ArrayList<>();
        try (Response response = fetch(endpoint);
                JsonReader json = new JsonReader(response.body().charStream())) {
            json.beginArray();
            Map<String, String> categories = liveCategories(endpoint, hadCategories);
            while (json.hasNext()) {
                String id = "", name = "", number = "", epg = "", category = "";
                boolean adult = false;
                json.beginObject();
                while (json.hasNext()) {
                    String field = json.nextName();
                    switch (json.peek()) {
                        case STRING, NUMBER -> {
                            switch (field) {
                                case "category_id" -> category = json.nextString();
                                case "stream_id" -> id = json.nextString();
                                case "name" -> name = json.nextString();
                                case "num" -> number = json.nextString();
                                case "epg_channel_id" -> epg = json.nextString();
                                case "is_adult" -> {
                                    String flag = json.nextString();
                                    adult = "1".equals(flag) || "true".equalsIgnoreCase(flag);
                                }
                                default -> json.skipValue();
                            }
                        }
                        case BOOLEAN -> {
                            if ("is_adult".equals(field)) adult = json.nextBoolean();
                            else json.skipValue();
                        }
                        default -> json.skipValue();
                    }
                }
                json.endObject();
                String group = categories.getOrDefault(category, "");
                if (!id.isBlank() && !name.isBlank())
                    channels.add(
                            new Entry(
                                    name,
                                    number,
                                    epg,
                                    streamBase
                                            .newBuilder()
                                            .addPathSegment(id + ".ts")
                                            .build()
                                            .toString(),
                                    adult || Channels.adultGroup(group),
                                    group.isEmpty() ? List.of() : List.of(group)));
            }
            json.endArray();
        } catch (IllegalStateException malformed) {
            throw new IOException("Invalid Xtream channel list.", malformed);
        }
        if (channels.isEmpty()) throw new IOException("Xtream returned no live channels.");
        return indexChannels(channels);
    }

    private static final class CategoryFailure extends IOException {
        CategoryFailure(Exception cause) {
            super("Could not refresh Xtream categories.", cause);
        }
    }

    private static Map<String, String> liveCategories(HttpUrl endpoint, boolean hadCategories)
            throws CategoryFailure {
        HttpUrl url =
                endpoint.newBuilder().setQueryParameter("action", "get_live_categories").build();
        try {
            JSONArray categories = new JSONArray(text(url, 2_000_000));
            Map<String, String> ids = new HashMap<>();
            for (int i = 0; i < categories.length(); i++) {
                JSONObject category = categories.optJSONObject(i);
                if (category == null
                        || category.isNull("category_name")
                        || category.isNull("category_id")) continue;
                String id = category.optString("category_id");
                String name = category.optString("category_name").trim();
                if (!id.isBlank() && !name.isEmpty()) ids.put(id, name);
            }
            if (ids.isEmpty() && categories.length() != 0)
                throw new JSONException("Invalid Xtream categories");
            return ids;
        } catch (IOException | JSONException unavailable) {
            // Some providers never supply categories. Preserve a previously categorized lineup on
            // failure.
            if (hadCategories) throw new CategoryFailure(unavailable);
            return Map.of();
        }
    }

    static Map<String, Entry> parse(String data, HttpUrl base, String profileName)
            throws IOException {
        data = (data.startsWith("\uFEFF") ? data.substring(1) : data).stripLeading();
        if (MARKUP.matcher(data).find())
            throw new IOException("Playlist contains markup instead of a stream URL.");
        boolean extended = data.startsWith("#EXTM3U");
        if (extended && HLS.matcher(data).find())
            return Map.of(
                    "stream", new Entry(profileName, "", "", base.toString(), false, List.of()));
        String name = "", number = "", guide = "";
        List<Entry> channels = new ArrayList<>();
        String group = "", entryGroup = "";
        BufferedReader reader = new BufferedReader(new StringReader(data));
        for (String line; (line = reader.readLine()) != null; ) {
            line = line.trim();
            if (line.startsWith("#EXTINF:")) {
                boolean quoted = false;
                int comma = 0;
                for (; comma < line.length(); comma++) {
                    char c = line.charAt(comma);
                    if (c == '"') quoted = !quoted;
                    if (!quoted && c == ',') break;
                }
                Matcher attrs = ATTR.matcher(line).region(0, comma);
                guide = "";
                number = "";
                entryGroup = "";
                while (attrs.find()) {
                    if (attrs.group(1).equalsIgnoreCase("group-title")) {
                        group = "";
                        entryGroup = attrs.group(2).trim();
                    }
                    if (attrs.group(1).equals("tvg-id")) guide = attrs.group(2);
                    if (attrs.group(1).equals("tvg-chno")) number = attrs.group(2);
                }
                name = comma == line.length() ? "" : line.substring(comma + 1).trim();
            } else if (line.regionMatches(true, 0, "#EXTGRP:", 0, 8)) {
                group = line.substring(8).trim();
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                HttpUrl url = HttpUrl.parse(line);
                if (url == null
                        && (extended
                                || line.startsWith("/")
                                || line.startsWith("./")
                                || RELATIVE_MEDIA.matcher(line).matches()))
                    url = base.resolve(line);
                if (url != null) {
                    String label = name.isEmpty() ? url.host() : name;
                    String labelGroup = entryGroup.isEmpty() ? group : entryGroup;
                    List<String> groups =
                            labelGroup.isEmpty()
                                    ? List.of()
                                    : labelGroup.indexOf(';') < 0
                                            ? List.of(labelGroup)
                                            : Arrays.stream(labelGroup.split(";"))
                                                    .map(String::trim)
                                                    .filter(g -> !g.isEmpty())
                                                    .distinct()
                                                    .collect(java.util.stream.Collectors.toList());
                    Entry entry =
                            new Entry(
                                    label,
                                    number,
                                    guide,
                                    url.toString(),
                                    Channels.adultGroup(entryGroup) || Channels.adultGroup(group),
                                    groups);
                    channels.add(entry);
                }
                entryGroup = "";
                name = "";
                number = "";
                guide = "";
            }
        }
        if (channels.isEmpty()) throw new IOException("Playlist contains no playable channels.");
        return indexChannels(channels);
    }

    private static Map<String, Entry> indexChannels(List<Entry> channels) {
        Set<String> seen = new HashSet<>(), ambiguous = new HashSet<>();
        for (Entry entry : channels) {
            String guide = entry.guide();
            if (!guide.isEmpty() && !seen.add(guide)) ambiguous.add(guide);
        }
        Map<String, Entry> indexed = new LinkedHashMap<>();
        for (Entry entry : channels) {
            String guide = entry.guide();
            indexed.put(
                    guide.isEmpty() || ambiguous.contains(guide)
                            ? fingerprint(entry)
                            : "id:" + guide,
                    entry);
        }
        return indexed;
    }

    private static String fingerprint(Entry e) {
        return "url:"
                + UUID.nameUUIDFromBytes(
                        (e.name() + "\n" + e.url()).getBytes(StandardCharsets.UTF_8));
    }

    public Page channels(int start) throws IOException {
        Map<String, Entry> loaded = initial != null ? initial : refresh(false);
        initial = null;
        return new Page(channelList(loaded), -1, loaded.size());
    }

    private Map<String, Entry> refresh(boolean recovering) throws IOException {
        synchronized (lineupLock) {
            if (recovering && SystemClock.elapsedRealtime() < nextRecoveryRefresh)
                return lastDownload;
            try {
                lastDownload =
                        download(
                                profile,
                                profile.xtream()
                                        && entries.values().stream()
                                                .anyMatch(e -> !e.categories().isEmpty()));
                return lastDownload;
            } finally {
                nextRecoveryRefresh = SystemClock.elapsedRealtime() + RECOVERY_REFRESH_MS;
            }
        }
    }

    Entry entry(String id) {
        return entries.get(id);
    }

    List<Channels.Channel> channelList(Map<String, Entry> loaded) {
        Map<String, ?> favorites = stars.getAll();
        Set<String> ids = new HashSet<>();
        List<Channels.Channel> channels = new ArrayList<>(loaded.size());
        loaded.forEach((id, entry) -> {
            if (!profile.guide().isEmpty() && !entry.guide().isEmpty()) ids.add(entry.guide());
            channels.add(
                    new Channels.Channel(
                            id,
                            entry.name(),
                            entry.number(),
                            entry.name().equals(favorites.get(id)),
                            entry.adult(),
                            entry.categories()));
        });
        entries = loaded;
        if (!ids.equals(guideIds)) guideIds = ids;
        return channels;
    }

    public Jellyfin.Stream open(
            Channels.Channel channel, boolean fallback, boolean inset, boolean recovering)
            throws IOException {
        if (recovering) {
            try {
                refresh(true);
            } catch (IOException unavailable) {
                // A playlist outage can leave its stream URLs working.
            }
        }
        Map<String, Entry> lineup = lastDownload;
        if (lineup == null) lineup = entries;
        Entry e = lineup.get(channel.id());
        if (e == null || !e.name().equals(channel.name()) || e.adult() != channel.adult())
            throw new IllegalStateException("Channel changed. Refresh the channel list.");
        return new Jellyfin.Stream(Jellyfin.nativePlaybackUrl(HttpUrl.get(e.url())), "", "", false);
    }

    public void close(Jellyfin.Stream stream) {}

    public void logout() {
        stars.edit().clear().apply();
    }

    public void favorite(Channels.Channel channel, boolean on) throws IOException {
        String id = channel.id();
        Entry entry = entries.get(id);
        if (entry == null || !entry.name().equals(channel.name()))
            throw new IOException("Channel is unavailable.");
        SharedPreferences.Editor edit = stars.edit();
        if (on) edit.putString(id, entry.name());
        else edit.remove(id);
        edit.apply();
    }

    private static long timestamp(String value) {
        if (value == null) return -1;
        try {
            String[] p = value.trim().split("\\s+");
            String digits = p[0];
            if (digits.length() < 8 || digits.length() > 14 || digits.length() % 2 != 0) return -1;
            return LocalDateTime.parse(digits + "000000".substring(digits.length() - 8), STAMP)
                    .toInstant(p.length == 1 ? ZoneOffset.UTC : ZoneOffset.of(p[1]))
                    .toEpochMilli();
        } catch (RuntimeException invalid) {
            return -1;
        }
    }

    private void guide() throws IOException {
        if (profile.guide().isEmpty()) return;
        long now = System.currentTimeMillis();
        Set<String> wanted = guideIds;
        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (wanted == loadedGuideIds && elapsed < guideExpires) return;
        Map<String, List<Channels.Program>> result = new HashMap<>();
        if (!wanted.isEmpty())
            try (Response r = fetch(HttpUrl.get(profile.guide()));
                    InputStream input = guideInput(r.body())) {
                XmlPullParser xml = Xml.newPullParser();
                xml.setInput(input, null);
                xml.nextTag();
                xml.require(XmlPullParser.START_TAG, null, "tv");
                String channel = null, title = "";
                long start = 0, stop = 0;
                for (int event; (event = xml.next()) != XmlPullParser.END_DOCUMENT; ) {
                    if (event == XmlPullParser.START_TAG && xml.getName().equals("programme")) {
                        channel = xml.getAttributeValue(null, "channel");
                        if (!wanted.contains(channel)) {
                            channel = null;
                            continue;
                        }
                        start = timestamp(xml.getAttributeValue(null, "start"));
                        stop = timestamp(xml.getAttributeValue(null, "stop"));
                        title = "";
                    } else if (event == XmlPullParser.START_TAG
                            && channel != null
                            && title.isEmpty()
                            && xml.getName().equals("title")) {
                        title = xml.nextText();
                    } else if (event == XmlPullParser.END_TAG
                            && xml.getName().equals("programme")) {
                        if (channel != null
                                && !title.isEmpty()
                                && start >= 0
                                && (stop == -1
                                        ? start >= now - 86_400_000L
                                        : stop > now - 86_400_000L && stop > start)
                                && start < now + 8 * 86_400_000L)
                            result.computeIfAbsent(channel, k -> new ArrayList<>())
                                    .add(new Channels.Program(title, start, stop));
                        channel = null;
                    }
                }
            } catch (org.xmlpull.v1.XmlPullParserException e) {
                throw new IOException("Could not read program guide.", e);
            }
        finishGuide(result);
        listings = result;
        loadedGuideIds = wanted;
        guideExpires = android.os.SystemClock.elapsedRealtime() + 15 * 60_000L;
    }

    private static InputStream guideInput(ResponseBody body) throws IOException {
        BufferedInputStream raw = new BufferedInputStream(body.byteStream());
        raw.mark(2);
        boolean gzip = raw.read() == 31 && raw.read() == 139;
        raw.reset();
        return gzip ? new GZIPInputStream(raw) : raw;
    }

    static void finishGuide(Map<String, List<Channels.Program>> rows) {
        for (List<Channels.Program> row : rows.values()) {
            row.sort(Comparator.comparingLong(Channels.Program::start));
            // XMLTV permits an omitted stop. The next program supplies its boundary.
            for (int i = 0; i + 1 < row.size(); i++) {
                Channels.Program p = row.get(i);
                if (p.end() < 0)
                    row.set(i, new Channels.Program(p.title(), p.start(), row.get(i + 1).start()));
            }
            row.removeIf(p -> p.end() <= p.start());
        }
        rows.values().removeIf(List::isEmpty);
    }

    public boolean hasGuide(long time, int hours) throws IOException {
        synchronized (guideLock) {
            guide();
            return !listings.isEmpty();
        }
    }

    public Map<String, List<Channels.Program>> programs(List<String> ids, long time, int hours)
            throws IOException {
        Map<String, List<Channels.Program>> snapshot;
        synchronized (guideLock) {
            guide();
            snapshot = listings;
        }
        Map<String, List<Channels.Program>> rows = new HashMap<>();
        for (String id : ids) {
            Entry e = entries.get(id);
            List<Channels.Program> row = new ArrayList<>();
            if (e != null)
                for (Channels.Program item : snapshot.getOrDefault(e.guide(), List.of())) {
                    if (item.start() >= time + hours * 3_600_000L) break;
                    if (item.end() > time) row.add(item);
                }
            rows.put(id, row);
        }
        return rows;
    }
}
