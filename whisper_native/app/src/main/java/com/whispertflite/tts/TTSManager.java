package com.whispertflite.tts;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class TTSManager {
    public interface Listener {
        void onTtsStart(String utteranceId);
        void onTtsDone(String utteranceId);
        void onTtsError(String utteranceId, String message);
    }

    public enum Policy {
        FLUSH,        // always flush queue
        QUEUE,        // always queue
        FLUSH_IF_SPEAKING // flush only if currently speaking
    }

    private static final String TAG = "TTSManager";

    private final Context appContext;
    private final Listener listener;
    private TextToSpeech tts;
    private boolean ready = false;
    private boolean enabled = true;
    private Policy policy = Policy.FLUSH;
    private String preferredLocaleTag = "en-US"; // default preference

    // Keep a small pending utterance if speak is called before ready
    private String pendingText;
    private String pendingId;

    public TTSManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        init();
    }

    private void init() {
        try {
            tts = new TextToSpeech(appContext, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    configureLanguage();
                    setProgressListener();
                    ready = true;
                    if (pendingText != null) {
                        internalSpeak(pendingText, pendingId, policy);
                        pendingText = null;
                        pendingId = null;
                    }
                } else {
                    Log.d(TAG, "TTS init failed: status=" + status);
                }
            });
        } catch (Throwable t) {
            Log.d(TAG, "TTS init exception: " + t.getMessage());
        }
    }

    private void configureLanguage() {
        try {
            // Prefer en-US if available, else any English, else system default
            Locale target = Locale.forLanguageTag(preferredLocaleTag);
            int res = tts.setLanguage(target);
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Try generic English
                res = tts.setLanguage(Locale.ENGLISH);
            }
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to default locale
                tts.setLanguage(Locale.getDefault());
            }
        } catch (Throwable ignore) {}
    }

    private void setProgressListener() {
        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    if (listener != null) listener.onTtsStart(utteranceId);
                }
                @Override public void onDone(String utteranceId) {
                    if (listener != null) listener.onTtsDone(utteranceId);
                }
                @Override public void onError(String utteranceId) {
                    if (listener != null) listener.onTtsError(utteranceId, "onError");
                }
                @Override public void onError(String utteranceId, int errorCode) {
                    if (listener != null) listener.onTtsError(utteranceId, "code=" + errorCode);
                }
            });
        } catch (Throwable t) {
            Log.d(TAG, "Failed to set TTS progress listener: " + t.getMessage());
        }
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setPolicy(Policy policy) { if (policy != null) this.policy = policy; }
    public void setPreferredLocaleTag(String tag) { if (tag != null) this.preferredLocaleTag = tag; }

    public boolean isSpeaking() {
        try { return tts != null && tts.isSpeaking(); } catch (Throwable ignore) { return false; }
    }

    public void speak(String text) {
        speak(text, this.policy);
    }

    public void speak(String text, Policy policy) {
        if (!enabled || text == null || text.isEmpty()) return;
        String id = "utt-" + System.currentTimeMillis();
        if (!ready) {
            // Queue one pending; last wins
            pendingText = text;
            pendingId = id;
            return;
        }
        internalSpeak(text, id, policy);
    }

    private void internalSpeak(String text, String id, Policy policy) {
        if (tts == null) return;
        try {
            int queueMode;
            if (policy == Policy.FLUSH) {
                queueMode = TextToSpeech.QUEUE_FLUSH;
            } else if (policy == Policy.FLUSH_IF_SPEAKING && tts.isSpeaking()) {
                queueMode = TextToSpeech.QUEUE_FLUSH;
            } else {
                queueMode = TextToSpeech.QUEUE_ADD;
            }

            if (Build.VERSION.SDK_INT >= 21) {
                Bundle params = new Bundle();
                // Ensure it uses music stream for consistency with earcons/media session
                params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC);
                tts.speak(text, queueMode, params, id);
            } else {
                // Deprecated API support
                Map<String, String> params = new HashMap<>();
                params.put(TextToSpeech.Engine.KEY_PARAM_STREAM, String.valueOf(android.media.AudioManager.STREAM_MUSIC));
                tts.speak(text, queueMode, params);
            }
        } catch (Throwable t) {
            Log.d(TAG, "speak failed: " + t.getMessage());
        }
    }

    public void stop() {
        try { if (tts != null) tts.stop(); } catch (Throwable ignore) {}
    }

    public void shutdown() {
        try { if (tts != null) { tts.shutdown(); tts = null; } } catch (Throwable ignore) {}
        ready = false;
    }
}
