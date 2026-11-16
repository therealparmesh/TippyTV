package com.parmesh.livetv;

import static org.junit.Assert.*;
import org.junit.Test;

public class JellyfinUrlTest {
    @Test public void acceptsLocalHostsAndPreservesReverseProxyPaths() {
        assertEquals("http://example.local:8097/", Jellyfin.serverUrl(" example.local:8097 ").toString());
        assertEquals("/jellyfin/", Jellyfin.serverUrl("https://example.test/jellyfin/").encodedPath());
    }

    @Test public void rejectsCredentialsAndQueryInServerAddress() {
        assertThrows(IllegalArgumentException.class, () -> Jellyfin.serverUrl("http://user:pass@example.test"));
        assertThrows(IllegalArgumentException.class, () -> Jellyfin.serverUrl("http://example.test?token=x"));
    }

    @Test public void authenticatesNativePlaybackOnlyOnJellyfinOrigin() throws Exception {
        Jellyfin client = new Jellyfin(new Jellyfin.Session("https://server.test:8097", "session-token", "user", "device"));
        assertEquals("session-token", client.playbackUrl(okhttp3.HttpUrl.get("https://server.test:8097/video.ts"))
                .queryParameter("api_key"));
        for (String remote : new String[] {"https://provider.test/video.ts", "http://server.test:8097/video.ts",
                "https://server.test:8098/video.ts"}) {
            assertNull(client.playbackUrl(okhttp3.HttpUrl.get(remote)).queryParameter("api_key"));
        }
        assertEquals("provider-key", client.playbackUrl(okhttp3.HttpUrl.get("https://provider.test/video.ts?api_key=provider-key"))
                .queryParameter("api_key"));
    }
}
