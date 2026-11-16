package com.parmesh.livetv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

/** A stable render target, retained until the native player finishes tearing down. */
final class MpvView extends TextureView implements TextureView.SurfaceTextureListener {
    static final class Target {
        final Surface surface;
        private final SurfaceTexture texture;
        private int references = 1;
        Target(SurfaceTexture texture) { this.texture = texture; surface = new Surface(texture); }
        synchronized Target retain() { ++references; return this; }
        synchronized void release() {
            if (--references == 0) { surface.release(); texture.release(); }
        }
    }

    private Target target;
    private Runnable ready;
    Runnable resized;
    long frames;

    MpvView(Context context) {
        super(context);
        setSurfaceTextureListener(this);
        setFocusable(false);
        setOpaque(true);
    }

    Target acquire() { return target == null ? null : target.retain(); }
    void whenReady(Runnable action) {
        ready = action;
        if (target != null && action != null) { ready = null; action.run(); }
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        target = new Target(texture);
        if (ready != null) { Runnable action = ready; ready = null; action.run(); }
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        if (resized != null) resized.run();
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        target.release(); target = null;
        // A worker may still be stopping the decoder. It owns the remaining reference.
        return false;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { ++frames; }
}
