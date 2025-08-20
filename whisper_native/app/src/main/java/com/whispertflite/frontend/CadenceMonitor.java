package com.whispertflite.frontend;

/**
 * Lightweight callback cadence monitor. Records inter-callback intervals (ms)
 * and provides simple stats for debugging: count, avg, min, max, p50, p95.
 */
public class CadenceMonitor {
    private final int capacity;
    private final float[] ring;
    private int writeIdx = 0;
    private long count = 0;
    private double sum = 0.0;
    private float min = Float.POSITIVE_INFINITY;
    private float max = 0f;
    private long lastTs = -1L;

    public CadenceMonitor(int capacity) {
        this.capacity = Math.max(8, capacity);
        this.ring = new float[this.capacity];
    }

    public synchronized void reset() {
        writeIdx = 0;
        count = 0;
        sum = 0.0;
        min = Float.POSITIVE_INFINITY;
        max = 0f;
        lastTs = -1L;
        for (int i = 0; i < ring.length; i++) ring[i] = 0f;
    }

    public synchronized void onCallback(long nowUptimeMs) {
        if (lastTs > 0) {
            long dt = nowUptimeMs - lastTs;
            if (dt >= 0 && dt < 10_000) { // guard outliers
                float ms = (float) dt;
                ring[writeIdx] = ms;
                writeIdx = (writeIdx + 1) % capacity;
                count++;
                sum += ms;
                if (ms < min) min = ms;
                if (ms > max) max = ms;
            }
        }
        lastTs = nowUptimeMs;
    }

    public synchronized String summary() {
        int n = (int) Math.min(count, capacity);
        if (n <= 0) return "cadence: n=0";
        float avg = (float) (sum / Math.max(1, count));
        float p50 = percentile(50);
        float p95 = percentile(95);
        return "cadence: n=" + count + ", avg=" + Math.round(avg) + " ms, p50=" + Math.round(p50) +
               " ms, p95=" + Math.round(p95) + " ms, min=" + Math.round(min) + " ms, max=" + Math.round(max) + " ms";
    }

    private float percentile(int pct) {
        int n = (int) Math.min(count, capacity);
        if (n <= 0) return 0f;
        // copy to tmp array
        float[] tmp = new float[n];
        // ring buffer: last n entries ending at writeIdx-1
        int idx = writeIdx - n;
        for (int i = 0; i < n; i++) {
            int r = idx + i;
            if (r < 0) r += capacity;
            tmp[i] = ring[r % capacity];
        }
        java.util.Arrays.sort(tmp);
        int pos = Math.min(n - 1, Math.max(0, (pct * (n - 1)) / 100));
        return tmp[pos];
    }
}
