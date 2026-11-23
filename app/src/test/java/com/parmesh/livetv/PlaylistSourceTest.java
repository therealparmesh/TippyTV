package com.parmesh.livetv;

import static org.junit.Assert.*;

import okhttp3.HttpUrl;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class PlaylistSourceTest {
    private static final HttpUrl BASE = HttpUrl.get("https://example.com/group/list");

    @Test
    public void xtreamProfilesUseSeparateFavoriteStoresAndEncodeCredentials() {
        for (String path : java.util.List.of("/iptv", "/iptv/")) {
            HttpUrl server = HttpUrl.get("https://example.com:8443" + path);
            var session = Jellyfin.Session.xtream("Living room", server, "user+1", "p&?/#%");
            assertTrue(session.direct());
            assertTrue(SessionStore.legacyXtream(session.playlist(), session.guide()));
            assertFalse(
                    SessionStore.legacyXtream(session.playlist() + "&group=kids", session.guide()));
            assertEquals("Living room", session.label());
            assertNotEquals(
                    session.user(),
                    Jellyfin.Session.xtream("Second", server, "other", "pw").user());
            HttpUrl playlist = HttpUrl.get(session.playlist()),
                    guide = HttpUrl.get(session.guide());
            assertEquals("/iptv/get.php", playlist.encodedPath());
            assertEquals("/iptv/xmltv.php", guide.encodedPath());
            for (HttpUrl url : java.util.List.of(playlist, guide)) {
                assertEquals(8443, url.port());
                assertEquals("user+1", url.queryParameter("username"));
                assertEquals("p&?/#%", url.queryParameter("password"));
            }
            assertEquals("m3u_plus", playlist.queryParameter("type"));
            assertEquals("ts", playlist.queryParameter("output"));
        }
    }

    @Test
    public void duplicateGuideIdsKeepSeparateChannelsAcrossReordering() throws IOException {
        String a = "#EXTINF:-1 tvg-id=\"news\",News East\neast.m3u8\n";
        String b = "#EXTINF:-1 tvg-id=\"news\",News West\nwest.m3u8\n";
        String other = "#EXTINF:-1,Other\no.ts\n";
        var first = PlaylistSource.parse("#EXTM3U\n" + a + other + b, BASE, "News");
        assertEquals(
                java.util.List.of("News East", "Other", "News West"),
                first.values().stream().map(PlaylistSource.Entry::name).toList());
        assertEquals(first, PlaylistSource.parse("#EXTM3U\n" + b + other + a, BASE, "News"));
        assertTrue(
                first.values().stream()
                        .anyMatch(e -> e.url().encodedPath().equals("/group/east.m3u8")));
        assertFalse(first.containsKey("id:news"));
    }

    @Test
    public void hlsManifestIsOneChannelAndUsesManifestUrl() throws IOException {
        String data = "\uFEFF\r\n#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nsegment.ts\n";
        var channels = PlaylistSource.parse(data, BASE, "Live");
        assertEquals(1, channels.size());
        assertEquals(BASE, channels.get("stream").url());
    }

    @Test
    public void titleCanContainQuotedTextThatLooksLikeAnAttribute() throws IOException {
        var rows =
                PlaylistSource.parse(
                        "#EXTM3U\n"
                            + "#EXTINF:-1 tvg-id=\"a\" group-title=\"TV, US\",News tvg-id=\"west\","
                            + " Live\n"
                            + "a.ts",
                        BASE,
                        "Live");
        assertEquals("News tvg-id=\"west\", Live", rows.get("id:a").name());
    }

    @Test
    public void plainM3uCanUseRelativePaths() throws IOException {
        assertEquals(
                BASE.resolve("news.ts"),
                PlaylistSource.parse("news.ts\n", BASE, "Live").values().iterator().next().url());
    }

    @Test
    public void missingGuideStopUsesNextProgrammeInTimeOrder() {
        var row =
                new java.util.ArrayList<>(
                        java.util.List.of(
                                new Channels.Program("Next", 20, 30),
                                new Channels.Program("Now", 10, -1)));
        assertEquals(
                java.util.List.of(
                        new Channels.Program("Now", 10, 20), new Channels.Program("Next", 20, 30)),
                PlaylistSource.guideRow(row));
    }

    @Test
    public void m3uGroupDirectivesCarryForwardUntilReset() throws IOException {
        var entries =
                List.copyOf(
                        PlaylistSource.parse(
                                        "#EXTM3U\n"
                                                + "#EXTGRP:HD;XXX\n"
                                                + "#EXTINF:-1,East\n"
                                                + "a.ts\n"
                                                + "#EXTINF:-1,West\n"
                                                + "b.ts\n"
                                                + "#EXTINF:-1 group-title=\"General\",XXX"
                                                + "\n"
                                                + "c.ts\n"
                                                + "#EXTGRP:Adult\n"
                                                + "#EXTGRP:\n"
                                                + "#EXTINF:-1,News\n"
                                                + "d.ts\n"
                                                + "#EXTINF:-1 group-title=\"Adult\",Local\ne.ts",
                                        BASE,
                                        "QA")
                                .values());
        assertEquals(
                List.of(true, true, false, false, true),
                entries.stream().map(PlaylistSource.Entry::adult).toList());
        var entry = entries.get(2);
        assertFalse(new Channels.Channel("c", entry.name(), "", false, entry.adult()).adult());
    }

    @Test
    public void htmlResponseCannotReplaceTheLineup() {
        assertThrows(
                IOException.class,
                () -> PlaylistSource.parse("<html>Unavailable</html>", BASE, "Live"));
    }
}
