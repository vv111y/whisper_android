package com.whispertflite;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.whispertflite.utils.WaveUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // whisper-small.tflite works well for multi-lingual
    private static final String MULTI_LINGUAL_MODEL = "whisper-small.tflite";
    // English only model ends with extension ".en.tflite"
    private static final String ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite";
    private static final String ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin";
    private static final String MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin";
    private static final String[] EXTENSIONS_TO_COPY = {"bin"};

    private TextView tvStatus;
    private TextView tvResult;
    private FloatingActionButton fabCopy;
    private ImageButton btnRecord;
    private CheckBox append;

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;

    private File sdcardDataFolder = null;
    private File selectedWaveFile = null;
    private File selectedTfliteFile = null;

    private long startTime = 0;
    private final boolean loopTesting = false;
    private final SharedResource transcriptionSync = new SharedResource();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        append = findViewById(R.id.mode_append);
        // Call the method to copy specific file types from assets to data folder
        sdcardDataFolder = this.getExternalFilesDir(null);
        copyAssetsToSdcard(this, sdcardDataFolder, EXTENSIONS_TO_COPY);

        ArrayList<File> tfliteFiles = getFilesWithExtension(sdcardDataFolder, ".tflite");

        // Initialize default model to use
        selectedTfliteFile = new File(sdcardDataFolder, MULTI_LINGUAL_MODEL);

        // Sort the list to ensure MULTI_LINGUAL_MODEL is at the top (Default)
        if (tfliteFiles.contains(selectedTfliteFile)) {
            tfliteFiles.remove(selectedTfliteFile);
            tfliteFiles.add(0, selectedTfliteFile);
        }

        Spinner spinnerTflite = findViewById(R.id.spnrTfliteFiles);
        spinnerTflite.setAdapter(getFileArrayAdapter(tfliteFiles));
        spinnerTflite.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                deinitModel();
                selectedTfliteFile = (File) parent.getItemAtPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Handle case when nothing is selected, if needed
            }
        });

        selectedWaveFile = new File(sdcardDataFolder+"/"+WaveUtil.RECORDING_FILE);

        // Implementation of record button functionality
        btnRecord = findViewById(R.id.btnRecord);

        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Pressed
                Log.d(TAG, "Start recording...");
                startRecording();
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                // Released
                if (mRecorder != null && mRecorder.isInProgress()) {
                    Log.d(TAG, "Recording is in progress... stopping...");
                    stopRecording();

                    if (mWhisper == null)
                        initModel(selectedTfliteFile);

                    if (!mWhisper.isInProgress()) {
                        Log.d(TAG, "Start transcription...");
                        startTranscription(selectedWaveFile.getAbsolutePath());

                        // only for loop testing
                        if (loopTesting) {
                            new Thread(() -> {
                                for (int i = 0; i < 1000; i++) {
                                    if (!mWhisper.isInProgress())
                                        startTranscription(selectedWaveFile.getAbsolutePath());
                                    else
                                        Log.d(TAG, "Whisper is already in progress...!");

                                    boolean wasNotified = transcriptionSync.waitForSignalWithTimeout(15000);
                                    Log.d(TAG, wasNotified ? "Transcription Notified...!" : "Transcription Timeout...!");
                                }
                            }).start();
                        }
                    } else {
                        Log.d(TAG, "Whisper is already in progress...!");
                        stopTranscription();
                    }

                }
            }
            return true;
        });

        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        fabCopy = findViewById(R.id.fabCopy);
        fabCopy.setOnClickListener(v -> {
            // Get the text from tvResult
            String textToCopy = tvResult.getText().toString();

            // Copy the text to the clipboard
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Copied Text", textToCopy);
            clipboard.setPrimaryClip(clip);
        });

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                handler.post(() -> tvStatus.setText(message));

                if (message.equals(Recorder.MSG_RECORDING)) {
                    if (!append.isChecked()) handler.post(() -> tvResult.setText(""));
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                }
            }

            @Override
            public void onDataReceived(float[] samples) {
//                mWhisper.writeBuffer(samples);
            }
        });


        // Assume this Activity is the current activity, check record permission
        checkRecordPermission();

        // for debugging
//        testParallelProcessing();
    }

    // Model initialization
    private void initModel(File modelFile) {
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);

                if (message.equals(Whisper.MSG_PROCESSING)) {
                    handler.post(() -> tvStatus.setText(message));
                    //handler.post(() -> tvResult.setText(""));
                    startTime = System.currentTimeMillis();
                } if (message.equals(Whisper.MSG_PROCESSING_DONE)) {
//                    handler.post(() -> tvStatus.setText(message));
                    // for testing
                    if (loopTesting)
                        transcriptionSync.sendSignal();
                } else if (message.equals(Whisper.MSG_FILE_NOT_FOUND)) {
                    handler.post(() -> tvStatus.setText(message));
                    Log.d(TAG, "File not found error...!");
                }
            }

            @Override
            public void onResultReceived(String result) {
                long timeTaken = System.currentTimeMillis() - startTime;
                handler.post(() -> tvStatus.setText("Processing done in " + timeTaken + "ms"));

                Log.d(TAG, "Result: " + result);
                handler.post(() -> tvResult.append(result));
            }
        });
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    private @NonNull ArrayAdapter<File> getFileArrayAdapter(ArrayList<File> tfliteFiles) {
        ArrayAdapter<File> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, tfliteFiles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL))
                    textView.setText("Multi-lingual, slow");
                else
                    textView.setText("English only, fast");

                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL))
                    textView.setText("Multi-lingual, slow");
                else
                    textView.setText("English only, fast");

                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        if (permission == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted");
        } else {
            Log.d(TAG, "Requesting record permission");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0);
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted");
        } else {
            Log.d(TAG, "Record permission is not granted");
        }
    }

    // Recording calls
    private void startRecording() {
        checkRecordPermission();

        File waveFile= new File(sdcardDataFolder, WaveUtil.RECORDING_FILE);
        mRecorder.setFilePath(waveFile.getAbsolutePath());
        mRecorder.start();
    }

    private void stopRecording() {
        mRecorder.stop();
    }

    // Transcription calls
    private void startTranscription(String waveFilePath) {
        mWhisper.setFilePath(waveFilePath);
        mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);
        mWhisper.start();
    }

    private void stopTranscription() {
        mWhisper.stop();
    }

    // Copy assets with specified extensions to destination folder
    private static void copyAssetsToSdcard(Context context, File destFolder, String[] extensions) {
        AssetManager assetManager = context.getAssets();

        try {
            // List all files in the assets folder once
            String[] assetFiles = assetManager.list("");
            if (assetFiles == null) return;

            for (String assetFileName : assetFiles) {
                // Check if file matches any of the provided extensions
                for (String extension : extensions) {
                    if (assetFileName.endsWith("." + extension)) {
                        File outFile = new File(destFolder, assetFileName);

                        // Skip if file already exists
                        if (outFile.exists()) break;

                        // Copy the file from assets to the destination folder
                        try (InputStream inputStream = assetManager.open(assetFileName);
                             OutputStream outputStream = new FileOutputStream(outFile)) {

                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                outputStream.write(buffer, 0, bytesRead);
                            }
                        }
                        break; // No need to check further extensions
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public ArrayList<File> getFilesWithExtension(File directory, String extension) {
        ArrayList<File> filteredFiles = new ArrayList<>();

        // Check if the directory is accessible
        if (directory != null && directory.exists()) {
            File[] files = directory.listFiles();

            // Filter files by the provided extension
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(extension)) {
                        filteredFiles.add(file);
                    }
                }
            }
        }

        return filteredFiles;
    }

    static class SharedResource {
        // Synchronized method for Thread 1 to wait for a signal with a timeout
        public synchronized boolean waitForSignalWithTimeout(long timeoutMillis) {
            long startTime = System.currentTimeMillis();

            try {
                wait(timeoutMillis);  // Wait for the given timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();  // Restore interrupt status
                return false;  // Thread interruption as timeout
            }

            long elapsedTime = System.currentTimeMillis() - startTime;

            // Check if wait returned due to notify or timeout
            if (elapsedTime < timeoutMillis) {
                return true;  // Returned due to notify
            } else {
                return false;  // Returned due to timeout
            }
        }

        // Synchronized method for Thread 2 to send a signal
        public synchronized void sendSignal() {
            notify();  // Notifies the waiting thread
        }
    }

}