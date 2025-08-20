package com.whispertflite.frontend;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Map;

/**
 * Silero VAD implementation using ONNX Runtime.
 * Uses silero_vad_q4.onnx quantized model from assets.
 */
public class VadSilero implements BasicVad {
    private static final String TAG = "VadSilero";
    private static final String MODEL_NAME = "silero_vad_q4.onnx";
    
    private static final int CHUNK_SIZE = 512; // Silero VAD expects 512 samples at 16kHz
    private static final int SAMPLE_RATE = 16000;
    
    // Edge detection parameters (initial defaults)
    private static final int DEFAULT_ATTACK_FRAMES = 3;   // ~60-96ms
    private static final int DEFAULT_HANGOVER_FRAMES = 10; // ~200-320ms
    
    private BasicVad.Listener listener;
    private OrtEnvironment env;
    private OrtSession session;
    private boolean isReady = false;
    private boolean debugLogs = false;
    
    // Audio buffering
    private float[] buffer = new float[CHUNK_SIZE];
    private int bufferPos = 0;
    
    // LSTM state arrays (will be initialized based on model requirements)
    private float[] h; // hidden state
    private float[] c; // cell state (not used by all models)
    // Model IO names (some Silero variants use h/c, others use a combined state)
    private String audioInputName = "input";
    private String sampleRateInputName = "sr";
    private String hInputName = null; // e.g., "h"
    private String cInputName = null; // e.g., "c"
    private String stateInputName = null; // e.g., "state"
    private String probOutputName = null; // e.g., "output" or "out"
    private String hOutName = null; // e.g., "hn"
    private String cOutName = null; // e.g., "cn"
    
    // Edge detection state via shared EdgeDetector
    private final EdgeDetector edgeDetector = new EdgeDetector(DEFAULT_ATTACK_FRAMES, DEFAULT_HANGOVER_FRAMES);
    private float threshold = 0.5f; // Default threshold, will be adjusted by MainActivity

    public VadSilero(Context context, BasicVad.Listener listener) {
        this.listener = listener;
        initModel(context);
    }

    private void initModel(Context context) {
        try {
            // Copy model from assets to internal storage if needed
            File modelFile = new File(context.getFilesDir(), MODEL_NAME);
            if (!modelFile.exists()) {
                copyAssetToFile(context, MODEL_NAME, modelFile);
            }
            
            // Initialize ONNX Runtime
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            sessionOptions.setIntraOpNumThreads(1);
            sessionOptions.setInterOpNumThreads(1);
            
            session = env.createSession(modelFile.getAbsolutePath(), sessionOptions);

            // Discover IO names (be tolerant of variants)
            try {
                java.util.Set<String> in = session.getInputNames();
                java.util.Set<String> out = session.getOutputNames();
                debugLogs = detectDebug();
                if (debugLogs) {
                    Log.d(TAG, "Silero VAD model inputs: " + in);
                    Log.d(TAG, "Silero VAD model outputs: " + out);
                }
                // Audio input
                for (String n : in) {
                    String ln = n.toLowerCase();
                    if (ln.contains("wave") || ln.equals("input") || ln.contains("audio")) { audioInputName = n; }
                    if (ln.equals("sr") || ln.contains("sample")) { sampleRateInputName = n; }
                    if (ln.equals("h") || ln.endsWith("_h")) { hInputName = n; }
                    if (ln.equals("c") || ln.endsWith("_c")) { cInputName = n; }
                    if (ln.contains("state")) { stateInputName = n; }
                }
                // Outputs
                for (String n : out) {
                    String ln = n.toLowerCase();
                    if (ln.contains("out") || ln.contains("prob") || ln.equals("output")) { probOutputName = n; }
                    if (ln.equals("hn") || ln.endsWith("_hn") || ln.endsWith("_h")) { hOutName = n; }
                    if (ln.equals("cn") || ln.endsWith("_cn") || ln.endsWith("_c")) { cOutName = n; }
                }
            } catch (Throwable t) {
                // Non-fatal; keep defaults
            }
            
            // Initialize LSTM states
            h = null; // Will be initialized on first use
            c = null;
            
            isReady = true;
            if (debugLogs) Log.d(TAG, "Silero VAD quantized model loaded successfully");
            
        } catch (OrtException | IOException e) {
            Log.e(TAG, "Failed to initialize Silero VAD: " + e.getMessage());
            isReady = false;
        }
    }

    private static boolean detectDebug() {
        try {
            Class<?> c = Class.forName("com.whispertflite.BuildConfig");
            java.lang.reflect.Field f = c.getField("DEBUG");
            return f.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    private void copyAssetToFile(Context context, String assetName, File outputFile) throws IOException {
        AssetManager assetManager = context.getAssets();
        InputStream inputStream = assetManager.open(assetName);
        FileOutputStream outputStream = new FileOutputStream(outputFile);
        
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, length);
        }
        
        outputStream.close();
        inputStream.close();
    }

    @Override
    public void accept(float[] audioSamples) {
        if (!isReady || audioSamples == null) return;

        // Process audio in chunks of 512 for Silero, but always emit one
        // onFrameAccepted per incoming 320-sample frame so the pipeline
        // stays in sync with FrameEmitter's cadence.
        for (float sample : audioSamples) {
            buffer[bufferPos++] = sample;

            if (bufferPos >= CHUNK_SIZE) {
                boolean speechDetected = processSileroChunk(buffer);
                EdgeDetector.EdgeResult er = edgeDetector.update(speechDetected);
                if (listener != null) {
                    if (er.start) listener.onSpeechStart();
                    if (er.end) listener.onSpeechEnd();
                }
                bufferPos = 0;
            }
        }

        // Emit frame-level callback with the latest speech state.
        if (listener != null) {
            // Use current inSpeech from edge detector (updated when CHUNK_SIZE was reached).
            listener.onFrameAccepted(audioSamples, edgeDetector.isInSpeech());
        }
    }
    
    private boolean processSileroChunk(float[] samples) {
        if (!isReady) return false;
        
        try {
            // Create input tensor for audio samples
            long[] inputShape = {1, samples.length};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), inputShape);

            // Create sample rate tensor (16000 Hz as int64)
            long[] srShape = {1};
            OnnxTensor srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{SAMPLE_RATE}), srShape);

            // Prepare recurrent state(s)
            if (h == null) { h = new float[2 * 1 * 128]; }
            long[] stateShape = {2, 1, 128};
            OnnxTensor hTensor = null;
            OnnxTensor cTensor = null;
            OnnxTensor stateTensor = null;
            java.util.HashMap<String, OnnxTensor> inputs = new java.util.HashMap<>();
            inputs.put(audioInputName, inputTensor);
            inputs.put(sampleRateInputName, srTensor);
            if (stateInputName != null) {
                stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), stateShape);
                inputs.put(stateInputName, stateTensor);
            } else {
                // Separate h/c
                hTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), stateShape);
                inputs.put(hInputName != null ? hInputName : "h", hTensor);
                if (c == null) c = new float[h.length];
                cTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), stateShape);
                inputs.put(cInputName != null ? cInputName : "c", cTensor);
            }

            OrtSession.Result result = session.run(inputs);

            // Get speech probability output and updated state
            float speechProb = 0f;
            try {
                if (probOutputName != null) {
                    float[][] out = (float[][]) result.get(probOutputName).get().getValue();
                    speechProb = out[0][0];
                } else {
                    float[][] out = (float[][]) result.get(0).getValue();
                    speechProb = out[0][0];
                }
            } catch (Throwable t) {
                // Fallback indexing
                float[][] out = (float[][]) result.get(0).getValue();
                speechProb = out[0][0];
            }

            // Update recurrent state(s) if returned
            try {
                if (stateInputName != null) {
                    // Combined state
                    Object st = (probOutputName != null && result.size() > 1) ? result.get(1).getValue() : null;
                    if (st instanceof float[][][]) {
                        float[][][] newState = (float[][][]) st;
                        int idx = 0;
                        for (int i = 0; i < 2; i++) for (int j = 0; j < 1; j++) for (int k = 0; k < 128; k++) h[idx++] = newState[i][j][k];
                    }
                } else {
                    // Separate h/c
                    if (hOutName != null) {
                        float[][][] hn = (float[][][]) result.get(hOutName).get().getValue();
                        int idx = 0; for (int i = 0; i < 2; i++) for (int j = 0; j < 1; j++) for (int k = 0; k < 128; k++) h[idx++] = hn[i][j][k];
                    } else if (result.size() > 1) {
                        float[][][] hn = (float[][][]) result.get(1).getValue();
                        int idx = 0; for (int i = 0; i < 2; i++) for (int j = 0; j < 1; j++) for (int k = 0; k < 128; k++) h[idx++] = hn[i][j][k];
                    }
                    if (cOutName != null) {
                        float[][][] cn = (float[][][]) result.get(cOutName).get().getValue();
                        int idx = 0; for (int i = 0; i < 2; i++) for (int j = 0; j < 1; j++) for (int k = 0; k < 128; k++) c[idx++] = cn[i][j][k];
                    } else if (result.size() > 2) {
                        float[][][] cn = (float[][][]) result.get(2).getValue();
                        int idx = 0; for (int i = 0; i < 2; i++) for (int j = 0; j < 1; j++) for (int k = 0; k < 128; k++) c[idx++] = cn[i][j][k];
                    }
                }
            } catch (Throwable ignore) { }

            // Clean up
            try { inputTensor.close(); } catch (Throwable ignore) {}
            try { srTensor.close(); } catch (Throwable ignore) {}
            try { if (stateTensor != null) stateTensor.close(); } catch (Throwable ignore) {}
            try { if (hTensor != null) hTensor.close(); } catch (Throwable ignore) {}
            try { if (cTensor != null) cTensor.close(); } catch (Throwable ignore) {}
            try { result.close(); } catch (Throwable ignore) {}

            return speechProb > threshold;

        } catch (OrtException e) {
            Log.e(TAG, "ONNX inference failed: " + e.getMessage());
            return false;
        }
    }

    // Edge detection handled by EdgeDetector

    @Override
    public void setThreshold(float threshold) {
        this.threshold = threshold;
    }

    @Override
    public void setHangoverFrames(int frames) {
    try { edgeDetector.setHangoverFrames(frames); } catch (Throwable ignore) {}
    }

    @Override
    public void setStartAttackFrames(int frames) {
    try { edgeDetector.setAttackFrames(frames); } catch (Throwable ignore) {}
    }

    @Override
    public void reset() {
        // Reset LSTM states
        if (h != null) {
            for (int i = 0; i < h.length; i++) h[i] = 0f;
        }
        if (c != null) {
            for (int i = 0; i < c.length; i++) c[i] = 0f;
        }
        
    // Reset buffering and edge state
        bufferPos = 0;
        try { edgeDetector.reset(); } catch (Throwable ignore) {}
    }

    public void release() {
        try {
            if (session != null) {
                session.close();
                session = null;
            }
            if (env != null) {
                env.close();
                env = null;
            }
        } catch (OrtException e) {
            Log.e(TAG, "Error releasing ONNX resources: " + e.getMessage());
        }
        isReady = false;
    }
}
