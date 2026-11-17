package com.parmesh.livetv;

import android.content.Context;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;

/** One libmpv handle per picture. Creation/destruction run on the owning slot's worker. */
final class MpvPlayer {
    static { System.loadLibrary("tippympv"); }
    private static boolean initialized;
    private final long handle;
    private final MpvView.Target target;

    private static synchronized String prepare(Context context) throws IOException {
        File cert = new File(context.getFilesDir(), "mpv-ca.pem");
        if (!initialized) {
            try (var input = context.getAssets().open("cacert.pem"); var output = new FileOutputStream(cert)) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            }
            environment(context.getApplicationContext());
            initialized = true;
        }
        return cert.getAbsolutePath();
    }

    MpvPlayer(Context context, MpvView.Target target, Jellyfin.Stream stream) throws IOException {
        this.target = target;
        handle = create(target.surface, stream.url().toString(),
                "TippyTV/" + BuildConfig.VERSION_NAME,
                prepare(context));
    }
    double aspect() { return aspect(handle); }
    double buffered() { return buffered(handle); }
    int state() { return poll(handle); }
    String diagnostics() { return snapshot(handle); }
    void pause() { setPaused(true); }
    void setPaused(boolean paused) { property(handle, "pause", paused ? "yes" : "no"); }
    void volume(int volume) { property(handle, "volume", Integer.toString(volume)); }
    void close() { try { destroy(handle); } finally { target.release(); } }

    private static native void environment(Context context);
    private static native long create(Surface surface, String url, String agent, String ca);
    private static native double aspect(long handle);
    private static native double buffered(long handle);
    private static native int poll(long handle);
    private static native String snapshot(long handle);
    private static native void property(long handle, String name, String value);
    private static native void destroy(long handle);
}
