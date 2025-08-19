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
    
    // Edge detection parameters (tuned for 32ms frames)
    private static final int startAttackFrames = 3;  // ~96ms
    private static final int endReleaseFrames = 10;   // ~320ms
    
    private BasicVad.Listener listener;
    private OrtEnvironment env;
    private OrtSession session;
    private boolean isReady = false;
    
    // Audio buffering
    private float[] buffer = new float[CHUNK_SIZE];
    private int bufferPos = 0;
    
    // LSTM state arrays (will be initialized based on model requirements)
    private float[] h; // hidden state
    private float[] c; // cell state (not used by all models)
    
    // Edge detection state
    private boolean prevSpeech = false;
    private int hangCount = 0;
    private int streak = 0;
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
            
            // Log model details for debugging
            Log.d(TAG, "Silero VAD model inputs: " + session.getInputNames());
            Log.d(TAG, "Silero VAD model outputs: " + session.getOutputNames());
            
            // Get input info for debugging
            for (String inputName : session.getInputNames()) {
                NodeInfo inputInfo = session.getInputInfo().get(inputName);
                if (inputInfo != null) {
                    Log.d(TAG, "Input '" + inputName + "' info: " + inputInfo.getInfo());
                }
            }
            
            // Initialize LSTM states
            h = null; // Will be initialized on first use
            c = null;
            
            isReady = true;
            Log.d(TAG, "Silero VAD quantized model loaded successfully");
            
        } catch (OrtException | IOException e) {
            Log.e(TAG, "Failed to initialize Silero VAD: " + e.getMessage());
            isReady = false;
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
                edgeDetect(speechDetected);
                bufferPos = 0;
            }
        }

        // Emit frame-level callback with the latest speech state.
        if (listener != null) {
            // Use current prevSpeech (updated when CHUNK_SIZE was reached).
            listener.onFrameAccepted(audioSamples, prevSpeech);
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
            OnnxTensor srTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{16000}), srShape);
            
            // Create state tensor (2D: [2, 1, 128] for LSTM states) - initialize with zeros if null
            if (h == null) {
                h = new float[2 * 1 * 128]; // 2 layers, batch=1, hidden=128
            }
            long[] stateShape = {2, 1, 128};
            OnnxTensor stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), stateShape);
            
            // Provide all required inputs
            Map<String, OnnxTensor> inputs = Map.of(
                "input", inputTensor,
                "state", stateTensor, 
                "sr", srTensor
            );
            OrtSession.Result result = session.run(inputs);
            
            // Get speech probability output and updated state
            float[][] output = (float[][]) result.get(0).getValue(); // output
            float speechProb = output[0][0];
            
            // Update state if model returns it
            if (result.size() > 1) {
                float[][][] newState = (float[][][]) result.get(1).getValue(); // stateN
                // Flatten new state back to h array
                int idx = 0;
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 1; j++) {
                        for (int k = 0; k < 128; k++) {
                            h[idx++] = newState[i][j][k];
                        }
                    }
                }
            }
            
            // Clean up
            inputTensor.close();
            srTensor.close();
            stateTensor.close();
            result.close();
            
            return speechProb > threshold;
            
        } catch (OrtException e) {
            Log.e(TAG, "ONNX inference failed: " + e.getMessage());
            return false;
        }
    }

    private void edgeDetect(boolean speechLike) {
        if (speechLike) {
            hangCount = 0;
            if (!prevSpeech) {
                streak++;
                if (streak >= startAttackFrames) {
                    prevSpeech = true;
                    streak = 0;
                    if (listener != null) {
                        listener.onSpeechStart();
                    }
                }
            }
        } else {
            streak = 0;
            if (prevSpeech) {
                hangCount++;
                if (hangCount >= endReleaseFrames) {
                    prevSpeech = false;
                    hangCount = 0;
                    if (listener != null) {
                        listener.onSpeechEnd();
                    }
                }
            }
        }
    }

    @Override
    public void setThreshold(float threshold) {
        this.threshold = threshold;
    }

    @Override
    public void setHangoverFrames(int frames) {
        // Not yet tunable for Silero adapter; kept for BasicVad compatibility.
        // Will be handled by shared EdgeDetector in the refactor phases.
    }

    @Override
    public void setStartAttackFrames(int frames) {
        // Not yet tunable for Silero adapter; kept for BasicVad compatibility.
        // Will be handled by shared EdgeDetector in the refactor phases.
    }

    @Override
    public void reset() {
        // Reset LSTM states
        if (h != null) {
            for (int i = 0; i < h.length; i++) h[i] = 0f;
        }
        
        // Reset edge detection state
        prevSpeech = false;
        hangCount = 0;
        streak = 0;
        bufferPos = 0;
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
