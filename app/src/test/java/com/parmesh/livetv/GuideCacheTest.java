package com.parmesh.livetv;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class GuideCacheTest {
    @Test public void cachesEmptyRowsAndExpiresThemPerWindow() {
        var cache = new GuideCache(3);
        cache.put(0, "channel", List.of(), 100);
        assertEquals(List.of(), cache.get(0, "channel", 101));
        assertNull(cache.get(GuideCache.WINDOW, "channel", 101));
        assertNull(cache.get(0, "channel", 100 + GuideCache.FRESH));
    }

    @Test public void capsEntriesAndRetainsMostRecentlyViewedRows() {
        var cache = new GuideCache(2);
        var data = new ArrayList<>(List.of(new Channels.Program("News", 1, 2)));
        cache.put(0, "a", data, 10);
        cache.put(0, "b", List.of(), 10);
        data.clear();
        assertEquals(1, cache.get(0, "a", 11).size());
        cache.put(0, "c", List.of(), 12);
        assertNull(cache.get(0, "b", 12));
        assertNotNull(cache.get(0, "a", 12));
    }
}
