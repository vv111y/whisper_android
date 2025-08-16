package com.whispertflite;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.widget.SeekBar;
import android.view.KeyEvent;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.ToneGenerator;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.whispertflite.asr.FrameEmitter;
import com.whispertflite.asr.Player;
import com.whispertflite.utils.WaveUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.frontend.DtwWakewordDetector;
import com.whispertflite.frontend.PipelineController;
import com.whispertflite.frontend.VadEnergy;
import com.whispertflite.frontend.WakewordDetector;
import com.whispertflite.audio.BeepPlayer;
import com.whispertflite.engine.WhisperEngineNative;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

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
    private Button btnWakeListenStart; // new
    private Button btnWakeListenStop;  // new
    private Button btnSessionStart;    // new
    private Button btnSessionStop;     // new
    private Button btnVadTuning;       // new
    private Button btnSessionPauseResume; // new
    private Button btnValidateModel; // new
    private android.widget.CheckBox chkCaptureMedia; // new

    private Player mPlayer = null;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private FrameEmitter frameEmitter; // new
    private VadEnergy vadEnergy; // new
    private WakewordDetector wakewordDetector; // new
    private PipelineController pipelineController; // new
    private ToneGenerator toneGen; // ready click
    private Runnable finalizeRunnable; // debounce finalize for session
    private final long finalizeDelayMs = 550; // allow slightly longer pauses
    private final long rearmDelayMs = 150; // slight delay before re-arming after transcription
    private MediaSession mediaSession; // capture play/pause from earbuds
    private AudioManager audioManager; // manage audio focus for media buttons
    private AudioFocusRequest audioFocusRequest; // focus request (O+)
    private boolean hasAudioFocus = false;
    private AudioFocusRequest toneFocusRequest; // transient focus for tones
    private boolean toneFocusHeld = false;
    private BeepPlayer beepPlayer;
    private boolean mediaKeysObserved = false; // for conditional fallback
    private Runnable fallbackClaimTask; // schedule AudioTrack fallback if needed
    private Runnable inactivityReleaseTask; // release silent claim after idle
    private final long fallbackDelayMs = 1200; // wait for keys before fallback
    private final long inactivityTimeoutMs = 15_000; // stop fallback after idle

    private File sdcardDataFolder = null;
    private File selectedWaveFile = null;
    private File selectedTfliteFile = null;

    private long startTime = 0;
    private final boolean loopTesting = false;
    private final SharedResource transcriptionSync = new SharedResource();
    private final Handler handler = new Handler(Looper.getMainLooper());

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
                if (pipelineController != null) pipelineController.onOutputStart();
            }

            @Override
            public void onPlaybackStopped() {
                handler.post(() -> btnPlay.setText(R.string.play));
                if (pipelineController != null) pipelineController.onOutputEnd();
            }
        });

    btnWakeListenStart = findViewById(R.id.btnWakeListenStart);
    btnWakeListenStop = findViewById(R.id.btnWakeListenStop);
    btnSessionStart = findViewById(R.id.btnSessionStart);
    btnSessionStop = findViewById(R.id.btnSessionStop);
    btnVadTuning = findViewById(R.id.btnVadTuning);
    btnSessionPauseResume = findViewById(R.id.btnSessionPauseResume);
    btnValidateModel = findViewById(R.id.btnValidateModel);
    chkCaptureMedia = findViewById(R.id.chkCaptureMedia);
        chkCaptureMedia.setOnCheckedChangeListener((android.widget.CompoundButton buttonView, boolean isChecked) -> {
            // If session is running, apply immediately
            if (pipelineController != null && pipelineController.getMode() == PipelineController.Mode.SESSION) {
                if (isChecked) {
                    // Claim focus and activate session now
                    if (requestAudioFocus()) {
                        mediaSession.setActive(true);
                        updatePlaybackState(PlaybackState.STATE_PLAYING);
                    }
                    try {
                        android.content.Intent svc = new android.content.Intent(this, SessionService.class);
                        svc.putExtra("command", "startNoAudio");
                        ContextCompat.startForegroundService(this, svc);
                    } catch (Throwable t) { Log.d(TAG, "Failed to start service on toggle: " + t.getMessage()); }
                    mediaKeysObserved = false;
                    if (fallbackClaimTask != null) handler.removeCallbacks(fallbackClaimTask);
                    fallbackClaimTask = () -> {
                        if (!mediaKeysObserved) {
                            try {
                                android.content.Intent svc2 = new android.content.Intent(this, SessionService.class);
                                svc2.putExtra("command", "ensureFallback");
                                ContextCompat.startForegroundService(this, svc2);
                            } catch (Throwable t) { Log.d(TAG, "Fallback ensure on toggle failed: " + t.getMessage()); }
                        }
                    };
                    handler.postDelayed(fallbackClaimTask, fallbackDelayMs);
                    scheduleInactivityRelease();
                } else {
                    // Release focus and deactivate session now
                    updatePlaybackState(PlaybackState.STATE_PAUSED);
                    mediaSession.setActive(false);
                    abandonAudioFocus();
                    try {
                        android.content.Intent svc = new android.content.Intent(this, SessionService.class);
                        svc.putExtra("command", "stopFallback");
                        ContextCompat.startForegroundService(this, svc);
                        stopService(svc);
                    } catch (Throwable t) { Log.d(TAG, "Failed to stop service on toggle: " + t.getMessage()); }
                    if (fallbackClaimTask != null) handler.removeCallbacks(fallbackClaimTask);
                    if (inactivityReleaseTask != null) handler.removeCallbacks(inactivityReleaseTask);
                }
            }
        });
        frameEmitter = new FrameEmitter(this);
        pipelineController = new PipelineController(FrameEmitter.FRAME_SAMPLES, new PipelineController.Listener() {
            @Override public void onStateChanged(PipelineController.State state) {
                Log.d(TAG, "Pipeline state=" + state);
                handler.post(() -> tvStatus.setText(state.toString()));
                // Audio feedback on state transitions: LISTENING and IDLE
                if (state == PipelineController.State.LISTENING) {
                    playStateTone(true);
                } else if (state == PipelineController.State.IDLE) {
                    playStateTone(false);
                }
            }
            @Override public void onWakeTriggered(double score) { Log.d(TAG, "Pipeline wake triggered score=" + score); }
            @Override public void onUtteranceReady(float[] samples) {
                // Write temp WAV to reuse existing file transcription path
                try {
                    // Light normalization: center and peak-normalize to -1..1 with a cap
                    float[] norm = normalizeAudio(samples);
                    File tmp = new File(sdcardDataFolder, "wake_capture.wav");
                    com.whispertflite.utils.WaveUtil.createWaveFile(tmp.getAbsolutePath(), to16Bit(norm), 16000,1,2);
                    if (mWhisper == null) initModel(selectedTfliteFile);
                    startTranscription(tmp.getAbsolutePath());
                } catch (Exception e) { Log.d(TAG, "Failed to write wake capture wav: " + e.getMessage()); }
            }
        });
        // adjust wakeword callback to notify pipeline
        try {
            wakewordDetector = new DtwWakewordDetector(this,
                    "wake_templates.txt",
                    13,
                    400,
                    160,
                    30,
                    0.8,
                    2000,
                    score -> { Log.d(TAG, "WAKE TRIGGERED score=" + score); pipelineController.onWakeTriggered(score); });
        } catch (Exception e) { Log.d(TAG, "Wakeword init failed: " + e.getMessage()); }
        // Initialize VAD before assigning frame emitter listener
        // Slightly higher threshold and longer hangover to reduce false positives/splits
        vadEnergy = new VadEnergy(0.035f, 30, new VadEnergy.Listener() {
            @Override public void onSpeechStart() {
                Log.d(TAG, "VAD speech start");
                // Cancel any pending finalize to merge short gaps
                if (finalizeRunnable != null) handler.removeCallbacks(finalizeRunnable);
                pipelineController.onSpeechStart();
            }
            @Override public void onSpeechEnd() {
                Log.d(TAG, "VAD speech end");
                // Debounce finalization to merge brief pauses into one utterance (session mode)
                if (finalizeRunnable != null) handler.removeCallbacks(finalizeRunnable);
                finalizeRunnable = () -> pipelineController.onSpeechEnd();
                handler.postDelayed(finalizeRunnable, finalizeDelayMs);
            }
            @Override public void onFrameAccepted(float[] frame, boolean speech) {
                if (pipelineController.getState() == PipelineController.State.LISTENING && speech && wakewordDetector != null) wakewordDetector.acceptFrame(frame, true);
                pipelineController.onFrame(frame, speech);
            }
        });
        frameEmitter.setListener(new FrameEmitter.Listener() {
            @Override public void onFrame(float[] pcmFrame) {
                // Upstream gating: avoid feeding VAD while output is playing (no barge-in)
                if (pipelineController != null && pipelineController.isInputGated()) return;
                vadEnergy.accept(pcmFrame);
            }
            @Override public void onError(String msg) { Log.d(TAG, "FrameEmitter error: " + msg); }
        });
        btnWakeListenStart.setOnClickListener(v -> {
            if (!frameEmitter.isRunning()) frameEmitter.start();
            pipelineController.startListening();
        });
        btnWakeListenStop.setOnClickListener(v -> {
            if (frameEmitter.isRunning()) frameEmitter.stop();
            pipelineController.stop();
        });

        // Session listen wiring
        btnSessionStart.setOnClickListener(v -> {
            if (!frameEmitter.isRunning()) frameEmitter.start();
            pipelineController.startSession();
            mediaKeysObserved = false;
            // Activate MediaSession + focus only if capture toggle ON
            if (chkCaptureMedia.isChecked()) {
                if (requestAudioFocus()) {
                    mediaSession.setActive(true);
                    updatePlaybackState(PlaybackState.STATE_PLAYING);
                }
            } else {
                updatePlaybackState(PlaybackState.STATE_PAUSED);
                mediaSession.setActive(false);
                abandonAudioFocus();
            }
            // Conditionally start foreground service only if toggle is ON
            if (chkCaptureMedia.isChecked()) {
                try {
                    android.content.Intent svc = new android.content.Intent(this, SessionService.class);
                    svc.putExtra("command", "startNoAudio");
                    ContextCompat.startForegroundService(this, svc);
                } catch (Throwable t) { Log.d(TAG, "Failed to start foreground service: " + t.getMessage()); }
            }
            // Schedule fallback silent claim only if no keys arrive in time
            if (chkCaptureMedia.isChecked()) {
                if (fallbackClaimTask != null) handler.removeCallbacks(fallbackClaimTask);
                fallbackClaimTask = () -> {
                    if (!mediaKeysObserved) {
                        Log.d(TAG, "No media keys observed; requesting fallback claim");
                        try {
                            android.content.Intent svc = new android.content.Intent(this, SessionService.class);
                            svc.putExtra("command", "ensureFallback");
                            ContextCompat.startForegroundService(this, svc);
                        } catch (Throwable t) { Log.d(TAG, "Fallback ensure failed: " + t.getMessage()); }
                    }
                };
                handler.postDelayed(fallbackClaimTask, fallbackDelayMs);
            }
            // Start inactivity timer to release fallback later
            scheduleInactivityRelease();
        });
        btnSessionStop.setOnClickListener(v -> {
            if (frameEmitter.isRunning()) frameEmitter.stop();
            pipelineController.stopSession();
            updatePlaybackState(PlaybackState.STATE_STOPPED);
            mediaSession.setActive(false);
            abandonAudioFocus();
            try {
                android.content.Intent svc = new android.content.Intent(this, SessionService.class);
                stopService(svc);
            } catch (Throwable t) { Log.d(TAG, "Failed to stop foreground service: " + t.getMessage()); }
            if (fallbackClaimTask != null) handler.removeCallbacks(fallbackClaimTask);
            if (inactivityReleaseTask != null) handler.removeCallbacks(inactivityReleaseTask);
        });

    // Validate model (load-only check with detailed error)
    btnValidateModel.setOnClickListener(v -> {
        try {
        boolean isMultilingualModel = !(selectedTfliteFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        WhisperEngineNative engine = new WhisperEngineNative(this);
        int code = engine.validateModel(selectedTfliteFile.getAbsolutePath(), isMultilingualModel);
        String msg = code == 0 ? ("Model OK: " + selectedTfliteFile.getName())
            : ("Invalid: code " + code + "\n" + engine.lastError());
        new AlertDialog.Builder(this)
            .setTitle("Model Validation")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show();
        } catch (Throwable t) {
        new AlertDialog.Builder(this)
            .setTitle("Model Validation")
            .setMessage("Validation error: " + t.getMessage())
            .setPositiveButton("OK", null)
            .show();
        }
    });

    btnVadTuning.setOnClickListener(v -> showVadTuningDialog());

        btnSessionPauseResume.setOnClickListener(v -> {
            if (pipelineController.getState() == PipelineController.State.LISTENING) {
                pipelineController.pauseListening();
                btnSessionPauseResume.setText("Resume");
                updatePlaybackState(PlaybackState.STATE_PAUSED);
            } else {
                if (!frameEmitter.isRunning()) frameEmitter.start();
                pipelineController.resumeListening();
                btnSessionPauseResume.setText("Pause");
                if (chkCaptureMedia.isChecked()) {
                    if (requestAudioFocus()) {
                        mediaSession.setActive(true);
                    }
                    updatePlaybackState(PlaybackState.STATE_PLAYING);
                } else {
                    mediaSession.setActive(false);
                    abandonAudioFocus();
                    updatePlaybackState(PlaybackState.STATE_PAUSED);
                }
                scheduleInactivityRelease();
            }
        });
    // MediaSession for tap-to-pause (earbud play/pause -> toggle session listening)
    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    mediaSession = new MediaSession(this, "WhisperSession");
    MediaSessionHolder.set(mediaSession);
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        try {
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
            this,
            0,
            new android.content.Intent(this, MainActivity.class),
            android.os.Build.VERSION.SDK_INT >= 23 ? (android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE) : android.app.PendingIntent.FLAG_UPDATE_CURRENT
        );
        mediaSession.setSessionActivity(pi);
            mediaSession.setMediaButtonReceiver(pi);
    } catch (Throwable ignore) {}
    try {
        mediaSession.setPlaybackToLocal(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build());
        } catch (Throwable t) {
            // ignore if not supported on some API levels
        }
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public boolean onMediaButtonEvent(android.content.Intent mediaButtonIntent) {
                if (!chkCaptureMedia.isChecked()) {
                    // Do not intercept when capture is disabled
                    return super.onMediaButtonEvent(mediaButtonIntent);
                }
                Log.d(TAG, "onMediaButtonEvent: " + mediaButtonIntent);
                KeyEvent keyEvent = mediaButtonIntent.getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT);
                if (keyEvent != null && keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                    int code = keyEvent.getKeyCode();
                    Log.d(TAG, "Media key code: " + code);
                    mediaKeysObserved = true;
                    if (chkCaptureMedia.isChecked()) {
                        try {
                            android.content.Intent svc = new android.content.Intent(MainActivity.this, SessionService.class);
                            svc.putExtra("command", "stopFallback");
                            ContextCompat.startForegroundService(MainActivity.this, svc);
                        } catch (Throwable t) { Log.d(TAG, "stopFallback on first key failed: " + t.getMessage()); }
                    }
                    scheduleInactivityRelease();
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK
                || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                        if (pipelineController.getState() == com.whispertflite.frontend.PipelineController.State.LISTENING) {
                            pipelineController.pauseListening();
                            handler.post(() -> btnSessionPauseResume.setText("Resume"));
                        } else {
                            if (!frameEmitter.isRunning()) frameEmitter.start();
                            pipelineController.resumeListening();
                            handler.post(() -> btnSessionPauseResume.setText("Pause"));
                        }
                        return true;
                    } else if (code == KeyEvent.KEYCODE_MEDIA_PLAY) {
                        if (!frameEmitter.isRunning()) frameEmitter.start();
                        pipelineController.resumeListening();
                        handler.post(() -> btnSessionPauseResume.setText("Pause"));
                        return true;
                    } else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                        pipelineController.pauseListening();
                        handler.post(() -> btnSessionPauseResume.setText("Resume"));
                        return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }

            @Override
            public void onPlay() {
                if (!chkCaptureMedia.isChecked()) return;
                // Treat as resume listening
                if (!frameEmitter.isRunning()) frameEmitter.start();
                pipelineController.resumeListening();
                handler.post(() -> btnSessionPauseResume.setText("Pause"));
                if (requestAudioFocus()) mediaSession.setActive(true);
                updatePlaybackState(PlaybackState.STATE_PLAYING);
            }

            @Override
            public void onPause() {
                if (!chkCaptureMedia.isChecked()) return;
                // Treat as pause listening
                pipelineController.pauseListening();
                handler.post(() -> btnSessionPauseResume.setText("Resume"));
                updatePlaybackState(PlaybackState.STATE_PAUSED);
            }

            // No onPlayPause in Callback; handled via onMediaButtonEvent above
        });
    // Prepare session; activation controlled by session start/stop
    updatePlaybackState(PlaybackState.STATE_PAUSED);

        // Assume this Activity is the current activity, check record permission
        checkRecordPermission();

        // for debugging
//        testParallelProcessing();
    }

    private void scheduleInactivityRelease() {
        if (!chkCaptureMedia.isChecked()) return;
        if (inactivityReleaseTask != null) handler.removeCallbacks(inactivityReleaseTask);
        inactivityReleaseTask = () -> {
            try {
                android.content.Intent svc = new android.content.Intent(this, SessionService.class);
                svc.putExtra("command", "stopFallback");
                ContextCompat.startForegroundService(this, svc);
            } catch (Throwable t) { Log.d(TAG, "Inactivity stopFallback failed: " + t.getMessage()); }
        };
        handler.postDelayed(inactivityReleaseTask, inactivityTimeoutMs);
    }

    private void showVadTuningDialog() {
        if (vadEnergy == null) return;
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.dialog_vad_tuning, null);
        SeekBar seekThr = view.findViewById(R.id.seekThreshold);
        SeekBar seekHang = view.findViewById(R.id.seekHangover);
        SeekBar seekAtk = view.findViewById(R.id.seekAttack);
    TextView txtThrVal = view.findViewById(R.id.txtThresholdVal);
    TextView txtHangVal = view.findViewById(R.id.txtHangoverVal);
    TextView txtAtkVal = view.findViewById(R.id.txtAttackVal);

        // Map threshold 0.005..0.1 to 0..100
        float thr = vadEnergy.getThreshold();
        int thrProg = (int)Math.max(0, Math.min(100, Math.round((thr - 0.005f) / (0.1f - 0.005f) * 100f)));
        seekThr.setProgress(thrProg);
    seekThr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float v = 0.005f + (p / 100f) * (0.1f - 0.005f);
                vadEnergy.setThreshold(v);
        txtThrVal.setText(String.format(java.util.Locale.US, "%.3f", v));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        int hang = vadEnergy.getHangoverFrames();
        seekHang.setProgress(Math.max(0, Math.min(100, hang)));
        seekHang.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { vadEnergy.setHangoverFrames(p); txtHangVal.setText(String.valueOf(p)); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        int atk = vadEnergy.getStartAttackFrames();
        seekAtk.setProgress(Math.max(1, Math.min(10, atk)));
        seekAtk.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { int v = Math.max(1, p); vadEnergy.setStartAttackFrames(v); txtAtkVal.setText(String.valueOf(v)); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Initialize value labels
        txtThrVal.setText(String.format(java.util.Locale.US, "%.3f", vadEnergy.getThreshold()));
        txtHangVal.setText(String.valueOf(vadEnergy.getHangoverFrames()));
        txtAtkVal.setText(String.valueOf(vadEnergy.getStartAttackFrames()));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("VAD Tuning")
                .setView(view)
                .setPositiveButton("Close", null)
                .setNeutralButton("Reset", null)
                .create();
        dialog.setOnShowListener(dlg -> {
            android.widget.Button resetBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (resetBtn != null) {
                resetBtn.setOnClickListener(v2 -> {
                    // Tuned defaults
                    float thrDef = 0.035f;
                    int hangDef = 30;
                    int atkDef = 3;
                    // Apply
                    vadEnergy.setThreshold(thrDef);
                    vadEnergy.setHangoverFrames(hangDef);
                    vadEnergy.setStartAttackFrames(atkDef);
                    // Update UI
                    int thrProgDef = (int)Math.max(0, Math.min(100, Math.round((thrDef - 0.005f) / (0.1f - 0.005f) * 100f)));
                    seekThr.setProgress(thrProgDef);
                    seekHang.setProgress(hangDef);
                    seekAtk.setProgress(atkDef);
                    txtThrVal.setText(String.format(java.util.Locale.US, "%.3f", thrDef));
                    txtHangVal.setText(String.valueOf(hangDef));
                    txtAtkVal.setText(String.valueOf(atkDef));
                    // Do not dismiss dialog
                });
            }
        });
        dialog.show();
    }

    private void updatePlaybackState(int state) {
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        PlaybackState pbState = new PlaybackState.Builder()
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0)
                .setActions(actions)
                .build();
        if (mediaSession != null) mediaSession.setPlaybackState(pbState);
    }

    private boolean requestAudioFocus() {
        if (hasAudioFocus) return true;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(focusChange -> {
                            if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                                // Pause listening on focus loss
                                pipelineController.pauseListening();
                                handler.post(() -> btnSessionPauseResume.setText("Resume"));
                                updatePlaybackState(PlaybackState.STATE_PAUSED);
                            }
                        })
                        .build();
                int res = audioManager.requestAudioFocus(audioFocusRequest);
                hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            } else {
                int res = audioManager.requestAudioFocus(
                        focusChange -> {
                            if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                                pipelineController.pauseListening();
                                handler.post(() -> btnSessionPauseResume.setText("Resume"));
                                updatePlaybackState(PlaybackState.STATE_PAUSED);
                            }
                        },
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
                hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            }
        } catch (Exception ignore) { hasAudioFocus = false; }
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (!hasAudioFocus) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } else {
                audioManager.abandonAudioFocus(null);
            }
        } catch (Exception ignore) {}
        hasAudioFocus = false;
    }

    private void requestToneFocusIfNeeded() {
        if (hasAudioFocus || toneFocusHeld) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                toneFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(fc -> {})
                        .build();
                int res = audioManager.requestAudioFocus(toneFocusRequest);
                toneFocusHeld = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            } else {
                int res = audioManager.requestAudioFocus(
                        fc -> {},
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                toneFocusHeld = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            }
        } catch (Exception ignore) { toneFocusHeld = false; }
    }

    private void abandonToneFocus() {
        if (!toneFocusHeld) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && toneFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(toneFocusRequest);
            } else {
                audioManager.abandonAudioFocus(null);
            }
        } catch (Exception ignore) {}
        toneFocusHeld = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (mediaSession != null) {
                // Keep session instance; SessionService holds foreground state
            }
        } catch (Exception ignore) {}
        abandonAudioFocus();
    try { if (beepPlayer != null) { beepPlayer.release(); beepPlayer = null; } } catch (Exception ignore) {}
    }

    // Model initialization
    private void initModel(File modelFile) {
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                // Always reflect Whisper updates in the status line (init errors, etc.)
                handler.post(() -> tvStatus.setText(message));

                // If model initialization failed, surface details in a dialog too
                if (message != null && message.startsWith("Model initialization failed")) {
                    handler.post(() -> new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Model Init Error")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show());
                }

                if (message.equals(Whisper.MSG_PROCESSING)) {
                    handler.post(() -> tvResult.setText(""));
                    startTime = System.currentTimeMillis();
                } else if (message.equals(Whisper.MSG_PROCESSING_DONE)) {
                    // for testing
                    if (loopTesting) transcriptionSync.sendSignal();
                } else if (message.equals(Whisper.MSG_FILE_NOT_FOUND)) {
                    Log.d(TAG, "File not found error...!");
                }
            }

            @Override
            public void onResultReceived(String result) {
                long timeTaken = System.currentTimeMillis() - startTime;
                handler.post(() -> tvStatus.setText("Processing done in " + timeTaken + "ms"));

                Log.d(TAG, "Result: " + result);
                handler.post(() -> {
                    boolean session = (pipelineController != null && pipelineController.getMode() == PipelineController.Mode.SESSION);
                    String line = session ? ("• " + result + "\n") : (result + "\n");
                    tvResult.append(line);
                });
            }
        });

        mWhisper.setCompletionListener(() -> {
            runOnUiThread(() -> {
                if (pipelineController != null) {
                    handler.postDelayed(() -> pipelineController.onTranscriptionComplete(), rearmDelayMs);
                }
            });
        });
        // Load model AFTER listeners are registered so init errors surface in UI
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
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

    private byte[] to16Bit(float[] samples) {
        byte[] out = new byte[samples.length * 2];
        int idx = 0;
        for (float s : samples) {
            int v = (int)(Math.max(-1f, Math.min(1f, s)) * 32767);
            out[idx++] = (byte)(v & 0xFF);
            out[idx++] = (byte)((v >> 8) & 0xFF);
        }
        return out;
    }

    private float[] normalizeAudio(float[] in) {
        if (in == null || in.length == 0) return in;
        // DC offset removal (simple mean subtraction)
        double sum = 0;
        for (float v : in) sum += v;
        float mean = (float)(sum / in.length);
        float peak = 0f;
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            float v = in[i] - mean;
            out[i] = v;
            float a = Math.abs(v);
            if (a > peak) peak = a;
        }
        if (peak < 1e-6f) return out;
        // Peak normalize to 0.9 to avoid clipping
        float gain = 0.9f / peak;
        for (int i = 0; i < out.length; i++) out[i] *= gain;
        return out;
    }

    private File ensureSilentWav(int durationMs) throws IOException {
        if (sdcardDataFolder == null) return null;
        File f = new File(sdcardDataFolder, "silent_claim.wav");
        if (f.exists()) return f;
        int sampleRate = 16000;
        int channels = 1;
        int bytesPerSample = 2;
        int numSamples = (int)((long)durationMs * sampleRate / 1000L);
        int dataSize = numSamples * channels * bytesPerSample;
        int totalSize = 44 + dataSize;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            // RIFF header
            out.write(new byte[]{'R','I','F','F'});
            out.write(intLE(totalSize - 8));
            out.write(new byte[]{'W','A','V','E'});
            // fmt chunk
            out.write(new byte[]{'f','m','t',' '});
            out.write(intLE(16)); // PCM fmt chunk size
            out.write(shortLE((short)1)); // PCM
            out.write(shortLE((short)channels));
            out.write(intLE(sampleRate));
            out.write(intLE(sampleRate * channels * bytesPerSample));
            out.write(shortLE((short)(channels * bytesPerSample)));
            out.write(shortLE((short)(bytesPerSample * 8)));
            // data chunk
            out.write(new byte[]{'d','a','t','a'});
            out.write(intLE(dataSize));
            // zeros for silence
            byte[] zeros = new byte[Math.min(8192, dataSize)];
            int remaining = dataSize;
            while (remaining > 0) {
                int n = Math.min(remaining, zeros.length);
                out.write(zeros, 0, n);
                remaining -= n;
            }
        }
        return f;
    }

    private byte[] intLE(int v) {
        return new byte[]{(byte)(v & 0xFF), (byte)((v >> 8) & 0xFF), (byte)((v >> 16) & 0xFF), (byte)((v >> 24) & 0xFF)};
    }

    private byte[] shortLE(short v) {
        return new byte[]{(byte)(v & 0xFF), (byte)((v >> 8) & 0xFF)};
    }

    // Play audio feedback tones for state transitions.
    // listening=true => keep existing single beep (200ms)
    // listening=false => short double beep (two 120ms beeps with slight gap)
    private void playStateTone(boolean listening) {
        if (pipelineController == null) return;
        // Gate immediately to avoid any frames slipping through before handler executes
        pipelineController.gateInput(true);
        if (beepPlayer == null) beepPlayer = new BeepPlayer();
        // Request transient focus only if we don't already hold session focus
        requestToneFocusIfNeeded();
        if (listening) {
            // Small pre-delay, but mic is already gated
            final int delayMs = 30;
            // Slightly extend gating window to cover room echo
            final int totalGateMs = delayMs + 320; // ~0.32s total after delay
            handler.postDelayed(() -> { try { beepPlayer.playListeningBeep(); } catch (Exception ignore) {} }, delayMs);
            handler.postDelayed(() -> {
                pipelineController.gateInput(false);
                abandonToneFocus();
            }, totalGateMs);
        } else {
            // Double beep total ~0.4s; add extra tail for safety
            final int releaseMs = 460;
            try {
                beepPlayer.playIdleDoubleBeep(() -> {
                    handler.postDelayed(() -> {
                        pipelineController.gateInput(false);
                        abandonToneFocus();
                    }, 60);
                });
            } catch (Exception e) {
                handler.postDelayed(() -> {
                    pipelineController.gateInput(false);
                    abandonToneFocus();
                }, releaseMs);
            }
        }
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