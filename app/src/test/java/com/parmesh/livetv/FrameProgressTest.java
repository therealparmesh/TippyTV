package com.parmesh.livetv;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrameProgressTest {
    @Test public void startupHasABoundedGracePeriod() {
        FrameProgress progress = new FrameProgress(0);
        assertFalse(progress.stalled(35_000, false));
        assertFalse(progress.started());
        assertTrue(progress.stalled(90_000, false));
        assertFalse(progress.stalled(120_000, true));
        assertFalse(progress.stalled(120_001, false));
        progress.frame(0, 120_002);
        assertTrue(progress.started());
        assertFalse(progress.stalled(120_003, false));
    }

    @Test public void repeatedLastFrameDoesNotMaskStall() {
        FrameProgress progress = new FrameProgress(0);
        progress.frame(100, 0);
        for (int now = 5_000; now < 30_000; now += 5_000) {
            progress.frame(100, now);
            assertFalse(progress.stalled(now, false));
        }
        assertTrue(progress.stalled(30_000, false));
    }

    @Test public void progressingFramesAndPausePreventRecovery() {
        FrameProgress progress = new FrameProgress(0);
        progress.frame(100, 0);
        progress.frame(200, 25_000);
        assertFalse(progress.stalled(30_000, false));
        assertFalse(progress.stalled(90_000, true));
        assertFalse(progress.stalled(95_000, false));
        assertTrue(progress.stalled(120_000, false));
        progress.frame(0, 120_001); // A reset frame counter is valid progress.
        assertFalse(progress.stalled(120_002, false));
    }
}
