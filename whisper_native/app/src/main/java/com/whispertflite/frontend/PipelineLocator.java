package com.whispertflite.frontend;

import java.lang.ref.WeakReference;

public final class PipelineLocator {
    private static WeakReference<PipelineController> ref = new WeakReference<>(null);

    private PipelineLocator() {}

    public static void set(PipelineController pc) {
        ref = new WeakReference<>(pc);
    }

    public static PipelineController get() {
        return ref.get();
    }
}
