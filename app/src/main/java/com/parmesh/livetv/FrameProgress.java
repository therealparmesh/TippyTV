package com.parmesh.livetv;

/** Bound startup and stalled video; player state changes are not frame progress. */
final class FrameProgress {
    private long lastFrameAt;
    private long lastFrameCount = Long.MIN_VALUE;

    FrameProgress(long now) { lastFrameAt = now; }
    boolean started() { return lastFrameCount != Long.MIN_VALUE; }

    void frame(long frameCount, long now) {
        if (frameCount != lastFrameCount) {
            lastFrameCount = frameCount;
            lastFrameAt = now;
        }
    }

    boolean stalled(long now, boolean paused) {
        if (paused) lastFrameAt = now;
        return now - lastFrameAt >= (started() ? 30_000 : 90_000);
    }
}
