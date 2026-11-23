package com.parmesh.livetv;

import static org.junit.Assert.*;

import org.junit.Test;

public class JellyfinUrlTest {
    @Test
    public void acceptsLocalHostsAndPreservesReverseProxyPaths() {
        assertEquals(
                "http://example.local:8097/",
                Jellyfin.serverUrl(" example.local:8097 ").toString());
        assertEquals(
                "/jellyfin/", Jellyfin.serverUrl("https://example.test/jellyfin/").encodedPath());
    }

    @Test
    public void rejectsCredentialsAndQueryInServerAddress() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Jellyfin.serverUrl("http://user:pass@example.test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Jellyfin.serverUrl("http://example.test?token=x"));
    }

    @Test
    public void resolvesRelativePlaybackAgainstTheProxyDirectoryAndRejectsOtherOrigins()
            throws Exception {
        for (String suffix : new String[] {"/jellyfin", "/jellyfin/"}) {
            Jellyfin client =
                    new Jellyfin(
                            new Jellyfin.Session(
                                    "https://server.test:8097" + suffix,
                                    "token",
                                    "user",
                                    "device"));
            assertEquals(
                    "https://server.test:8097/jellyfin/video.ts?api_key=token",
                    client.playbackUrl("video.ts").toString());
            assertEquals(
                    "/jellyfin/video.ts", client.playbackUrl("/jellyfin/video.ts").encodedPath());
            for (String remote :
                    new String[] {
                        "//provider.test/video.ts",
                        "http://server.test:8097/video.ts",
                        "https://server.test:8098/video.ts"
                    }) assertThrows(java.io.IOException.class, () -> client.playbackUrl(remote));
        }
    }
}
