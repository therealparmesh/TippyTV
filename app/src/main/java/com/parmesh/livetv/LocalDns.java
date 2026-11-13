package com.parmesh.livetv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import okhttp3.Dns;

/** Queries local hostnames on older Android releases whose DNS resolver cannot resolve .local. */
final class LocalDns implements Dns {
    private static final byte[] MULTICAST = {(byte) 224, 0, 0, (byte) 251};
    private static final int PORT = 5353, A = 1, AAAA = 28;
    private final Map<String, Cached> cache = new LinkedHashMap<>(8, .75f, true);

    private record Cached(List<InetAddress> addresses, long expires) {}
    record Answer(InetAddress address, long ttl) {}

    @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        String name = hostname.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        if (!name.endsWith(".local")) return Dns.SYSTEM.lookup(hostname);
        List<InetAddress> resolved = localAddresses(name);
        return resolved.isEmpty() ? Dns.SYSTEM.lookup(hostname) : resolved;
    }

    private synchronized List<InetAddress> localAddresses(String name) {
        long now = System.nanoTime();
        Cached saved = cache.get(name);
        if (saved != null && now - saved.expires < 0) return saved.addresses;
        cache.remove(name);
        List<Answer> answers;
        try {
            answers = resolve(name);
        } catch (IOException | SecurityException failure) {
            return List.of();
        }
        if (answers.isEmpty()) return List.of();
        long ttl = 10;
        List<InetAddress> addresses = new ArrayList<>(answers.size());
        for (Answer answer : answers) {
            if (!addresses.contains(answer.address)) addresses.add(answer.address);
            ttl = Math.min(ttl, answer.ttl);
        }
        List<InetAddress> result = List.copyOf(addresses);
        if (!result.isEmpty() && ttl > 0) {
            cache.put(name, new Cached(result, System.nanoTime() + TimeUnit.SECONDS.toNanos(ttl)));
            if (cache.size() > 16) cache.remove(cache.keySet().iterator().next());
        }
        return result;
    }

    private static List<Answer> resolve(String name) throws IOException {
        int id = ThreadLocalRandom.current().nextInt(1, 65535);
        // An ephemeral source port requests a legacy unicast reply, which avoids Wi-Fi multicast receive filtering.
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress multicast = InetAddress.getByAddress(MULTICAST);
            for (int attempt = 0; attempt < 3; attempt++) {
                for (int type : new int[]{A, AAAA}) {
                    byte[] query = question(name, type == A ? id : id + 1, type);
                    socket.send(new DatagramPacket(query, query.length, multicast, PORT));
                }
                long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(650);
                List<Answer> v4 = new ArrayList<>(), v6 = new ArrayList<>();
                while (System.nanoTime() < end) {
                    socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())));
                    DatagramPacket response = new DatagramPacket(new byte[4096], 4096);
                    try { socket.receive(response); }
                    catch (SocketTimeoutException timeout) { break; }
                    if (response.getPort() != PORT || response.getLength() < 12) continue;
                    byte[] packet = Arrays.copyOf(response.getData(), response.getLength());
                    int replyId = word(packet, 0);
                    if (replyId == id) v4.addAll(parse(packet, name, id, A));
                    if (replyId == id + 1) v6.addAll(parse(packet, name, id + 1, AAAA));
                    if (!v4.isEmpty()) end = Math.min(end, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50));
                }
                if (!v4.isEmpty() || !v6.isEmpty()) {
                    v4.addAll(v6);
                    return v4;
                }
            }
            return List.of();
        }
    }

    static byte[] question(String host, int id, int type) throws UnknownHostException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = {(byte) (id >> 8), (byte) id, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0};
        out.write(header, 0, header.length);
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) throw new UnknownHostException(host);
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
        out.write(0); out.write(type);
        out.write(0); out.write(1);
        return out.toByteArray();
    }

    static List<Answer> parse(byte[] packet, String host, int id, int type) {
        if (packet.length < 12 || word(packet, 0) != id || (word(packet, 2) & 0xf80f) != 0x8000
                || word(packet, 4) != 1) return List.of();
        try {
            int[] cursor = {12};
            if (!readName(packet, cursor).equalsIgnoreCase(host)
                    || word(packet, cursor[0]) != type || word(packet, cursor[0] + 2) != 1) return List.of();
            cursor[0] += 4;
            List<Answer> answers = new ArrayList<>();
            for (int i = 0, count = word(packet, 6); i < count; i++) {
                String name = readName(packet, cursor);
                int offset = cursor[0];
                int rrType = word(packet, offset), rrClass = word(packet, offset + 2) & 0x7fff;
                long ttl = ((long) word(packet, offset + 4) << 16) | word(packet, offset + 6);
                int length = word(packet, offset + 8), start = offset + 10;
                if (start + length > packet.length) return List.of();
                if (rrType == type && rrClass == 1 && length == (type == A ? 4 : 16) && name.equalsIgnoreCase(host)) {
                    InetAddress address = InetAddress.getByAddress(host, Arrays.copyOfRange(packet, start, start + length));
                    if (!address.isAnyLocalAddress() && !address.isMulticastAddress() && !address.isLoopbackAddress()
                            && (type == A || !address.isLinkLocalAddress())) {
                        answers.add(new Answer(address, ttl));
                    }
                }
                cursor[0] = start + length;
            }
            return answers;
        } catch (IndexOutOfBoundsException | UnknownHostException invalid) {
            return List.of();
        }
    }

    private static int word(byte[] packet, int offset) {
        return ((packet[offset] & 255) << 8) | (packet[offset + 1] & 255);
    }

    private static String readName(byte[] packet, int[] cursor) {
        StringBuilder name = new StringBuilder();
        int position = cursor[0];
        boolean pointed = false;
        for (int labels = 0; labels < 128; labels++) {
            int length = packet[position++] & 255;
            if (length == 0) {
                if (!pointed) cursor[0] = position;
                return name.toString();
            }
            if ((length & 0xc0) == 0xc0) {
                int next = ((length & 0x3f) << 8) | (packet[position++] & 255);
                if (next >= position - 2) break;
                if (!pointed) cursor[0] = position;
                position = next;
                pointed = true;
            } else {
                if (length > 63 || name.length() + length >= 254) break;
                if (name.length() > 0) name.append('.');
                name.append(new String(packet, position, length, StandardCharsets.US_ASCII));
                position += length;
            }
        }
        throw new IndexOutOfBoundsException("Invalid DNS name");
    }
}
