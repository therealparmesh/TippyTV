package com.parmesh.livetv;

import static org.junit.Assert.*;

import org.junit.Test;

public class PlaybackProgressTest {
    @Test
    public void startupHasABoundedGracePeriod() {
        PlaybackProgress progress = new PlaybackProgress(0);
        assertFalse(progress.stalled(35_000, false));
        assertFalse(progress.started());
        assertTrue(progress.stalled(90_000, false));
        assertFalse(progress.stalled(120_000, true));
        assertFalse(progress.stalled(120_001, false));
        progress.sample(0, 120_002);
        assertTrue(progress.started());
        assertFalse(progress.stalled(120_003, false));
    }

    @Test
    public void repeatedLastFrameDoesNotMaskStall() {
        PlaybackProgress progress = new PlaybackProgress(0);
        progress.sample(100, 0);
        for (int now = 5_000; now < 30_000; now += 5_000) {
            progress.sample(100, now);
            assertFalse(progress.stalled(now, false));
        }
        assertTrue(progress.stalled(30_000, false));
    }

    @Test
    public void progressingFramesAndPausePreventRecovery() {
        PlaybackProgress progress = new PlaybackProgress(0);
        progress.sample(100, 0);
        progress.sample(200, 25_000);
        assertFalse(progress.stalled(30_000, false));
        assertFalse(progress.stalled(90_000, true));
        assertFalse(progress.stalled(95_000, false));
        assertTrue(progress.stalled(120_000, false));
        progress.sample(0, 120_001); // A reset frame counter is valid progress.
        assertFalse(progress.stalled(120_002, false));
    }
}
