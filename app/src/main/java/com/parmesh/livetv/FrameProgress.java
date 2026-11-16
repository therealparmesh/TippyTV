package com.parmesh.livetv;

/** Detects stalled video only after the first frame; player state changes are not progress. */
final class FrameProgress {
    private volatile long lastFrameAt = -1;
    private long lastPresentationTime = Long.MIN_VALUE;

    void frame(long presentationTimeUs, long now) {
        if (presentationTimeUs != lastPresentationTime) {
            lastPresentationTime = presentationTimeUs;
            lastFrameAt = now;
        }
    }

    boolean stalled(long now, boolean paused) {
        if (lastFrameAt < 0) return false;
        if (paused) lastFrameAt = now;
        return now - lastFrameAt >= 30_000;
    }
}
