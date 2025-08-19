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
import com.whispertflite.frontend.BasicVad;
import com.whispertflite.frontend.VadEnergy;
import com.whispertflite.frontend.VadSilero;
import com.whispertflite.frontend.WakewordDetector;
import com.whispertflite.frontend.VadFactory;
import com.whispertflite.audio.BeepPlayer;
import com.whispertflite.engine.WhisperEngineNative;
import com.google.android.material.navigation.NavigationView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.whispertflite.ui.StartFragment;
import com.whispertflite.ui.ChatFragment;
import com.whispertflite.tts.TTSManager;

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
    private Button btnSessionPauseResume; // new
    private android.widget.CheckBox chkCaptureMedia; // new

    private Player mPlayer = null;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private FrameEmitter frameEmitter; // new
    private BasicVad vadEnergy; // basic VAD abstraction (energy default)
    private String currentVadEngine = "energy"; // energy | webrtc | silero
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
    private boolean suppressNextListenTone = false;
    private TTSManager ttsManager; // system TTS
    // Deferred start if mic permission is requested mid-flow
    private boolean pendingStartListen = false;
    private String pendingListenMode = "session";
    private boolean mediaKeysObserved = false; // for conditional fallback
    private Runnable fallbackClaimTask; // schedule AudioTrack fallback if needed
    private Runnable inactivityReleaseTask; // release silent claim after idle
    private final long fallbackDelayMs = 1200; // wait for keys before fallback
    private final long inactivityTimeoutMs = 15_000; // stop fallback after idle
    private boolean normalizeBeforeTranscribe = true; // settings-driven
    // Post‑TTS action handling: after speaking, play ready beep then run action
    private enum PostTtsAction { NONE, START_RECORDING, START_SESSION_LISTENING }
    private PostTtsAction postTtsAction = PostTtsAction.NONE;
    // Router confirmation state
    private enum PendingAction { NONE, START_CHAT, START_RECORD }
    private PendingAction pendingAction = PendingAction.NONE;

    private File sdcardDataFolder = null;
    private File selectedWaveFile = null;
    private File selectedTfliteFile = null;

    private long startTime = 0;
    private final boolean loopTesting = false;
    private final SharedResource transcriptionSync = new SharedResource();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sp, key) -> {
    if ("pref_listen_mode".equals(key)) {
            // On mode change, go to IDLE and wait for explicit user action (app bar mic)
            try {
                handler.post(() -> {
                    if (pipelineController != null) {
                        // Pause listening and show IDLE in UI
                        pipelineController.pauseListening();
                    }
                    // Stop frame emitter to save power until user restarts
                    if (frameEmitter != null && frameEmitter.isRunning()) frameEmitter.stop();
                    // Release media session focus
                    updatePlaybackState(PlaybackState.STATE_PAUSED);
                    try { if (mediaSession != null) mediaSession.setActive(false); } catch (Throwable ignore2) {}
                    abandonAudioFocus();
                    // Refresh app bar icon
                    try { invalidateOptionsMenu(); } catch (Throwable ignore3) {}
                });
            } catch (Throwable ignore) {}
        }
        if ("pref_vad_engine".equals(key)) {
            // On engine change, pause/idle to avoid live swaps; rebuild on next start
            try {
                handler.post(() -> {
                    if (pipelineController != null) pipelineController.pauseListening();
                    if (frameEmitter != null && frameEmitter.isRunning()) frameEmitter.stop();
                    updatePlaybackState(PlaybackState.STATE_PAUSED);
                    try { if (mediaSession != null) mediaSession.setActive(false); } catch (Throwable ignore2) {}
                    abandonAudioFocus();
                    try { invalidateOptionsMenu(); } catch (Throwable ignore3) {}
                    // Release resources from current VAD instance
                    if (vadEnergy instanceof com.whispertflite.frontend.VadWebRtcNative) {
                        ((com.whispertflite.frontend.VadWebRtcNative) vadEnergy).release();
                    } else if (vadEnergy instanceof com.whispertflite.frontend.VadSilero) {
                        ((com.whispertflite.frontend.VadSilero) vadEnergy).release();
                    }
                    vadEnergy = null;
                });
            } catch (Throwable ignore) {}
        }
        if ("pref_vad_webrtc_impl".equals(key)) {
            // Switching between Simple vs Native requires VAD rebuild; do the same safe pause.
            try {
                handler.post(() -> {
                    if (pipelineController != null) pipelineController.pauseListening();
                    if (frameEmitter != null && frameEmitter.isRunning()) frameEmitter.stop();
                    updatePlaybackState(PlaybackState.STATE_PAUSED);
                    try { if (mediaSession != null) mediaSession.setActive(false); } catch (Throwable ignore2) {}
                    abandonAudioFocus();
                    try { invalidateOptionsMenu(); } catch (Throwable ignore3) {}
                    // Release WebRTC native resources if applicable
                    if (vadEnergy instanceof com.whispertflite.frontend.VadWebRtcNative) {
                        ((com.whispertflite.frontend.VadWebRtcNative) vadEnergy).release();
                    }
                    vadEnergy = null;
                });
            } catch (Throwable ignore) {}
        }
    if ("pref_vad_webrtc_mode".equals(key)) {
            try {
                String engine = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getString("pref_vad_engine", "energy");
                String impl = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getString("pref_vad_webrtc_impl", "simple");
        if ("webrtc".equals(engine) && "native".equals(impl) && vadEnergy instanceof com.whispertflite.frontend.VadWebRtcNative) {
            String modeStr = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getString("pref_vad_webrtc_mode", "2");
            int mode = 2;
            try { mode = Integer.parseInt(modeStr); } catch (Throwable ignore) {}
                    ((com.whispertflite.frontend.VadWebRtcNative) vadEnergy).setAggressiveness(mode);
                }
            } catch (Throwable ignore) {}
        }
    };

    private void ensureVadEngineInitialized() {
        try {
            android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
            // Reuse existing instance if compatible; otherwise create fresh via factory
            if (vadEnergy == null || !matchesPrefs(vadEnergy, prefs)) {
                // Release previous resources safely
                try {
                    if (vadEnergy instanceof com.whispertflite.frontend.VadWebRtcNative)
                        ((com.whispertflite.frontend.VadWebRtcNative) vadEnergy).release();
                    if (vadEnergy instanceof com.whispertflite.frontend.VadSilero)
                        ((com.whispertflite.frontend.VadSilero) vadEnergy).release();
                } catch (Throwable ignore) {}

                vadEnergy = VadFactory.create(this, prefs, new com.whispertflite.frontend.BasicVad.Listener() {
                    @Override public void onSpeechStart() {
                        Log.d(TAG, "VAD speech start (" + prefs.getString("pref_vad_engine", "energy") + ")");
                        if (finalizeRunnable != null) handler.removeCallbacks(finalizeRunnable);
                        pipelineController.onSpeechStart(0);
                    }
                    @Override public void onSpeechEnd() {
                        Log.d(TAG, "VAD speech end (" + prefs.getString("pref_vad_engine", "energy") + ")");
                        if (finalizeRunnable != null) handler.removeCallbacks(finalizeRunnable);
                        finalizeRunnable = () -> pipelineController.onSpeechEnd();
                        handler.postDelayed(finalizeRunnable, finalizeDelayMs);
                    }
                    @Override public void onFrameAccepted(float[] frame, boolean speech) {
                        if (pipelineController.getState() == com.whispertflite.frontend.PipelineController.State.LISTENING && speech && wakewordDetector != null)
                            wakewordDetector.acceptFrame(frame, true);
                        pipelineController.onFrame(frame, speech);
                    }
                });
            }
        } catch (Throwable t) {
            if (vadEnergy == null) vadEnergy = buildEnergyVad();
        }
    }

    private boolean matchesPrefs(com.whispertflite.frontend.BasicVad vad, android.content.SharedPreferences prefs) {
        String engine = prefs.getString("pref_vad_engine", "energy");
        if ("silero".equals(engine)) return vad instanceof com.whispertflite.frontend.VadSilero;
        if ("webrtc".equals(engine)) {
            String impl = prefs.getString("pref_vad_webrtc_impl", "simple");
            if ("native".equals(impl)) return vad instanceof com.whispertflite.frontend.VadWebRtcNative;
            return vad instanceof com.whispertflite.frontend.VadWebRtcSimple;
        }
        return vad instanceof com.whispertflite.frontend.VadEnergy;
    }

    private BasicVad buildEnergyVad() {
    return new VadEnergy(0.035f, 30, new VadEnergy.Listener() {
            @Override public void onSpeechStart() {
                Log.d(TAG, "VAD speech start");
                if (finalizeRunnable != null) handler.removeCallbacks(finalizeRunnable);
        pipelineController.onSpeechStart(0);
            }
            @Override public void onSpeechEnd() {
                Log.d(TAG, "VAD speech end");
                if (finalizeRunnable != null) handler.removeCallbacks(finalizeRunnable);
                finalizeRunnable = () -> pipelineController.onSpeechEnd();
                handler.postDelayed(finalizeRunnable, finalizeDelayMs);
            }
            @Override public void onFrameAccepted(float[] frame, boolean speech) {
                if (pipelineController.getState() == PipelineController.State.LISTENING && speech && wakewordDetector != null) wakewordDetector.acceptFrame(frame, true);
                pipelineController.onFrame(frame, speech);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    try {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefsListener);
    } catch (Throwable ignore) {}

        // Toolbar + Drawer
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.app_name, R.string.app_name);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
        NavigationView navView = findViewById(R.id.nav_view);
    navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_start) {
        showFragment(new StartFragment());
        findViewById(R.id.recorder_container).setVisibility(View.GONE);
        findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
            } else if (id == R.id.nav_chat) {
        showFragment(new ChatFragment());
        findViewById(R.id.recorder_container).setVisibility(View.GONE);
        findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
            } else if (id == R.id.nav_recorder) {
                clearFragment();
        findViewById(R.id.fragment_container).setVisibility(View.GONE);
        findViewById(R.id.recorder_container).setVisibility(View.VISIBLE);
            }
            drawerLayout.closeDrawers();
            return true;
        });
        View header = navView.getHeaderView(0);
        View btnSettings = header.findViewById(R.id.btnSettings);
        if (btnSettings != null) btnSettings.setOnClickListener(v -> openSettings());

        // Call the method to copy specific file types from assets to data folder
        sdcardDataFolder = this.getExternalFilesDir(null);
        copyAssetsToSdcard(this, sdcardDataFolder, EXTENSIONS_TO_COPY);

        ArrayList<File> tfliteFiles = getFilesWithExtension(sdcardDataFolder, ".tflite");
        ArrayList<File> waveFiles = getFilesWithExtension(sdcardDataFolder, ".wav");

    // Initialize default model to use (can be overridden by Settings)
    selectedTfliteFile = new File(sdcardDataFolder, DEFAULT_MODEL_TO_USE);

    // Model selection moved to Settings

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

    // Ensure TTS is ready for spoken feedback and routing prompts
    ensureTts();

    btnWakeListenStart = findViewById(R.id.btnWakeListenStart);
    btnWakeListenStop = findViewById(R.id.btnWakeListenStop);
    btnSessionStart = findViewById(R.id.btnSessionStart);
    btnSessionStop = findViewById(R.id.btnSessionStop);
    btnSessionPauseResume = findViewById(R.id.btnSessionPauseResume);
    chkCaptureMedia = findViewById(R.id.chkCaptureMedia);
        chkCaptureMedia.setOnCheckedChangeListener((android.widget.CompoundButton buttonView, boolean isChecked) -> {
            try {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putBoolean("pref_capture_media", isChecked).apply();
            } catch (Throwable ignore) {}
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
                // Keep app bar icon in sync with state transitions
                handler.post(() -> { try { invalidateOptionsMenu(); } catch (Throwable ignore) {} });
                // Audio feedback on state transitions: LISTENING and IDLE
                if (state == PipelineController.State.LISTENING) {
                    if (suppressNextListenTone) {
                        suppressNextListenTone = false;
                    } else {
                        playStateTone(true);
                    }
                } else if (state == PipelineController.State.IDLE) {
                    playStateTone(false);
                }
            }
            @Override public void onWakeTriggered(double score) { Log.d(TAG, "Pipeline wake triggered score=" + score); }
        @Override public void onUtteranceReady(float[] samples) {
                // Write temp WAV to reuse existing file transcription path
                try {
                    // Light normalization: center and peak-normalize to -1..1 with a cap
            float[] norm = normalizeBeforeTranscribe ? normalizeAudio(samples) : samples;
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
    ensureVadEngineInitialized();
        frameEmitter.setListener(new FrameEmitter.Listener() {
            @Override public void onFrame(float[] pcmFrame) {
                // Upstream gating: avoid feeding VAD while output is playing (no barge-in)
                if (pipelineController != null && pipelineController.isInputGated()) return;
                if (vadEnergy == null) return; // engine possibly being swapped; ignore frame
                vadEnergy.accept(pcmFrame);
            }
            @Override public void onError(String msg) { Log.d(TAG, "FrameEmitter error: " + msg); }
        });
        btnWakeListenStart.setOnClickListener(v -> {
            ensureVadEngineInitialized();
            if (!frameEmitter.isRunning()) frameEmitter.start();
            pipelineController.startListening();
            applyProfile(false); // command-style listen (no chat capture)
            // Play ready earcon explicitly and suppress state-driven duplicate
            suppressNextListenTone = true;
            playStateTone(true);
        });
        btnWakeListenStop.setOnClickListener(v -> {
            if (frameEmitter.isRunning()) frameEmitter.stop();
            pipelineController.stop();
        });

        // Session listen wiring
        btnSessionStart.setOnClickListener(v -> {
            ensureVadEngineInitialized();
            if (!frameEmitter.isRunning()) frameEmitter.start();
            pipelineController.startSession();
            applyProfile(false); // command defaults by default
            // Play ready earcon explicitly and suppress state-driven duplicate
            suppressNextListenTone = true;
            playStateTone(true);
            // If using WebRTC native, relax required pre-speech silence slightly
            try {
                android.content.SharedPreferences sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
                String eng = sp.getString("pref_vad_engine", "energy");
                String impl = sp.getString("pref_vad_webrtc_impl", "simple");
                if ("webrtc".equals(eng) && "native".equals(impl)) {
                    pipelineController.setRequiredSilenceFramesBeforeCapture(3);
                } else {
                    pipelineController.setRequiredSilenceFramesBeforeCapture(6);
                }
            } catch (Throwable ignore) {}
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
            if (ttsManager != null) ttsManager.stop();
            try {
                android.content.Intent svc = new android.content.Intent(this, SessionService.class);
                stopService(svc);
            } catch (Throwable t) { Log.d(TAG, "Failed to stop foreground service: " + t.getMessage()); }
            if (fallbackClaimTask != null) handler.removeCallbacks(fallbackClaimTask);
            if (inactivityReleaseTask != null) handler.removeCallbacks(inactivityReleaseTask);
        });

    // Model validation and VAD tuning moved to Settings

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
                        // Double-tap to stop TTS if enabled; else toggle listening
                        if (shouldStopTtsOnDoubleTap() && isDoubleTap(keyEvent)) {
                            if (ttsManager != null && ttsManager.isSpeaking()) {
                                ttsManager.stop();
                                return true;
                            }
                        }
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
                        if (shouldStopTtsOnDoubleTap() && ttsManager != null && ttsManager.isSpeaking()) {
                            ttsManager.stop();
                            return true;
                        }
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
                // Ensure TTS exists for any prompts
                ensureTts();
            }

            // No onPlayPause in Callback; handled via onMediaButtonEvent above
        });
    // Prepare session; activation controlled by session start/stop
    updatePlaybackState(PlaybackState.STATE_PAUSED);

    // Proactively request mic permission once
    checkRecordPermission();

        // for debugging
//        testParallelProcessing();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_appbar, menu);
        syncSessionMenuIcon(menu.findItem(R.id.action_session_toggle));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_session_toggle) {
            handleSessionToggleFromAppBar();
            syncSessionMenuIcon(item);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void handleSessionToggleFromAppBar() {
        if (pipelineController == null) return;
        PipelineController.State st = pipelineController.getState();
        if (st == PipelineController.State.LISTENING) {
            // Pause
            pipelineController.pauseListening();
            updatePlaybackState(PlaybackState.STATE_PAUSED);
            if (btnSessionPauseResume != null) btnSessionPauseResume.setText("Resume");
        } else if (st == PipelineController.State.IDLE) {
            // Start according to selected listening mode
            // Ensure audio frames are coming
            if (!frameEmitter.isRunning()) frameEmitter.start();
            applyListenModeAndStart();
            if (btnSessionPauseResume != null) btnSessionPauseResume.setText("Pause");
        } else if (st == PipelineController.State.CAPTURING || st == PipelineController.State.TRANSCRIBING) {
            // Stop the session entirely
            if (frameEmitter.isRunning()) frameEmitter.stop();
            pipelineController.stopSession();
            updatePlaybackState(PlaybackState.STATE_STOPPED);
            mediaSession.setActive(false);
            abandonAudioFocus();
            try {
                android.content.Intent svc = new android.content.Intent(this, SessionService.class);
                stopService(svc);
            } catch (Throwable ignore) {}
        }
    }

    private void syncSessionMenuIcon(MenuItem item) {
        if (item == null || pipelineController == null) return;
        PipelineController.State st = pipelineController.getState();
        if (st == PipelineController.State.IDLE) {
            item.setIcon(R.drawable.ic_mic);
        } else if (st == PipelineController.State.LISTENING) {
            item.setIcon(R.drawable.ic_pause_listen);
        } else {
            item.setIcon(R.drawable.ic_stop_listen);
        }
    }

    private void applyProfile(boolean chatMode) {
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        String p = chatMode ? "chat_" : "cmd_";
        // VAD
        if (vadEnergy != null) {
            // Threshold is stored as 1..100 slider position mapping; keep same scale
            int thrProg = prefs.getInt(p + "vad_threshold", prefs.getInt("pref_vad_threshold", 35));
            float thr = 0.005f + (thrProg / 100f) * (0.1f - 0.005f);
            vadEnergy.setThreshold(thr);
            vadEnergy.setHangoverFrames(prefs.getInt(p + "vad_hangover", prefs.getInt("pref_vad_hangover", 30)));
            vadEnergy.setStartAttackFrames(prefs.getInt(p + "vad_attack", prefs.getInt("pref_vad_attack", 3)));
        }
        // Pipeline
        if (pipelineController != null) {
            pipelineController.setPreRollFrames(prefs.getInt(p + "pre_roll_frames", prefs.getInt("pref_pre_roll_frames", 18)));
            pipelineController.setInCaptureSilenceFrames(prefs.getInt(p + "incap_silence_frames", prefs.getInt("pref_incap_silence_frames", 35)));
            pipelineController.setMinArmDelayMs(prefs.getInt(p + "min_arm_delay_ms", prefs.getInt("pref_min_arm_delay_ms", 600)));
            pipelineController.setInterUtteranceCooldownMs(prefs.getInt(p + "inter_cooldown_ms", prefs.getInt("pref_inter_cooldown_ms", 800)));
            pipelineController.setMinUtteranceFrames(prefs.getInt(p + "min_utter_frames", prefs.getInt("pref_min_utter_frames", 18)));
            pipelineController.setMaxCaptureMs(prefs.getInt(p + "max_capture_ms", prefs.getInt("pref_max_capture_ms", 12_000)));
        }
    }

    private void showFragment(Fragment f) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.fragment_container, f);
        ft.commitAllowingStateLoss();
    }

    private void clearFragment() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (current != null) {
            getSupportFragmentManager().beginTransaction().remove(current).commitAllowingStateLoss();
        }
    }

    private void openSettings() {
        startActivity(new android.content.Intent(this, com.whispertflite.ui.SettingsActivity.class));
    }

    private void ensureTts() {
        if (ttsManager != null) return;
        try {
            ttsManager = new TTSManager(MainActivity.this, new TTSManager.Listener() {
                @Override public void onTtsStart(String utteranceId) {
                    if (pipelineController != null) pipelineController.onOutputStart();
                }
                @Override public void onTtsDone(String utteranceId) {
                    if (pipelineController != null) pipelineController.onOutputEnd();
                    // If a post-TTS action is pending, play ready beep, then execute action
                    if (postTtsAction != PostTtsAction.NONE) {
                        PostTtsAction action = postTtsAction;
                        postTtsAction = PostTtsAction.NONE;
                        playStateTone(true);
                        // Schedule action slightly after the beep finishes to avoid capturing it
                        handler.postDelayed(() -> {
                            try {
                                if (action == PostTtsAction.START_RECORDING) {
                                    if (mRecorder != null && !mRecorder.isInProgress()) startRecording();
                                } else if (action == PostTtsAction.START_SESSION_LISTENING) {
                                    applyListenModeAndStart();
                                }
                            } catch (Throwable ignore) {}
                        }, 380);
                    }
                }
                @Override public void onTtsError(String utteranceId, String message) {
                    if (pipelineController != null) pipelineController.onOutputEnd();
                }
            });
            applyTtsPrefs();
        } catch (Throwable ignore) {}
    }

    // Called by Settings to immediately apply a new selected model
    public void onModelPreferenceChanged(String newPath) {
        try {
            if (newPath == null) return;
            File f = new File(newPath);
            if (!f.exists() || !f.isFile()) return;
            if (selectedTfliteFile == null || !f.equals(selectedTfliteFile)) {
                deinitModel();
            }
            selectedTfliteFile = f;
            // Optionally, eager-initialize if a session is active and no model loaded
            if (pipelineController != null && mWhisper == null) {
                initModel(selectedTfliteFile);
            }
        } catch (Throwable t) {
            Log.d(TAG, "onModelPreferenceChanged error: " + t.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Apply preferences to components
        try {
            android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
            boolean captureMedia = prefs.getBoolean("pref_capture_media", true);
            boolean normalize = prefs.getBoolean("pref_normalize_audio", true);
            // TTS prefs
            applyTtsPrefs();
            String modelPath = prefs.getString("pref_model_file", null);
            if (modelPath != null) {
                try {
                    java.io.File f = new java.io.File(modelPath);
                    if (f.exists() && f.isFile()) {
                        // deinit if changed
                        if (selectedTfliteFile == null || !f.equals(selectedTfliteFile)) {
                            deinitModel();
                        }
                        selectedTfliteFile = f;
                    }
                } catch (Throwable ignore) {}
            }
            // Apply media capture toggle to checkbox and current session behavior
            if (chkCaptureMedia != null) chkCaptureMedia.setChecked(captureMedia);
            // VAD mappings
            if (vadEnergy != null) {
                int thrProg = prefs.getInt("pref_vad_threshold", 35);
                float thr = 0.005f + (thrProg / 100f) * (0.1f - 0.005f);
                vadEnergy.setThreshold(thr);
                vadEnergy.setHangoverFrames(prefs.getInt("pref_vad_hangover", 30));
                vadEnergy.setStartAttackFrames(prefs.getInt("pref_vad_attack", 3));
            }
            // Pipeline tunables
            if (pipelineController != null) {
                pipelineController.setPreRollFrames(prefs.getInt("pref_pre_roll_frames", 18));
                pipelineController.setInCaptureSilenceFrames(prefs.getInt("pref_incap_silence_frames", 35));
                pipelineController.setMinArmDelayMs(prefs.getInt("pref_min_arm_delay_ms", 600));
                pipelineController.setInterUtteranceCooldownMs(prefs.getInt("pref_inter_cooldown_ms", 800));
                pipelineController.setMinUtteranceFrames(prefs.getInt("pref_min_utter_frames", 18));
                pipelineController.setMaxCaptureMs(prefs.getInt("pref_max_capture_ms", 12_000));
            }
            // Store normalize flag for future use; current implementation always normalizes before WAV write
            this.normalizeBeforeTranscribe = normalize;
        } catch (Throwable ignore) {}
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

    // Removed legacy showVadTuningDialog(); VAD is tuned via Settings now.

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
    try { if (ttsManager != null) { ttsManager.shutdown(); ttsManager = null; } } catch (Exception ignore) {}
    try {
        // Release VAD resources
        if (vadEnergy instanceof com.whispertflite.frontend.VadWebRtcNative) {
            ((com.whispertflite.frontend.VadWebRtcNative) vadEnergy).release();
        } else if (vadEnergy instanceof com.whispertflite.frontend.VadSilero) {
            ((com.whispertflite.frontend.VadSilero) vadEnergy).release();
        }
        vadEnergy = null;
    } catch (Exception ignore) {}
    try {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefsListener);
    } catch (Throwable ignore) {}
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
                    // Voice router: confirm commands and act after yes/no
                    if (handleRouterFromTranscript(result)) {
                        return; // handled by router (feedback already given)
                    }
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

    private boolean hasRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return permission == PackageManager.PERMISSION_GRANTED;
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
            if (pendingStartListen) {
                pendingStartListen = false;
                String mode = pendingListenMode;
                if (!frameEmitter.isRunning()) frameEmitter.start();
                if ("session".equals(mode)) startSessionListening(); else startWakeListening();
            }
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

    // --- Listening mode helpers (app bar + Settings) ---
    private String currentListenModePref() {
        try {
            return androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("pref_listen_mode", "session");
        } catch (Throwable t) { return "wake"; }
    }
    private void startWakeListening() {
    ensureVadEngineInitialized();
        if (!frameEmitter.isRunning()) frameEmitter.start();
        if (pipelineController != null) {
            pipelineController.stopSession(); // ensure wake mode
            pipelineController.startListening();
            applyProfile(false); // use command-style defaults for wake
        }
        updatePlaybackState(PlaybackState.STATE_PAUSED);
        mediaSession.setActive(false);
        abandonAudioFocus();
    }
    private void startSessionListening() {
    ensureVadEngineInitialized();
        if (!frameEmitter.isRunning()) frameEmitter.start();
        if (pipelineController != null) {
            pipelineController.startSession();
            applyProfile(false); // command defaults initially; user can switch to chat mode separately
        }
        if (chkCaptureMedia.isChecked()) {
            if (requestAudioFocus()) mediaSession.setActive(true);
            updatePlaybackState(PlaybackState.STATE_PLAYING);
        } else {
            updatePlaybackState(PlaybackState.STATE_PAUSED);
            mediaSession.setActive(false);
            abandonAudioFocus();
        }
    }
    public void applyListenModeAndStart() {
        String m = currentListenModePref();
        // Ensure permission; if not, defer action until granted
        if (!hasRecordPermission()) {
            pendingStartListen = true;
            pendingListenMode = m;
            checkRecordPermission();
            return;
        }
        if ("session".equals(m)) startSessionListening(); else startWakeListening();
    }

    // --- Router parsing and actions ---
    private boolean handleRouterFromTranscript(String raw) {
        if (raw == null) return false;
        String text = normalizeText(raw);
        if (pendingAction == PendingAction.NONE) {
            if (text.equals("start new chat")) {
                pendingAction = PendingAction.START_CHAT;
                ttsConfirmStartNewChat();
                return true;
            } else if (text.equals("start new recording")) {
                pendingAction = PendingAction.START_RECORD;
                ttsConfirmStartNewRecording();
                return true;
            }
            return false;
        } else {
            // Expect yes/no
            if (text.equals("yes")) {
                if (pendingAction == PendingAction.START_CHAT) {
                    ttsAnnounceChatStarted();
                    navigateToChat();
                    // TODO: execute app command to start a fresh chat session
                } else if (pendingAction == PendingAction.START_RECORD) {
                    ttsAnnounceRecordingStarting();
                    navigateToRecorder();
                    // TODO: execute app command to start a new recording after beep
                }
                pendingAction = PendingAction.NONE;
                return true;
            } else if (text.equals("no")) {
                // Cancel softly with a dud earcon
                earconDud();
                pendingAction = PendingAction.NONE;
                return true;
            } else {
                // Not understood: dud earcon only (quick feedback)
                earconDud();
                return true;
            }
        }
    }
    private String normalizeText(String s) {
        String t = s.toLowerCase();
        t = t.replaceAll("[^a-z0-9 ]", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private void navigateToChat() {
        try {
            showFragment(new com.whispertflite.ui.ChatFragment());
            findViewById(R.id.recorder_container).setVisibility(View.GONE);
            findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
        } catch (Throwable ignore) {}
    }
    private void navigateToRecorder() {
        try {
            clearFragment();
            findViewById(R.id.fragment_container).setVisibility(View.GONE);
            findViewById(R.id.recorder_container).setVisibility(View.VISIBLE);
        } catch (Throwable ignore) {}
    }

    private void applyTtsPrefs() {
        try {
            android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
            boolean enabled = prefs.getBoolean("pref_tts_enabled", true);
            String pol = prefs.getString("pref_tts_interrupt", "flush");
            if (ttsManager != null) {
                ttsManager.setEnabled(enabled);
                if ("queue".equals(pol)) {
                    ttsManager.setPolicy(TTSManager.Policy.QUEUE);
                } else if ("flush_state".equals(pol)) {
                    ttsManager.setPolicy(TTSManager.Policy.FLUSH_IF_SPEAKING);
                } else {
                    ttsManager.setPolicy(TTSManager.Policy.FLUSH);
                }
            }
        } catch (Throwable ignore) {}
    }

    private boolean shouldStopTtsOnDoubleTap() {
        try {
            return androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean("pref_tts_double_tap_stop", true);
        } catch (Throwable t) { return true; }
    }

    // Simple double-tap detector for media button
    private long lastTapTime = 0L;
    private int lastKeyCode = -1;
    private boolean isDoubleTap(KeyEvent ev) {
        int code = ev.getKeyCode();
        long now = System.currentTimeMillis();
        boolean doubleTap = (code == lastKeyCode) && (now - lastTapTime < 450);
        lastTapTime = now;
        lastKeyCode = code;
        return doubleTap;
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

    // --- TTS feedback helpers ---
    private void ttsConfirmStartNewChat() {
        if (ttsManager == null) return;
        ttsManager.speak("start new chat?");
    }
    private void ttsConfirmStartNewRecording() {
        if (ttsManager == null) return;
        ttsManager.speak("start new recording?");
    }
    private void ttsAnnounceChatStarted() {
        if (ttsManager == null) return;
    ttsManager.speak("new chat started, go ahead");
    // After this utterance completes, play ready beep and start listening
    postTtsAction = PostTtsAction.START_SESSION_LISTENING;
    }
    private void ttsAnnounceRecordingStarting() {
        if (ttsManager == null) return;
        ttsManager.speak("new recording starting, begin speaking after beep");
    // After this utterance completes, play ready beep and start recording
    postTtsAction = PostTtsAction.START_RECORDING;
    }
    private void earconDud() {
        if (pipelineController != null) pipelineController.gateInput(true);
        requestToneFocusIfNeeded();
        if (beepPlayer == null) beepPlayer = new BeepPlayer();
        try { beepPlayer.playDud(); } catch (Exception ignore) {}
        handler.postDelayed(() -> {
            if (pipelineController != null) pipelineController.gateInput(false);
            abandonToneFocus();
        }, 220);
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