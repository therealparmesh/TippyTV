package com.parmesh.livetv;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GuideCache {
    static final long WINDOW = 30 * 60_000L;
    static final long FRESH = 120_000L;
    private final int capacity;
    private final Map<Key, Entry> rows;

    private record Key(long window, String channel) {}

    private record Entry(long expires, List<Channels.Program> programs) {}

    GuideCache(int capacity) {
        this.capacity = capacity;
        rows = new LinkedHashMap<>(32, .75f, true);
    }

    static long window(long time) {
        return Math.floorDiv(time, WINDOW) * WINDOW;
    }

    List<Channels.Program> get(long window, String id, long clock) {
        Key key = new Key(window, id);
        Entry entry = rows.get(key);
        if (entry == null) return null;
        if (clock >= entry.expires) {
            rows.remove(key);
            return null;
        }
        return entry.programs;
    }

    void put(long window, String id, List<Channels.Program> programs, long clock) {
        rows.put(new Key(window, id), new Entry(clock + FRESH, List.copyOf(programs)));
        while (rows.size() > capacity) rows.remove(rows.keySet().iterator().next());
    }

    void clear() {
        rows.clear();
    }
}
