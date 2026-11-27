package com.parmesh.livetv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.util.function.Consumer;

/** A picture area that gives each native player its own render target. */
final class MpvView extends FrameLayout {
    static final class Target {
        final Surface surface;
        private final SurfaceTexture texture;
        private int references = 1;

        Target(SurfaceTexture texture) {
            this.texture = texture;
            surface = new Surface(texture);
        }

        synchronized void retain() {
            ++references;
        }

        synchronized void release() {
            if (--references == 0) {
                surface.release();
                texture.release();
            }
        }
    }

    private Output output;
    private double videoAspect;
    long frames;

    MpvView(Context context) {
        super(context);
        setFocusable(false);
    }

    void aspect(double aspect) {
        double ratio = Double.isFinite(aspect) && aspect > 0 ? aspect : 0;
        if (ratio != videoAspect) {
            videoAspect = ratio;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // The picture container supplies its current inner bounds, including after a PiP swap.
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        if (videoAspect > 0 && width > 0 && height > 0) {
            if (width > height * videoAspect)
                width = Math.max(1, (int) Math.round(height * videoAspect));
            else height = Math.max(1, (int) Math.round(width / videoAspect));
        }
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    void open(Consumer<Target> ready) {
        output = new Output(ready);
        addView(output, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void clear() {
        // Remove the outgoing buffer from the picture before resizing or tuning the next channel.
        Output previous = output;
        output = null;
        if (previous != null) removeView(previous);
        aspect(0);
    }

    private final class Output extends TextureView implements TextureView.SurfaceTextureListener {
        private Consumer<Target> ready;
        private Target target;

        Output(Consumer<Target> ready) {
            super(MpvView.this.getContext());
            this.ready = ready;
            setSurfaceTextureListener(this);
            setFocusable(false);
            setOpaque(false);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            target = new Target(texture);
            Consumer<Target> action = ready;
            ready = null;
            if (output == this && action != null) action.accept(target);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            if (target == null) return true;
            target.release();
            target = null;
            // A worker stopping the decoder may still own a reference.
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            if (output == this) ++frames;
        }
    }
}
