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
}
