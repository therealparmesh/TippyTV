package com.parmesh.livetv;

/** Track rendered frames for video and the audio clock for radio. */
final class PlaybackProgress {
    // Startup allows the network timeout and retries to finish; an established
    // picture gets a shorter grace period before reopening at the live edge.
    private static final long STARTUP_TIMEOUT_MS = 90_000;
    private static final long STALL_TIMEOUT_MS = 30_000;
    private long lastProgressAt;
    private double lastPosition = Double.NaN;

    PlaybackProgress(long now) {
        lastProgressAt = now;
    }

    boolean started() {
        return Double.isFinite(lastPosition);
    }

    void sample(double position, long now) {
        if (position != lastPosition) {
            lastPosition = position;
            lastProgressAt = now;
        }
    }

    boolean stalled(long now, boolean paused) {
        if (paused) lastProgressAt = now;
        return now - lastProgressAt >= (started() ? STALL_TIMEOUT_MS : STARTUP_TIMEOUT_MS);
    }
}
