package com.parmesh.livetv;

import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class Channels {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final String JUMP_KEYS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    // Providers may combine several group labels in one field.
    private static final Pattern ADULT_LABEL =
            Pattern.compile(
                    "(?:^|[;|:/\\[({_-])\\s*(?:xxx|adult)\\s*(?=$|[;|:/\\])}_-])",
                    Pattern.CASE_INSENSITIVE);

    static boolean adultGroup(String label) {
        return ADULT_LABEL.matcher(label).find();
    }

    record Channel(
            String id,
            String name,
            String number,
            boolean favorite,
            boolean adult,
            String searchKey,
            int jumpBucket) {
        Channel(String id, String name, String number, boolean favorite, boolean adult) {
            this(id, name, number, favorite, adult, normalize(name));
        }

        private Channel(
                String id,
                String name,
                String number,
                boolean favorite,
                boolean adult,
                String normalizedName) {
            this(
                    id,
                    name,
                    number,
                    favorite,
                    adult,
                    (normalizedName + " " + normalize(number)).trim(),
                    bucket(normalizedName));
        }

        String label() {
            return number.isBlank() ? name : number + "  " + name;
        }
    }

    record Program(String title, long start, long end) {
        boolean onAir(long now) {
            return start <= now && now < end;
        }
    }

    static String normalize(String s) {
        return MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD))
                .replaceAll("")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    static List<Channel> filter(
            List<Channel> source, Set<String> favorites, boolean favoriteOnly, String query) {
        String normalized = normalize(query);
        String[] words = normalized.isEmpty() ? new String[0] : SPACES.split(normalized);
        if (!favoriteOnly && words.length == 0) return source;
        List<Channel> result = new ArrayList<>();
        List<Channel> exactNumbers = new ArrayList<>();
        outer:
        for (Channel c : source) {
            if (favoriteOnly && !favorites.contains(c.id)) continue;
            for (String word : words) if (!c.searchKey.contains(word)) continue outer;
            if (!normalized.isEmpty() && normalized.equals(c.number)) exactNumbers.add(c);
            else result.add(c);
        }
        if (exactNumbers.isEmpty()) return result;
        exactNumbers.addAll(result);
        return exactNumbers;
    }

    static Map<String, Integer> jumpTargets(List<Channel> channels) {
        int[] first = new int[JUMP_KEYS.length()];
        for (int i = 0; i < channels.size(); i++) {
            int bucket = channels.get(i).jumpBucket;
            if (first[bucket] == 0) first[bucket] = i + 1;
        }
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (int i = 0; i < first.length; i++) {
            if (first[i] > 0) ordered.put(String.valueOf(JUMP_KEYS.charAt(i)), first[i] - 1);
        }
        return ordered;
    }

    private static int bucket(String name) {
        if (name.isEmpty()) return 0;
        char initial = Character.toUpperCase(name.charAt(0));
        return initial >= 'A' && initial <= 'Z' ? initial - 'A' + 1 : 0;
    }

    static int indexOf(List<Channel> channels, String id) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    static int jumpPage(int position, int size, int delta) {
        return size == 0 ? 0 : (int) Math.min(size - 1L, Math.max(0L, (long) position + delta));
    }

    static Program now(List<Program> programs, long time) {
        for (Program p : programs) if (p.onAir(time)) return p;
        return null;
    }

    static Program next(List<Program> programs, long time) {
        for (Program p : programs) if (p.start > time) return p;
        return null;
    }

    static long timestamp(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException invalid) {
            try {
                return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
            } catch (RuntimeException malformed) {
                return -1;
            }
        }
    }

    static List<Program> sortedPrograms(List<Program> programs) {
        programs.sort(java.util.Comparator.comparingLong(Program::start));
        return Collections.unmodifiableList(programs);
    }
}
