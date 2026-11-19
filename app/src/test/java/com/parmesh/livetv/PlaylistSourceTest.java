package com.parmesh.livetv;

import java.io.IOException;
import okhttp3.HttpUrl;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaylistSourceTest {
    private static final HttpUrl BASE = HttpUrl.get("https://example.com/group/list");

    @Test public void duplicateGuideIdsKeepSeparateChannelsAcrossReordering() throws IOException {
        String a = "#EXTINF:-1 tvg-id=\"news\",News East\neast.m3u8\n";
        String b = "#EXTINF:-1 tvg-id=\"news\",News West\nwest.m3u8\n";
        String other = "#EXTINF:-1,Other\no.ts\n";
        var first = PlaylistSource.parse("#EXTM3U\n" + a + other + b, BASE, "News");
        assertEquals(java.util.List.of("News East", "Other", "News West"),
                first.values().stream().map(PlaylistSource.Entry::name).toList());
        assertEquals(first, PlaylistSource.parse("#EXTM3U\n" + b + other + a, BASE, "News"));
        assertTrue(first.values().stream().anyMatch(e -> e.url().encodedPath().equals("/group/east.m3u8")));
        assertFalse(first.containsKey("id:news"));
    }

    @Test public void hlsManifestIsOneChannelAndUsesManifestUrl() throws IOException {
        String data = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nsegment.ts\n";
        var channels = PlaylistSource.parse(data, BASE, "Live");
        assertEquals(1, channels.size());
        assertEquals(BASE, channels.get("stream").url());
    }

    @Test public void titleCanContainQuotedTextThatLooksLikeAnAttribute() throws IOException {
        var rows = PlaylistSource.parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" group-title=\"TV, US\",News tvg-id=\"west\", Live\na.ts", BASE, "Live");
        assertEquals("News tvg-id=\"west\", Live", rows.get("id:a").name());
    }

    @Test public void plainM3uCanUseRelativePaths() throws IOException {
        assertEquals(BASE.resolve("news.ts"), PlaylistSource.parse("news.ts\n", BASE, "Live").values().iterator().next().url());
    }

    @Test public void missingGuideStopUsesNextProgrammeInTimeOrder() {
        var row = new java.util.ArrayList<>(java.util.List.of(new Channels.Program("Next", 20, 30),
                new Channels.Program("Now", 10, -1)));
        assertEquals(java.util.List.of(new Channels.Program("Now", 10, 20),
                new Channels.Program("Next", 20, 30)), PlaylistSource.guideRow(row));
    }

    @Test public void htmlResponseCannotReplaceTheLineup() {
        assertThrows(IOException.class, () -> PlaylistSource.parse("<html>Unavailable</html>", BASE, "Live"));
    }
}
