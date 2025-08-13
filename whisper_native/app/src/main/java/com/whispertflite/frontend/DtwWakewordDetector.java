package com.whispertflite.frontend;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Template-based DTW wakeword detector.
 * Expects asset file with one template per line: comma-separated MFCC values (flattened).
 * Each template must have same frame count * coeffs length.
 *
 * Log guide:
 * - Call setDebugLogScores(true) to log periodic best DTW scores for tuning.
 * - Speak the wakeword ~10–20 times (positives) and observe score range.
 * - Stay silent or speak other words (negatives) and observe scores.
 * - Choose threshold between max positive and min negative with margin.
 */
public class DtwWakewordDetector implements WakewordDetector {
    private static final String TAG = "DtwWake";

    private final Context context;
    private final String assetFile;
    private final int mfccCoeffs; // e.g., 13
    private final int frameLen;   // samples per analysis frame (e.g., 400 for 25 ms @16k)
    private final int hop;        // advance (e.g., 160 for 10 ms)
    private final int windowFrames; // number of frames representing ~ keyword length (e.g., 30 = 300 ms)
    private final double triggerThreshold; // DTW distance threshold
    private final long debounceMillis;
    private final Listener listener;

    private long lastTriggerTs = 0;

    private final List<float[]> templates = new ArrayList<>();
    private final ArrayDeque<float[]> audioBuffer = new ArrayDeque<>(); // holds raw PCM frames (20 ms)

    // Optional periodic score logging for threshold tuning
    private boolean debugLogScores = false;
    private long lastLogTs = 0;

    // scratch for MFCC window accumulation
    private final List<float[]> mfccWindow = new ArrayList<>();

    public DtwWakewordDetector(Context ctx,
                               String assetFile,
                               int mfccCoeffs,
                               int frameLen,
                               int hop,
                               int windowFrames,
                               double triggerThreshold,
                               long debounceMillis,
                               Listener listener) throws IOException {
        this.context = ctx.getApplicationContext();
        this.assetFile = assetFile;
        this.mfccCoeffs = mfccCoeffs;
        this.frameLen = frameLen;
        this.hop = hop;
        this.windowFrames = windowFrames;
        this.triggerThreshold = triggerThreshold;
        this.debounceMillis = debounceMillis;
        this.listener = listener;
        loadTemplates();
    }

    /** Enable/disable periodic best-score logs for threshold tuning. */
    public void setDebugLogScores(boolean enable) { this.debugLogScores = enable; }

    private void loadTemplates() throws IOException {
        try (InputStream is = context.getAssets().open(assetFile);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split(",");
                float[] tpl = new float[parts.length];
                for (int i = 0; i < parts.length; i++) tpl[i] = Float.parseFloat(parts[i]);
                templates.add(tpl);
            }
        }
        Log.d(TAG, "Loaded templates: " + templates.size());
    }

    @Override
    public void acceptFrame(float[] frame, boolean speech) {
        // only process speech frames
        if (!speech) return;
        audioBuffer.add(frame.clone());
        // keep only last ~ 1.5 s (arbitrary safety)
        while (audioBuffer.size() > 75) audioBuffer.pollFirst();

        // Convert last N ms (~windowFrames*hop) to MFCC sequence when enough samples
        int samplesNeeded = frameLen + (windowFrames - 1) * hop;
        int totalSamples = audioBuffer.size() * frame.length;
        if (totalSamples < samplesNeeded) return;

        float[] window = latestSamples(samplesNeeded);
        float[][] mfccSeq = mfcc(window, frameLen, hop, mfccCoeffs);
        if (mfccSeq.length != windowFrames) return; // enforce fixed size

        double best = Double.MAX_VALUE;
        for (float[] tpl : templates) {
            int tplFrames = tpl.length / mfccCoeffs;
            if (tplFrames != windowFrames) continue; // mismatch
            double d = dtw(mfccSeq, tpl, mfccCoeffs);
            if (d < best) best = d;
        }

        long now = System.currentTimeMillis();
        if (debugLogScores && now - lastLogTs > 500) { // log at ~2 Hz
            lastLogTs = now;
            Log.d(TAG, "best_dtw=" + best + ", thr=" + triggerThreshold + ", wf=" + windowFrames);
        }

        if (best < triggerThreshold && (now - lastTriggerTs) > debounceMillis) {
            lastTriggerTs = now;
            if (listener != null) listener.onWakeTriggered(best);
        }
    }

    @Override
    public void reset() {
        audioBuffer.clear();
        mfccWindow.clear();
    }

    private float[] latestSamples(int needed) {
        float[] out = new float[needed];
        int writePos = needed;
        Object[] array = audioBuffer.toArray();
        for (int idx = array.length - 1; idx >= 0 && writePos > 0; idx--) {
            float[] fr = (float[]) array[idx];
            int copy = Math.min(fr.length, writePos);
            writePos -= copy;
            System.arraycopy(fr, 0, out, writePos, copy);
        }
        return out;
    }

    // Very small MFCC (no liftering, no delta) for prototype
    private float[][] mfcc(float[] samples, int frameLen, int hop, int coeffs) {
        int frames = 1 + (samples.length - frameLen) / hop;
        if (frames <= 0) return new float[0][0];
        float[][] out = new float[frames][coeffs];
        // pre-emphasis
        float pre = 0.97f;
        float[] preEmph = new float[samples.length];
        preEmph[0] = samples[0];
        for (int i = 1; i < samples.length; i++) preEmph[i] = samples[i] - pre * samples[i - 1];
        // Hamming
        float[] win = new float[frameLen];
        for (int i = 0; i < frameLen; i++) win[i] = (float)(0.54 - 0.46 * Math.cos(2 * Math.PI * i / (frameLen - 1)));
        // FFT size power of two >= frameLen
        int nFft = 1; while (nFft < frameLen) nFft <<= 1;
        float[] re = new float[nFft];
        float[] im = new float[nFft];
        int melBands = 26;
        double fMin = 0; double fMax = 8000; // Nyquist 16k/2
        double[] melPoints = new double[melBands + 2];
        for (int i = 0; i < melPoints.length; i++) melPoints[i] = hzToMel(fMin) + (hzToMel(fMax) - hzToMel(fMin)) * i / (melBands + 1);
        double[] hzPoints = new double[melPoints.length];
        for (int i = 0; i < hzPoints.length; i++) hzPoints[i] = melToHz(melPoints[i]);
        int[] bin = new int[hzPoints.length];
        for (int i = 0; i < hzPoints.length; i++) bin[i] = (int)Math.floor((nFft + 1) * hzPoints[i] / (2 * 8000));
        double[][] filter = new double[melBands][nFft/2+1];
        for (int m = 1; m <= melBands; m++) {
            for (int k = bin[m - 1]; k < bin[m]; k++) filter[m-1][k] = (k - bin[m - 1]) / (double)(bin[m] - bin[m - 1]);
            for (int k = bin[m]; k < bin[m + 1]; k++) filter[m-1][k] = (bin[m + 1] - k) / (double)(bin[m + 1] - bin[m]);
        }
        for (int f = 0; f < frames; f++) {
            int off = f * hop;
            for (int i = 0; i < nFft; i++) { re[i] = 0; im[i] = 0; }
            for (int i = 0; i < frameLen; i++) {
                float wv = (off + i < preEmph.length) ? preEmph[off + i] * win[i] : 0f;
                re[i] = wv;
            }
            fft(re, im, nFft);
            double[] pow = new double[nFft/2+1];
            for (int k = 0; k < pow.length; k++) pow[k] = re[k]*re[k] + im[k]*im[k];
            double[] melE = new double[melBands];
            for (int m = 0; m < melBands; m++) {
                double sum = 0;
                for (int k = 0; k < pow.length; k++) sum += pow[k] * filter[m][k];
                melE[m] = Math.log10(Math.max(sum, 1e-10));
            }
            // DCT-II
            for (int c = 0; c < coeffs; c++) {
                double s = 0;
                for (int m = 0; m < melBands; m++) s += melE[m] * Math.cos(Math.PI * c * (2*m+1)/(2.0*melBands));
                out[f][c] = (float)s;
            }
        }
        return out;
    }

    private double dtw(float[][] seq, float[] templateFlat, int coeffs) {
        int n = seq.length;
        int m = templateFlat.length / coeffs;
        // reshape template
        float[][] tpl = new float[m][coeffs];
        for (int i = 0; i < m; i++) System.arraycopy(templateFlat, i*coeffs, tpl[i], 0, coeffs);
        double[][] dp = new double[n+1][m+1];
        double INF = 1e18;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= m; j++) dp[i][j] = INF;
        }
        dp[0][0] = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                double dist = l2(seq[i-1], tpl[j-1]);
                double best = Math.min(dp[i-1][j], Math.min(dp[i][j-1], dp[i-1][j-1]));
                dp[i][j] = dist + best;
            }
        }
        return dp[n][m] / (n + m); // normalized
    }

    private double l2(float[] a, float[] b) {
        double s = 0; for (int i = 0; i < a.length; i++) { double d = a[i]-b[i]; s += d*d; } return Math.sqrt(s / a.length);
    }

    private double hzToMel(double hz) { return 2595.0 * Math.log10(1 + hz/700.0); }
    private double melToHz(double mel) { return 700.0 * (Math.pow(10, mel/2595.0) - 1); }

    private void fft(float[] re, float[] im, int n) {
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (i < j) { float tr = re[i]; float ti = im[i]; re[i]=re[j]; im[i]=im[j]; re[j]=tr; im[j]=ti; }
            int m = n >> 1;
            while (m >=1 && j >= m) { j -= m; m >>= 1; }
            j += m;
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2*Math.PI/len;
            float wlen_r = (float)Math.cos(ang);
            float wlen_i = (float)Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float wr = 1, wi = 0;
                for (int k = 0; k < len/2; k++) {
                    int u = i + k;
                    int v = i + k + len/2;
                    float vr = re[v]*wr - im[v]*wi;
                    float vi = re[v]*wi + im[v]*wr;
                    re[v] = re[u] - vr; im[v] = im[u] - vi;
                    re[u] += vr; im[u] += vi;
                    float nwr = wr*wlen_r - wi*wlen_i;
                    wi = wr*wlen_i + wi*wlen_r;
                    wr = nwr;
                }
            }
        }
    }
}
