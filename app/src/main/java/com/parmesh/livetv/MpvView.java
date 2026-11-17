package com.parmesh.livetv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Gravity;
import android.view.View;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

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
    private double videoAspect;
    private View container;
    private final OnLayoutChangeListener containerLayout =
            (v, l, t, r, b, ol, ot, or, ob) -> fitVideo();
    long frames;

    MpvView(Context context) {
        super(context);
        setSurfaceTextureListener(this);
        setFocusable(false);
        setOpaque(true);
    }

    void aspect(double aspect) {
        if (!Double.isFinite(aspect) || aspect <= 0 || aspect == videoAspect) return;
        videoAspect = aspect;
        fitVideo();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        container = (View) getParent();
        container.addOnLayoutChangeListener(containerLayout);
        fitVideo();
    }
    @Override protected void onDetachedFromWindow() {
        container.removeOnLayoutChangeListener(containerLayout);
        container = null;
        super.onDetachedFromWindow();
    }
    private void fitVideo() {
        if (container == null || videoAspect <= 0) return;
        int width = container.getWidth() - container.getPaddingLeft() - container.getPaddingRight();
        int height = container.getHeight() - container.getPaddingTop() - container.getPaddingBottom();
        if (width <= 0 || height <= 0) return;
        if ((double) width / height > videoAspect) {
            width = Math.max(1, (int) Math.round(height * videoAspect));
        } else {
            height = Math.max(1, (int) Math.round(width / videoAspect));
        }
        // Keep every texture pixel inside the picture; the parent draws the bars.
        // This avoids uncovered regions within the decoder texture.
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) getLayoutParams();
        if (params.width == width && params.height == height && params.gravity == Gravity.CENTER) return;
        setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
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
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        target.release(); target = null;
        // A worker may still be stopping the decoder. It owns the remaining reference.
        return false;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { ++frames; }
}
