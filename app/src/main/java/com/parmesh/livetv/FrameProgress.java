package com.parmesh.livetv;

/** Bound startup and stalled video; player state changes are not frame progress. */
final class FrameProgress {
    // Startup allows the network timeout and retries to finish; an established
    // picture gets a shorter grace period before reopening at the live edge.
    private static final long STARTUP_TIMEOUT_MS = 90_000;
    private static final long STALL_TIMEOUT_MS = 30_000;
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
        return now - lastFrameAt >= (started() ? STALL_TIMEOUT_MS : STARTUP_TIMEOUT_MS);
    }
}
