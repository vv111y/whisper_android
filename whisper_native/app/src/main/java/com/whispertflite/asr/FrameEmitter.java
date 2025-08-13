package com.whispertflite.asr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Emits fixed 20 ms PCM float frames (320 samples @16 kHz) via callback without
 * affecting existing Recorder / Whisper flow. First MVP step for wakeword pipeline.
 */
public class FrameEmitter {
    public interface Listener {
        void onFrame(float[] pcmFrame);
        default void onError(String msg) {}
        default void onStarted() {}
        default void onStopped() {}
    }

    private static final String TAG = "FrameEmitter";
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_MS = 20; // 20 ms frames
    public static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000; // 320

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private Listener listener;

    public FrameEmitter(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            worker = new Thread(this::captureLoop, "FrameEmitterThread");
            worker.start();
        } else {
            Log.d(TAG, "Already running");
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (worker != null) {
                worker.interrupt();
            }
        }
    }

    private void captureLoop() {
        if (listener != null) listener.onStarted();
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) listener.onError("RECORD_AUDIO permission not granted");
            running.set(false);
            return;
        }

        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat);
        // Ensure buffer can hold at least a few frames
        int bufferSize = Math.max(minBuffer, FRAME_SAMPLES * 4); // bytes needed > frame size

        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, channelConfig, audioFormat, bufferSize);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            if (listener != null) listener.onError("AudioRecord init failed");
            running.set(false);
            return;
        }

        short[] shortBuf = new short[FRAME_SAMPLES];
        float[] floatFrame = new float[FRAME_SAMPLES];

        recorder.startRecording();
        Log.d(TAG, "Frame emitter started");
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                int read = 0;
                while (read < FRAME_SAMPLES && running.get()) {
                    int r = recorder.read(shortBuf, read, FRAME_SAMPLES - read);
                    if (r <= 0) {
                        if (listener != null) listener.onError("Audio read error: " + r);
                        running.set(false);
                        break;
                    }
                    read += r;
                }
                if (!running.get()) break;
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    floatFrame[i] = shortBuf[i] / 32768f;
                }
                if (listener != null) listener.onFrame(floatFrame.clone()); // clone to avoid mutation
            }
        } finally {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder.release();
            running.set(false);
            if (listener != null) listener.onStopped();
            Log.d(TAG, "Frame emitter stopped");
        }
    }
}
