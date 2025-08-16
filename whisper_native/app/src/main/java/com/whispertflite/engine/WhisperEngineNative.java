package com.whispertflite.engine;

import android.content.Context;
import android.util.Log;

public class WhisperEngineNative implements WhisperEngine {
    private final String TAG = "WhisperEngineNative";
    private final long nativePtr; // Native pointer to the TFLiteEngine instance

    private final Context mContext;
    private boolean mIsInitialized = false;

    public WhisperEngineNative(Context context) {
        mContext = context;
        nativePtr = createTFLiteEngine();
    }

    @Override
    public boolean isInitialized() {
        return mIsInitialized;
    }

    @Override
    public boolean initialize(String modelPath, String vocabPath, boolean multilingual) {
        int ret = loadModel(modelPath, multilingual);
        if (ret == 0) {
            Log.d(TAG, "Model loaded: " + modelPath);
            mIsInitialized = true;
            return true;
        } else {
            Log.e(TAG, "Model load failed (code=" + ret + "): " + getLastError(nativePtr));
            mIsInitialized = false;
            return false;
        }
    }

    @Override
    public void deinitialize() {
        freeModel();
    }

    @Override
    public String transcribeBuffer(float[] samples) {
    if (!mIsInitialized) return "[error] model not initialized";
    return transcribeBuffer(nativePtr, samples);
    }

    @Override
    public String transcribeFile(String waveFile) {
    if (!mIsInitialized) return "[error] model not initialized";
    return transcribeFile(nativePtr, waveFile);
    }

    @Override
    public String lastError() {
        try { return getLastError(nativePtr); } catch (Throwable t) { return ""; }
    }

    private int loadModel(String modelPath, boolean isMultilingual) {
        return loadModel(nativePtr, modelPath, isMultilingual);
    }

    private void freeModel() {
        freeModel(nativePtr);
    }

    static {
        System.loadLibrary("audioEngine");
    }

    // Native methods
    private native long createTFLiteEngine();
    private native int loadModel(long nativePtr, String modelPath, boolean isMultilingual);
    private native int validateModel(long nativePtr, String modelPath, boolean isMultilingual);
    private native String getLastError(long nativePtr);
    private native void freeModel(long nativePtr);
    private native String transcribeBuffer(long nativePtr, float[] samples);
    private native String transcribeFile(long nativePtr, String waveFile);

    // Public helper to allow UI to validate a model without loading it persistently
    public int validateModel(String path, boolean multilingual) {
        // Safe validator that only verifies the flatbuffer; does not build an interpreter
        return validateModel(nativePtr, path, multilingual);
    }
}
