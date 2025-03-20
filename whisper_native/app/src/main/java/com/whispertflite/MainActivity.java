package com.whispertflite;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.whispertflite.asr.Player;
import com.whispertflite.utils.WaveUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import android.app.AlertDialog;
import android.os.Build;
import android.provider.Settings;
import android.content.ActivityNotFoundException;
import androidx.documentfile.provider.DocumentFile;
import androidx.core.content.FileProvider;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // whisper-tiny.tflite and whisper-base-nooptim.en.tflite works well
    private static final String DEFAULT_MODEL_TO_USE = "whisper-tiny.tflite";
    // English only model ends with extension ".en.tflite"
    private static final String ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite";
    private static final String ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin";
    private static final String MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin";
    private static final String[] EXTENSIONS_TO_COPY = {"tflite", "bin", "wav", "pcm"};

    private TextView tvStatus;
    private TextView tvResult;
    private FloatingActionButton fabCopy;
    private Button btnRecord;
    private Button btnPlay;
    private Button btnTranscribe;

    private Player mPlayer = null;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;

    private File sdcardDataFolder = null;
    private File selectedWaveFile = null;
    private File selectedTfliteFile = null;

    private long startTime = 0;
    private final boolean loopTesting = false;
    private final SharedResource transcriptionSync = new SharedResource();
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Add these new fields for file selection
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                boolean allGranted = true;
                for (Boolean isGranted : permissions.values()) {
                    allGranted = allGranted && isGranted;
                }
                if (allGranted) {
                    openAudioFilePicker();
                } else {
                    // Show dialog with option to go to settings
                    showPermissionExplanationDialog();
                }
            });

    private final ActivityResultLauncher<Intent> selectAudioLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri audioUri = result.getData().getData();
                            processAudioFile(audioUri);
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Call the method to copy specific file types from assets to data folder
        sdcardDataFolder = this.getExternalFilesDir(null);
        copyAssetsToSdcard(this, sdcardDataFolder, EXTENSIONS_TO_COPY);

        ArrayList<File> tfliteFiles = getFilesWithExtension(sdcardDataFolder, ".tflite");
        ArrayList<File> waveFiles = getFilesWithExtension(sdcardDataFolder, ".wav");

        // Initialize default model to use
        selectedTfliteFile = new File(sdcardDataFolder, DEFAULT_MODEL_TO_USE);

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

        Spinner spinnerWave = findViewById(R.id.spnrWaveFiles);
        spinnerWave.setAdapter(getFileArrayAdapter(waveFiles));
        spinnerWave.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Cast item to File and get the file name
                selectedWaveFile = (File) parent.getItemAtPosition(position);

                // Check if the selected file is the recording file
                if (selectedWaveFile.getName().equals(WaveUtil.RECORDING_FILE)) {
                    btnRecord.setVisibility(View.VISIBLE);
                } else {
                    btnRecord.setVisibility(View.GONE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Handle case when nothing is selected, if needed
            }
        });

        // Implementation of record button functionality
        btnRecord = findViewById(R.id.btnRecord);
        btnRecord.setOnClickListener(v -> {
            if (mRecorder != null && mRecorder.isInProgress()) {
                Log.d(TAG, "Recording is in progress... stopping...");
                stopRecording();
            } else {
                Log.d(TAG, "Start recording...");
                startRecording();
            }
        });

        // Implementation of Play button functionality
        btnPlay = findViewById(R.id.btnPlay);
        btnPlay.setOnClickListener(v -> {
            if(!mPlayer.isPlaying()) {
                mPlayer.initializePlayer(selectedWaveFile.getAbsolutePath());
                mPlayer.startPlayback();
            } else {
                mPlayer.stopPlayback();
            }
        });

        // Implementation of transcribe button functionality
        btnTranscribe = findViewById(R.id.btnTranscb);
        btnTranscribe.setOnClickListener(v -> {
            if (mRecorder != null && mRecorder.isInProgress()) {
                Log.d(TAG, "Recording is in progress... stopping...");
                stopRecording();
            }

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
                    handler.post(() -> tvResult.setText(""));
                    handler.post(() -> btnRecord.setText(R.string.stop));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    handler.post(() -> btnRecord.setText(R.string.record));
                }
            }

            @Override
            public void onDataReceived(float[] samples) {
//                mWhisper.writeBuffer(samples);
            }
        });

        // Audio playback functionality
        mPlayer = new Player(this);
        mPlayer.setListener(new Player.PlaybackListener() {
            @Override
            public void onPlaybackStarted() {
                handler.post(() -> btnPlay.setText(R.string.stop));
            }

            @Override
            public void onPlaybackStopped() {
                handler.post(() -> btnPlay.setText(R.string.play));
            }
        });

        // Assume this Activity is the current activity, check record permission
        checkRecordPermission();

        // Add this new button handler
        Button btnSelectAudio = findViewById(R.id.btnSelectAudio);
        btnSelectAudio.setOnClickListener(v -> checkPermissionsAndOpenFilePicker());

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
                    handler.post(() -> tvResult.setText(""));
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
                
                // Always save to cache first
                File cachedTranscription = saveTranscriptionToCache(result);
                
                // If we're processing a selected file (not from the dropdown)
                if (selectedAudioUri != null) {
                    handler.post(() -> {
                        // Show dialog with options for the transcription
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Transcription Complete")
                            .setMessage("What would you like to do with the transcription?")
                            .setPositiveButton("Share", (dialog, which) -> {
                                shareTranscription(result);
                            })
                            .setNegativeButton("Save", (dialog, which) -> {
                                saveTranscriptionWithSaf(selectedAudioUri, result);
                            })
                            .setNeutralButton("Close", null)
                            .show();
                        
                        // Reset the selected URI
                        selectedAudioUri = null;
                    });
                }
                // If we're processing a file from the dropdown
                else if (mWhisper.getFilePath() != null) {
                    String textFilePath = saveTranscriptionToFile(mWhisper.getFilePath(), result);
                    if (textFilePath != null) {
                        handler.post(() -> {
                            String message = "Transcription saved to: " + textFilePath;
                            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            
                            // Offer to share this transcription too
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Share Transcription?")
                                .setMessage("Would you like to share this transcription?")
                                .setPositiveButton("Share", (dialog, which) -> {
                                    shareTranscription(result);
                                })
                                .setNegativeButton("No", null)
                                .show();
                        });
                    }
                }
            }
        });
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    private @NonNull ArrayAdapter<File> getFileArrayAdapter(ArrayList<File> waveFiles) {
        ArrayAdapter<File> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, waveFiles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                textView.setText(getItem(position).getName());  // Show only the file name
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                textView.setText(getItem(position).getName());  // Show only the file name
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

    // Add these methods for file selection and processing
    private void checkPermissionsAndOpenFilePicker() {
        // For Android 13+ (API 33+), we need READ_MEDIA_AUDIO permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) 
                    == PackageManager.PERMISSION_GRANTED) {
                openAudioFilePicker();
            } else {
                boolean shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO);
                
                if (!shouldShowRationale) {
                    // User selected "Don't ask again" - direct to settings
                    showPermissionExplanationDialog();
                } else {
                    // Request the permission
                    requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_MEDIA_AUDIO});
                }
            }
        } 
        // For Android 10-12 (API 29-32)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED) {
                openAudioFilePicker();
            } else {
                boolean shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE);
                
                if (!shouldShowRationale) {
                    showPermissionExplanationDialog();
                } else {
                    requestPermissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
                }
            }
        }
        // For older Android versions (below API 29)
        else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED) {
                openAudioFilePicker();
            } else {
                boolean shouldShowReadRationale = shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE);
                boolean shouldShowWriteRationale = shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                
                if (!shouldShowReadRationale && !shouldShowWriteRationale) {
                    showPermissionExplanationDialog();
                } else {
                    requestPermissionLauncher.launch(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    });
                }
            }
        }
    }

    /**
     * Shows a dialog explaining why storage permissions are needed
     * and offers option to go to app settings
     */
    private void showPermissionExplanationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Storage Permission Required")
                .setMessage("This app needs permission to access audio files on your device. Please enable the 'Files and media' or 'Music and audio' permission in app settings.")
                .setPositiveButton("Go to Settings", (dialog, which) -> {
                    openAppSettings();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    Toast.makeText(this, "Storage access permission is required to select audio files", Toast.LENGTH_LONG).show();
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Opens the app settings page where user can grant permissions
     */
    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getPackageName(), null);
        intent.setData(uri);
        startActivity(intent);
        Toast.makeText(this, "Please enable storage permissions", Toast.LENGTH_LONG).show();
    }

    private void openAudioFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        // Add these flags to ensure we can read the file later and keep access
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        selectAudioLauncher.launch(intent);
    }

    private void processAudioFile(Uri audioUri) {
        try {
            // Take persistent permission for future access
            try {
                getContentResolver().takePersistableUriPermission(audioUri, 
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                Log.w(TAG, "Could not take persistable permission: " + e.getMessage());
                // Continue anyway as we already have the URI
            }
                
            tvStatus.setText("Preparing to transcribe selected audio file...");
            tvResult.setText("");
            
            // Store the URI for later use when saving transcription
            selectedAudioUri = audioUri;
            String fileName = getFileNameFromUri(audioUri);
            if (fileName != null) {
                tvStatus.setText("Transcribing: " + fileName);
            }
            
            // Create temp file for transcription processing
            File tempFile = createTempAudioFile(audioUri);
            if (tempFile == null) {
                Toast.makeText(this, "Failed to process audio file", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Initialize model if needed
            if (mWhisper == null) {
                initModel(selectedTfliteFile);
            }
            
            // Start transcription using existing Whisper implementation
            startTranscription(tempFile.getAbsolutePath());
            
            // Also save the transcription once completed
            if (!mWhisper.isInProgress()) {
                Toast.makeText(this, "Starting transcription...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Transcription already in progress", Toast.LENGTH_SHORT).show();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing audio file: " + e.getMessage(), e);
            tvStatus.setText("Error: " + e.getMessage());
        }
    }
    
    // Add a field to store the selected audio URI
    private Uri selectedAudioUri = null;
    
    /**
     * Save transcription to app's cache directory
     */
    private File saveTranscriptionToCache(String transcription) {
        try {
            // Create a timestamped file name
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String fileName = "transcription_" + timestamp + ".txt";
            
            File cacheFile = new File(getCacheDir(), fileName);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(transcription.getBytes());
                return cacheFile;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving transcription to cache", e);
            return null;
        }
    }
    
    /**
     * Share the transcription text using Android's share system
     */
    private void shareTranscription(String transcription) {
        try {
            // Save to a temporary file for sharing
            File tempFile = saveTranscriptionToCache(transcription);
            if (tempFile == null) {
                Toast.makeText(this, "Failed to prepare transcription for sharing", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Get content URI using FileProvider
            Uri contentUri = FileProvider.getUriForFile(
                this,
                "com.whispertflite.fileprovider",
                tempFile
            );
            
            // Create share intent
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.putExtra(Intent.EXTRA_TEXT, transcription); // Also include as text
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            try {
                startActivity(Intent.createChooser(shareIntent, "Share Transcription"));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No app available to share", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sharing transcription", e);
            Toast.makeText(this, "Error sharing transcription", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Use Storage Access Framework to let user choose where to save the transcription
     */
    private void saveTranscriptionWithSaf(Uri sourceAudioUri, String transcription) {
        try {
            // Get original file name to create a similar name for transcription
            String audioFileName = getFileNameFromUri(sourceAudioUri);
            String transcriptionFileName = "transcription_";
            
            if (audioFileName != null) {
                // Remove extension and add .txt
                int extensionPos = audioFileName.lastIndexOf(".");
                if (extensionPos > 0) {
                    transcriptionFileName = audioFileName.substring(0, extensionPos);
                } else {
                    transcriptionFileName = audioFileName;
                }
            } else {
                // Use timestamp if no file name is available
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(new java.util.Date());
                transcriptionFileName = "transcription_" + timestamp;
            }
            
            // Add .txt extension
            if (!transcriptionFileName.endsWith(".txt")) {
                transcriptionFileName += ".txt";
            }
            
            // Create intent to save a document
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, transcriptionFileName);
            
            // Try to set initial URI to be the same folder as the audio
            try {
                // For content URIs, try to get the parent directory
                if ("content".equals(sourceAudioUri.getScheme())) {
                    DocumentFile documentFile = DocumentFile.fromSingleUri(this, sourceAudioUri);
                    if (documentFile != null && documentFile.getParentFile() != null) {
                        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, 
                                documentFile.getParentFile().getUri());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not set initial URI: " + e.getMessage());
                // Continue without setting initial URI
            }
            
            // Store transcription for the callback
            temporaryTranscription = transcription;
            
            // Launch the save dialog
            saveTranscriptionLauncher.launch(intent);
            
        } catch (Exception e) {
            Log.e(TAG, "Error initiating save with SAF", e);
            Toast.makeText(this, "Error initiating save: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    // Store transcription temporarily for the save callback
    private String temporaryTranscription = null;
    
    // Add launcher for handling the save document activity result
    private final ActivityResultLauncher<Intent> saveTranscriptionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri saveUri = result.getData().getData();
                            if (saveUri != null && temporaryTranscription != null) {
                                saveTranscriptionToUri(saveUri, temporaryTranscription);
                            }
                        } else {
                            Toast.makeText(this, "Transcription save cancelled", Toast.LENGTH_SHORT).show();
                        }
                        temporaryTranscription = null; // Clear the temporary storage
                    });
    
    /**
     * Actually save the transcription to the selected URI
     */
    private void saveTranscriptionToUri(Uri uri, String transcription) {
        try {
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                if (outputStream != null) {
                    outputStream.write(transcription.getBytes());
                    outputStream.flush();
                    Toast.makeText(this, "Transcription saved successfully", Toast.LENGTH_SHORT).show();
                    
                    // Show the save location
                    new AlertDialog.Builder(this)
                        .setTitle("Transcription Saved")
                        .setMessage("The transcription has been saved. You can find it in your file manager where you selected to save it.")
                        .setPositiveButton("OK", null)
                        .show();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving transcription to URI", e);
            Toast.makeText(this, "Error saving transcription: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private String getPathFromUri(Uri uri) {
        Log.d(TAG, "Getting path from URI: " + uri.toString());
        
        // Handle different URI schemes
        if ("content".equals(uri.getScheme())) {
            // Try to get the actual file path for newer Android versions
            try {
                String[] projection = { MediaStore.Audio.Media.DATA };
                Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    String path = cursor.getString(columnIndex);
                    cursor.close();
                    Log.d(TAG, "Found path via MediaStore: " + path);
                    return path;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting file path from MediaStore: " + e.getMessage());
                // Continue to other methods if this fails
            }
            
            // Fall back to temp file creation method
            return null;
        } else if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        
        return null;
    }
    
    private File createTempAudioFile(Uri uri) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        if (inputStream == null) return null;
        
        String fileName = getFileNameFromUri(uri);
        if (fileName == null) fileName = "temp_audio";
        
        File outputDir = getCacheDir();
        File outputFile = new File(outputDir, fileName);
        
        try (OutputStream outputStream = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[4 * 1024]; // 4k buffer
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
            return outputFile;
        } finally {
            inputStream.close();
        }
    }
    
    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }
    
    private String saveTranscriptionToFile(String audioFilePath, String transcription) {
        try {
            // Create output file path with same name but .txt extension
            String textFilePath = audioFilePath.substring(0, audioFilePath.lastIndexOf('.')) + ".txt";
            File textFile = new File(textFilePath);
            
            // Write transcription to file
            try (FileOutputStream fos = new FileOutputStream(textFile)) {
                fos.write(transcription.getBytes());
                return textFilePath;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving transcription", e);
            return null;
        }
    }

    // Test code for parallel processing
//    private void testParallelProcessing() {
//
//        // Define the file names in an array
//        String[] fileNames = {
//                "english_test1.wav",
//                "english_test2.wav",
//                "english_test_3_bili.wav"
//        };
//
//        // Multilingual model and vocab
//        String modelMultilingual = getFilePath("whisper-tiny.tflite");
//        String vocabMultilingual = getFilePath("filters_vocab_multilingual.bin");
//
//        // Perform task for multiple audio files using multilingual model
//        for (String fileName : fileNames) {
//            Whisper whisper = new Whisper(this);
//            whisper.setAction(Whisper.ACTION_TRANSCRIBE);
//            whisper.loadModel(modelMultilingual, vocabMultilingual, true);
//            //whisper.setListener((msgID, message) -> Log.d(TAG, message));
//            String waveFilePath = getFilePath(fileName);
//            whisper.setFilePath(waveFilePath);
//            whisper.start();
//        }
//
//        // English-only model and vocab
//        String modelEnglish = getFilePath("whisper-tiny-en.tflite");
//        String vocabEnglish = getFilePath("filters_vocab_en.bin");
//
//        // Perform task for multiple audio files using english only model
//        for (String fileName : fileNames) {
//            Whisper whisper = new Whisper(this);
//            whisper.setAction(Whisper.ACTION_TRANSCRIBE);
//            whisper.loadModel(modelEnglish, vocabEnglish, false);
//            //whisper.setListener((msgID, message) -> Log.d(TAG, message));
//            String waveFilePath = getFilePath(fileName);
//            whisper.setFilePath(waveFilePath);
//            whisper.start();
//        }
//    }
}