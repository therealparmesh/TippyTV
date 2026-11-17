package com.parmesh.livetv;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PlaybackTest {
    @Test public void retryScheduleCapsAndCanRestart() {
        long delay = 0;
        for (long expected : new long[] {3_000, 5_000, 10_000, 15_000, 30_000, 30_000}) {
            delay = Playback.nextRetryDelay(delay);
            assertEquals(expected, delay);
        }
        assertEquals(3_000, Playback.nextRetryDelay(0));
    }
}
