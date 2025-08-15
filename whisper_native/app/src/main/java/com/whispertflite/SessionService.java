package com.whispertflite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat; // still used for channel creation on older code paths (safe)
import android.media.session.MediaSession;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class SessionService extends Service {
    public static final String CHANNEL_ID = "whisper_session_channel";
    public static final int NOTIF_ID = 1011;
    private AudioTrack loopTrack;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification notification = buildNotification();
        startForeground(NOTIF_ID, notification);
        startSilentLoop();
    try {
        android.media.session.MediaSession s = MediaSessionHolder.get();
        if (s != null) {
        s.setActive(true);
        long actions = android.media.session.PlaybackState.ACTION_PLAY
            | android.media.session.PlaybackState.ACTION_PAUSE
            | android.media.session.PlaybackState.ACTION_PLAY_PAUSE
            | android.media.session.PlaybackState.ACTION_STOP
            | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
            | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        android.media.session.PlaybackState st = new android.media.session.PlaybackState.Builder()
            .setState(android.media.session.PlaybackState.STATE_PLAYING, android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(actions)
            .build();
        s.setPlaybackState(st);
        }
    } catch (Throwable ignore) {}
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
    stopSilentLoop();
        stopForeground(true);
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Whisper Session",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
    // Action to toggle listening (no-op target, activity handles intent if needed)
    Intent toggleIntent = new Intent(this, MainActivity.class);
    PendingIntent togglePi = PendingIntent.getActivity(this, 1, toggleIntent,
        PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

    Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Listening session active")
                .setContentText("Tap to return to Whisper")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true);
        try {
            MediaSession s = MediaSessionHolder.get();
            if (s != null) {
        b.setStyle(new Notification.MediaStyle().setMediaSession(s.getSessionToken()))
            .addAction(new Notification.Action.Builder(null, "Toggle", togglePi).build());
            }
        } catch (Throwable ignore) {}
        return b.build();
    }

    private void startSilentLoop() {
        try {
            if (loopTrack != null) return;
            int sampleRate = 16000;
            int numFrames = sampleRate / 2; // 0.5s buffer
            byte[] zeros = new byte[numFrames * 2]; // 16-bit mono
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            loopTrack = new AudioTrack(attrs, fmt, zeros.length, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
            loopTrack.write(zeros, 0, zeros.length);
            // Loop from frame 0 to numFrames indefinitely
            loopTrack.setLoopPoints(0, numFrames, -1);
            loopTrack.setVolume(0f);
            loopTrack.play();
        } catch (Exception ignore) {}
    }

    private void stopSilentLoop() {
        try {
            if (loopTrack != null) {
                loopTrack.pause();
                loopTrack.flush();
                loopTrack.release();
                loopTrack = null;
            }
        } catch (Exception ignore) {}
    }
}
