package com.whispertflite.asr;

import android.content.Context;
import android.util.Log;

import com.whispertflite.engine.WhisperEngine;
import com.whispertflite.engine.WhisperEngineJava;
import com.whispertflite.engine.WhisperEngineNative;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(String result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";
    public static final String MSG_FILE_NOT_FOUND = "Input file doesn't exist..!";

    public static final Action ACTION_TRANSCRIBE = Action.TRANSCRIBE;
    public static final Action ACTION_TRANSLATE = Action.TRANSLATE;

    private enum Action {
        TRANSLATE, TRANSCRIBE
    }

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);
    private final Queue<float[]> audioBufferQueue = new LinkedList<>();

    private final WhisperEngine mWhisperEngine;
    private Action mAction;
    private String mWavFilePath;
    private WhisperListener mUpdateListener;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;

    public Whisper(Context context) {
//        this.mWhisperEngine = new WhisperEngineJava(context);
        this.mWhisperEngine = new WhisperEngineNative(context);

        // Start thread for file transcription for file transcription
        Thread threadTranscbFile = new Thread(this::transcribeFileLoop);
        threadTranscbFile.start();

        // Start thread for buffer transcription for live mic feed transcription
        Thread threadTranscbBuffer = new Thread(this::transcribeBufferLoop);
        threadTranscbBuffer.start();
    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public void loadModel(File modelPath, File vocabPath, boolean isMultilingual) {
        loadModel(modelPath.getAbsolutePath(), vocabPath.getAbsolutePath(), isMultilingual);
    }

    public void loadModel(String modelPath, String vocabPath, boolean isMultilingual) {
        try {
            mWhisperEngine.initialize(modelPath, vocabPath, isMultilingual);
        } catch (IOException e) {
            Log.e(TAG, "Error initializing model...", e);
            sendUpdate("Model initialization failed");
        }
    }

    public void unloadModel() {
        mWhisperEngine.deinitialize();
    }

    public void setAction(Action action) {
        this.mAction = action;
    }

    public void setFilePath(String wavFile) {
        this.mWavFilePath = wavFile;
    }

    // Add getter for file path
    public String getFilePath() {
        return mWavFilePath;
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void transcribeFileLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                transcribeFile();
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskLock.unlock();
            }
        }
    }

    private void transcribeFile() {
        try {
            if (mWhisperEngine.isInitialized() && mWavFilePath != null) {
                File waveFile = new File(mWavFilePath);
                if (waveFile.exists()) {
                    if (waveFile.length() == 0) {
                        Log.e(TAG, "Audio file exists but is empty: " + mWavFilePath);
                        sendUpdate("Audio file is empty");
                        sendResult(""); // Send empty result to trigger proper handling
                        mInProgress.set(false);
                        return;
                    }
                    
                    long startTime = System.currentTimeMillis();
                    sendUpdate(MSG_PROCESSING);

                    String result = null;
                    synchronized (mWhisperEngine) {
                        if (mAction == Action.TRANSCRIBE) {
                            try {
                                result = mWhisperEngine.transcribeFile(mWavFilePath);
                                Log.d(TAG, "Transcription complete. Result length: " + 
                                        (result != null ? result.length() : "null"));
                            } catch (Exception e) {
                                Log.e(TAG, "Error during transcription engine call", e);
                                result = "Error transcribing: " + e.getMessage();
                            }
                        } else {
                            Log.d(TAG, "TRANSLATE feature is not implemented");
                        }
                    }
                    
                    // Handle potentially null result
                    if (result == null) {
                        result = ""; // Convert null to empty string to avoid NullPointerException
                    }
                    
                    sendResult(result);

                    long timeTaken = System.currentTimeMillis() - startTime;
                    Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                    sendUpdate(MSG_PROCESSING_DONE);
                } else {
                    Log.e(TAG, "Audio file does not exist: " + mWavFilePath);
                    sendUpdate(MSG_FILE_NOT_FOUND);
                }
            } else {
                sendUpdate("Engine not initialized or file path not set");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendResult(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(message);
        }
    }

    /////////////////////// Live MIC feed transcription calls /////////////////////////////////
    private void transcribeBufferLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            float[] samples = readBuffer();
            if (samples != null) {
                synchronized (mWhisperEngine) {
                    String result = mWhisperEngine.transcribeBuffer(samples);
                    sendResult(result);
                }
            }
        }
    }

    public void writeBuffer(float[] samples) {
        synchronized (audioBufferQueue) {
            audioBufferQueue.add(samples);
            audioBufferQueue.notify();
        }
    }

    private float[] readBuffer() {
        synchronized (audioBufferQueue) {
            while (audioBufferQueue.isEmpty()) {
                try {
                    audioBufferQueue.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return audioBufferQueue.poll();
        }
    }
}
