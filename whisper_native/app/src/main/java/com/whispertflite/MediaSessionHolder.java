package com.whispertflite;

import android.media.session.MediaSession;

public final class MediaSessionHolder {
    private static MediaSession frameworkSession;

    private MediaSessionHolder() {}

    public static synchronized void set(MediaSession session) {
        frameworkSession = session;
    }

    public static synchronized MediaSession get() { return frameworkSession; }
}
