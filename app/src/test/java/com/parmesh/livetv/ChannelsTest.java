package com.parmesh.livetv;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class ChannelsTest {
    @Test public void largeCatalogSearchSupportsAccentsNumbersAndFavorites() {
        List<Channels.Channel> all = new ArrayList<>();
        for (int i = 0; i < 25_000; i++) all.add(new Channels.Channel("id" + i, "Channel " + i, "" + i, false));
        var featured = new Channels.Channel("news", "Télé Québec", "73.2", false);
        all.add(featured);
        assertEquals(List.of(featured), Channels.filter(all, Set.of("news"), true, "quebec tele"));
        assertEquals(featured, Channels.filter(all, Set.of(), false, "73.2").get(0));
        assertEquals("id40", Channels.filter(all, Set.of(), false, "40").get(0).id());
        assertSame(all, Channels.filter(all, Set.of(), false, " "));
        assertTrue(Channels.filter(all, Set.of(), true, "quebec").isEmpty());
    }

    @Test public void jumpsUseFilteredIndicesAndClampAtBoundaries() {
        var channels = List.of(new Channels.Channel("1", "Élan", "", false),
                new Channels.Channel("2", "Beta", "", false),
                new Channels.Channel("3", "# Seven", "", false));
        assertEquals(List.of("#", "B", "E"), new ArrayList<>(Channels.jumpTargets(channels).keySet()));
        assertEquals(Integer.valueOf(0), Channels.jumpTargets(channels).get("E"));
        assertEquals(2, Channels.jumpPage(1, channels.size(), Integer.MAX_VALUE));
        assertEquals(0, Channels.jumpPage(0, channels.size(), -50));
        assertEquals(0, Channels.jumpPage(10, 0, 50));
    }

    @Test public void programmeBoundariesAndMalformedTimes() {
        var a = new Channels.Program("First", 10, 20);
        var b = new Channels.Program("Second", 20, 40);
        var schedule = Channels.sortedPrograms(new ArrayList<>(List.of(b, a)));
        assertEquals(a, Channels.now(schedule, 19));
        assertEquals(b, Channels.now(schedule, 20));
        assertEquals(b, Channels.next(schedule, 19));
        assertNull(Channels.now(schedule, 40));
        assertEquals(-1, Channels.timestamp("invalid"));
        assertEquals(0, Channels.timestamp("1970-01-01T00:00:00Z"));
        assertEquals(1_000, Channels.timestamp("1970-01-01T01:00:01+01:00"));
    }
}
