package com.parmesh.livetv;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** One replaceable, encrypted snapshot per signed-in profile. Stream URLs can contain passwords. */
final class LineupCache {
    // Compressed snapshots are optional. Bound heap use on low-memory TVs.
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    // AtomicFile requires coordination across activity instances sharing a profile.
    private static final Object FILES = new Object();
    private final AtomicFile file;
    private boolean closed;
    private byte[] savedDigest;

    LineupCache(Context context, Jellyfin.Session profile) {
        file =
                new AtomicFile(
                        new File(
                                context.getCacheDir(),
                                "lineup-v1-" + SessionStore.profileId(profile)));
    }

    List<Channels.Channel> read(TvSource source) {
        try (FileInputStream input = open();
                DataInputStream raw = new DataInputStream(input)) {
            byte[] iv = new byte[12];
            raw.readFully(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, SessionStore.key(), new GCMParameterSpec(128, iv));
            long length = input.getChannel().size() - iv.length;
            if (length < 16 || length > MAX_BYTES) return List.of();
            byte[] encrypted = new byte[(int) length];
            raw.readFully(encrypted);
            byte[] compressed = cipher.doFinal(encrypted);
            try (DataInputStream data =
                    new DataInputStream(
                            new BufferedInputStream(
                                    new GZIPInputStream(new ByteArrayInputStream(compressed))))) {
                int size = data.readInt();
                List<Channels.Channel> channels = new ArrayList<>();
                Map<String, PlaylistSource.Entry> entries = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    String id = data.readUTF(), name = data.readUTF(), number = data.readUTF();
                    boolean adult = data.readBoolean();
                    if (source instanceof PlaylistSource) {
                        entries.put(
                                id,
                                new PlaylistSource.Entry(
                                        name, number, data.readUTF(), data.readUTF(), adult));
                    } else {
                        channels.add(
                                new Channels.Channel(id, name, number, data.readBoolean(), adult));
                    }
                }
                if (size < 0 || data.read() != -1) throw new IOException("Invalid lineup snapshot");
                savedDigest = digest(compressed);
                return source instanceof PlaylistSource playlist
                        ? playlist.channelList(entries)
                        : channels;
            }
        } catch (FileNotFoundException missing) {
            return List.of();
        } catch (Exception unreadable) {
            Log.w("LineupCache", "Saved lineup unavailable");
            return List.of();
        }
    }

    private FileInputStream open() throws FileNotFoundException {
        synchronized (FILES) {
            return file.openRead();
        }
    }

    void write(List<Channels.Channel> channels, TvSource source) {
        try {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DataOutputStream data =
                    new DataOutputStream(
                            new BufferedOutputStream(new GZIPOutputStream(compressed)))) {
                data.writeInt(channels.size());
                for (Channels.Channel c : channels) {
                    if (compressed.size() > MAX_BYTES) {
                        clear();
                        return;
                    }
                    data.writeUTF(c.id());
                    data.writeUTF(c.name());
                    data.writeUTF(c.number());
                    data.writeBoolean(c.adult());
                    if (source instanceof PlaylistSource playlist) {
                        PlaylistSource.Entry e = playlist.entry(c.id());
                        data.writeUTF(e.guide());
                        data.writeUTF(e.url());
                    } else {
                        data.writeBoolean(c.favorite());
                    }
                }
            }
            if (compressed.size() + 16 > MAX_BYTES) {
                clear();
                return;
            }
            byte[] bytes = compressed.toByteArray();
            byte[] digest = digest(bytes);
            if (Arrays.equals(digest, savedDigest) && file.getBaseFile().isFile()) return;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, SessionStore.key());
            byte[] encrypted = cipher.doFinal(bytes);
            synchronized (FILES) {
                if (closed) return;
                FileOutputStream output = file.startWrite();
                try {
                    output.write(cipher.getIV());
                    output.write(encrypted);
                    file.finishWrite(output);
                    savedDigest = digest;
                } catch (IOException failure) {
                    file.failWrite(output);
                    throw failure;
                }
            }
        } catch (Exception unavailable) {
            Log.w("LineupCache", "Could not save lineup");
        }
    }

    private static byte[] digest(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    void close(boolean remove) {
        synchronized (FILES) {
            closed = true;
            if (remove) file.delete();
        }
    }

    void clear() {
        synchronized (FILES) {
            if (!closed) file.delete();
        }
    }
}
