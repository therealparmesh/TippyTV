package com.parmesh.livetv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;
import okhttp3.*;
import org.xmlpull.v1.XmlPullParser;

final class PlaylistSource implements TvSource {
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().dns(new LocalDns())
            .callTimeout(45, TimeUnit.SECONDS).build();
    private static final Pattern RELATIVE_MEDIA = Pattern.compile("(?i)[^<>]+\\.(?:m3u8|ts|mp4|aac|mp3)(?:[?#].*)?");
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
    private final Jellyfin.Session profile;
    private final SharedPreferences stars;
    record Entry(String name, String number, String guide, HttpUrl url) {}
    private volatile Map<String, Entry> entries = Map.of();
    private List<String> order = List.of();
    private Map<String, List<Channels.Program>> listings = Map.of();
    private Map<String, Entry> guideCatalog;
    private long guideExpires;
    private final Object guideLock = new Object();
    PlaylistSource(Context context, Jellyfin.Session profile) {
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
        try (Response r = fetch(url)) { return body(r, limit); }
    }
    private static String body(Response r, int limit) throws IOException {
        if (r.body().source().request(limit + 1L)) throw new IOException("File is too large.");
        return r.body().string();
    }

    static Map<String, Entry> download(Jellyfin.Session profile) throws IOException {
        try (Response r = fetch(HttpUrl.get(profile.playlist()))) {
            HttpUrl base = r.request().url();
            String data = body(r, 16_000_000).replaceFirst("^\\uFEFF", "");
            return parse(data, base, profile.name());
        }
    }

    static Map<String, Entry> parse(String data, HttpUrl base, String profileName) throws IOException {
        Map<String, Entry> loaded = new LinkedHashMap<>();
        if (data.startsWith("#EXTM3U") && Pattern.compile("(?m)^#EXT-X-(STREAM-INF|TARGETDURATION):").matcher(data).find()) {
            loaded.put("stream", new Entry(profileName, "", "", base));
        } else {
            String name = "", number = "", guide = "";
            List<Entry> channels = new ArrayList<>();
            Set<String> seen = new HashSet<>(), ambiguous = new HashSet<>();
            boolean extended = data.stripLeading().startsWith("#EXTM3U");
            BufferedReader reader = new BufferedReader(new StringReader(data));
            for (String line; (line = reader.readLine()) != null;) {
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
                    guide = ""; number = "";
                    while (attrs.find()) {
                        if (attrs.group(1).equals("tvg-id")) guide = attrs.group(2);
                        if (attrs.group(1).equals("tvg-chno")) number = attrs.group(2);
                    }
                    name = comma == line.length() ? "" : line.substring(comma + 1).trim();
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    HttpUrl url = HttpUrl.parse(line);
                    if (url == null && (extended || line.startsWith("/") || line.startsWith("./")
                            || RELATIVE_MEDIA.matcher(line).matches())) url = base.resolve(line);
                    if (url != null) {
                        String label = name.isEmpty() ? url.host() : name;
                        Entry entry = new Entry(label, number, guide, url);
                        channels.add(entry);
                        if (!guide.isEmpty() && !seen.add(guide)) ambiguous.add(guide);
                    }
                    name = ""; number = ""; guide = "";
                }
            }
            for (Entry entry : channels) {
                String guideId = entry.guide();
                String key = guideId.isEmpty() || ambiguous.contains(guideId) ? fingerprint(entry) : "id:" + guideId;
                loaded.put(key, entry);
            }
        }
        if (loaded.isEmpty()) throw new IOException("Playlist contains no playable channels.");
        return loaded;
    }
    private static String fingerprint(Entry e) {
        return "url:" + UUID.nameUUIDFromBytes((e.name() + "\n" + e.url()).getBytes(StandardCharsets.UTF_8));
    }
    public synchronized Jellyfin.Page channels(int start, int limit) throws IOException {
        if (start == 0) {
            entries = download(profile);
            order = new ArrayList<>(entries.keySet());
        }
        List<Channels.Channel> page = new ArrayList<>();
        for (int i = start; i < Math.min(order.size(), start + limit); i++) {
            String key = order.get(i);
            Entry e = entries.get(key);
            page.add(new Channels.Channel(key, e.name(), e.number(), e.name().equals(stars.getString(key, null))));
        }
        return new Jellyfin.Page(page, page.size(), order.size());
    }
    public Jellyfin.Stream open(Channels.Channel channel, boolean fallback, boolean inset, boolean recovering) throws IOException {
        Entry e = (recovering ? download(profile) : entries).get(channel.id());
        if (e == null || !e.name().equals(channel.name()))
            throw new IllegalStateException("Channel changed. Refresh the channel list.");
        return new Jellyfin.Stream(Jellyfin.nativePlaybackUrl(e.url()), "", "", false);
    }
    public void close(Jellyfin.Stream stream) {}
    public void logout() { stars.edit().clear().apply(); }
    public void favorite(Channels.Channel channel, boolean on) throws IOException {
        String id = channel.id();
        Entry entry = entries.get(id);
        if (entry == null || !entry.name().equals(channel.name())) throw new IOException("Channel is unavailable.");
        SharedPreferences.Editor edit = stars.edit();
        if (on) edit.putString(id, entry.name()); else edit.remove(id);
        edit.apply();
    }

    private static long timestamp(String value) {
        if (value == null) return -1;
        try {
            String[] p = value.trim().split("\\s+");
            String digits = p[0];
            if (digits.length() < 8 || digits.length() > 14 || digits.length() % 2 != 0) return -1;
            return LocalDateTime.parse(digits + "000000".substring(digits.length() - 8), STAMP).toInstant(p.length == 1 ? ZoneOffset.UTC : ZoneOffset.of(p[1])).toEpochMilli();
        } catch (RuntimeException invalid) { return -1; }
    }

    private void guide() throws IOException {
        if (profile.guide().isEmpty()) return;
        long now = System.currentTimeMillis();
        Map<String, Entry> catalog = entries;
        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (catalog == guideCatalog && elapsed < guideExpires) return;
        Map<String, List<Channels.Program>> result = new HashMap<>();
        Set<String> wanted = new HashSet<>();
        for (Entry e : catalog.values()) if (!e.guide().isEmpty()) wanted.add(e.guide());
        try (Response r = fetch(HttpUrl.get(profile.guide())); BufferedInputStream raw = new BufferedInputStream(r.body().byteStream())) {
            raw.mark(2);
            boolean gzip = raw.read() == 31 && raw.read() == 139;
            raw.reset();
            XmlPullParser xml = Xml.newPullParser();
            xml.setInput(gzip ? new GZIPInputStream(raw) : raw, null);
            xml.nextTag();
            xml.require(XmlPullParser.START_TAG, null, "tv");
            String channel = null, title = "";
            long start = 0, stop = 0;
            for (int event; (event = xml.next()) != XmlPullParser.END_DOCUMENT;) {
                if (event == XmlPullParser.START_TAG && xml.getName().equals("programme")) {
                    channel = xml.getAttributeValue(null, "channel");
                    start = timestamp(xml.getAttributeValue(null, "start"));
                    stop = timestamp(xml.getAttributeValue(null, "stop"));
                    title = "";
                } else if (event == XmlPullParser.START_TAG && channel != null && title.isEmpty() && xml.getName().equals("title")) {
                    title = xml.nextText();
                } else if (event == XmlPullParser.END_TAG && xml.getName().equals("programme")) {
                    if (wanted.contains(channel) && !title.isEmpty() && start >= 0 && (stop == -1 ? start >= now - 86_400_000L : stop > now - 86_400_000L && stop > start) && start < now + 8 * 86_400_000L)
                        result.computeIfAbsent(channel, k -> new ArrayList<>()).add(new Channels.Program(title, start, stop));
                    channel = null;
                }
            }
        } catch (org.xmlpull.v1.XmlPullParserException e) { throw new IOException("Could not read programme guide.", e); }
        result.replaceAll((k, rows) -> guideRow(rows));
        listings = result;
        guideCatalog = catalog;
        guideExpires = elapsed + 15 * 60_000L;
    }
    static List<Channels.Program> guideRow(List<Channels.Program> row) {
        List<Channels.Program> sorted = Channels.sortedPrograms(row);
        List<Channels.Program> result = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Channels.Program p = sorted.get(i);
            // XMLTV permits an omitted stop. The next programme supplies its boundary.
            if (p.end() < 0 && i + 1 < sorted.size())
                p = new Channels.Program(p.title(), p.start(), sorted.get(i + 1).start());
            if (p.end() > p.start()) result.add(p);
        }
        return result;
    }

    public boolean hasGuide(long time, int hours) throws IOException {
        synchronized (guideLock) { guide(); return !listings.isEmpty(); }
    }
    public Map<String, List<Channels.Program>> programs(List<String> ids, long time, int hours) throws IOException {
        Map<String, List<Channels.Program>> snapshot;
        synchronized (guideLock) { guide(); snapshot = listings; }
        Map<String, List<Channels.Program>> rows = new HashMap<>();
        for (String id : ids) {
            Entry e = entries.get(id);
            List<Channels.Program> row = new ArrayList<>();
            if (e != null) for (Channels.Program item : snapshot.getOrDefault(e.guide(), List.of())) {
                if (item.end() > time && item.start() < time + hours * 3_600_000L) row.add(item);
            }
            rows.put(id, row);
        }
        return rows;
    }
}
