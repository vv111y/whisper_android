package com.whispertflite.frontend;

import org.junit.Test;
import static org.junit.Assert.*;

public class EdgeDetectorTest {
    @Test
    public void attack_then_hangover_edges() {
        EdgeDetector ed = new EdgeDetector(3, 2);
        // Two speech-like frames: not enough to start
        assertFalse(ed.update(true).start);
        assertFalse(ed.update(true).start);
        // Third speech-like triggers start
        assertTrue(ed.update(true).start);
        assertTrue(ed.isInSpeech());
        // One non-speech: within hangover, still in speech
        assertFalse(ed.update(false).end);
        assertTrue(ed.isInSpeech());
        // Second non-speech: exceeds hangover => end
        assertTrue(ed.update(false).end);
        assertFalse(ed.isInSpeech());
    }

    @Test
    public void reset_clears_state_but_keeps_config() {
        EdgeDetector ed = new EdgeDetector(2, 1);
        assertFalse(ed.update(true).start);
        assertTrue(ed.update(true).start);
        assertTrue(ed.isInSpeech());
        ed.reset();
        assertFalse(ed.isInSpeech());
        // Config unchanged: two speech-like frames required
        assertFalse(ed.update(true).start);
        assertTrue(ed.update(true).start);
    }
}
