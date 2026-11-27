package com.parmesh.livetv;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ChannelsTest {
    @Test
    public void searchSupportsAccentsNumbersAndFavorites() {
        var featured = new Channels.Channel("news", "Télé Québec", "73.2", false, false, List.of());
        var numbered = new Channels.Channel("id40", "Channel 40", "40", false, false, List.of());
        var all = List.of(numbered, featured);
        assertEquals(
                List.of(featured), Channels.filter(all, Set.of("news"), true, "", "quebec tele"));
        assertEquals(featured, Channels.filter(all, Set.of(), false, "", "73.2").get(0));
        assertEquals("id40", Channels.filter(all, Set.of(), false, "", "40").get(0).id());
        assertSame(all, Channels.filter(all, Set.of(), false, "", " "));
        assertTrue(Channels.filter(all, Set.of(), true, "", "quebec").isEmpty());
    }

    @Test
    public void jumpsUseFilteredIndicesAndClampAtBoundaries() {
        var channels =
                List.of(
                        new Channels.Channel("1", "Élan", "", false, false, List.of()),
                        new Channels.Channel("2", "  Beta News", "", false, false, List.of("News")),
                        new Channels.Channel("3", "# Seven", "", false, false, List.of()),
                        new Channels.Channel(
                                "4",
                                "Beta Weather",
                                "",
                                false,
                                false,
                                List.of("Local", "Weather")));
        assertEquals(
                List.of("#", "B", "E"), new ArrayList<>(Channels.jumpTargets(channels).keySet()));
        assertEquals(Integer.valueOf(0), Channels.jumpTargets(channels).get("E"));
        assertEquals(Integer.valueOf(1), Channels.jumpTargets(channels).get("B"));
        var filtered = Channels.filter(channels, Set.of(), false, "Local", "beta");
        assertEquals(Integer.valueOf(0), Channels.jumpTargets(filtered).get("B"));
        assertEquals(0, Channels.indexOf(filtered, "4"));
        assertEquals(-1, Channels.indexOf(filtered, "2"));
        assertEquals(3, Channels.jumpPage(1, channels.size(), Integer.MAX_VALUE));
        assertEquals(0, Channels.jumpPage(0, channels.size(), -50));
        assertEquals(0, Channels.jumpPage(10, 0, 50));
    }

    @Test
    public void programBoundariesAndMalformedTimes() {
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
