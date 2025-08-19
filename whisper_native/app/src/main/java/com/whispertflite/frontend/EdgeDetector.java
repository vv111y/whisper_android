package com.whispertflite.frontend;

/**
 * Simple edge detector with attack/hangover hysteresis over per-frame speech-likeness.
 * Engine-independent; emits start/end edges and maintains in-speech state.
 */
class EdgeDetector {
    private int attackFrames;
    private int hangoverFrames;
    private boolean inSpeech = false;
    private int streak = 0;
    private int hang = 0;

    static class EdgeResult {
        final boolean start;
        final boolean end;
        final boolean inSpeech;
        EdgeResult(boolean start, boolean end, boolean inSpeech) {
            this.start = start; this.end = end; this.inSpeech = inSpeech;
        }
    }

    EdgeDetector(int attackFrames, int hangoverFrames) {
        this.attackFrames = Math.max(1, attackFrames);
        this.hangoverFrames = Math.max(0, hangoverFrames);
    }

    EdgeResult update(boolean speechLike) {
        boolean start = false, end = false;
        if (speechLike) {
            hang = 0;
            if (!inSpeech) {
                streak++;
                if (streak >= attackFrames) {
                    inSpeech = true;
                    streak = 0;
                    start = true;
                }
            }
        } else {
            streak = 0;
            if (inSpeech) {
                hang++;
                if (hang >= hangoverFrames) {
                    inSpeech = false;
                    hang = 0;
                    end = true;
                }
            }
        }
        return new EdgeResult(start, end, inSpeech);
    }

    void setAttackFrames(int frames) { this.attackFrames = Math.max(1, frames); }
    void setHangoverFrames(int frames) { this.hangoverFrames = Math.max(0, frames); }
    boolean isInSpeech() { return inSpeech; }

    // Reset internal state (does not change configured attack/hangover values)
    void reset() {
        inSpeech = false;
        streak = 0;
        hang = 0;
    }
}
