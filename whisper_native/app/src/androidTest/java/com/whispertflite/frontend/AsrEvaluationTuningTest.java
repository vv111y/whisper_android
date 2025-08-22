package com.whispertflite.frontend;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Environment;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class AsrEvaluationTuningTest {

    @Test
    public void whenAsrTuneArgProvided_writesBestConfigJson() throws Exception {
        // Respect -Pandroid.testInstrumentationRunnerArguments.asrTune=true
        String asrTune = InstrumentationRegistry.getArguments().getString("asrTune", "false");
        if (!"true".equalsIgnoreCase(asrTune)) {
            // Not a tuning run; skip without failing CI
            return;
        }

        String manifestPath = InstrumentationRegistry.getArguments().getString("asr.manifest", "docs/data/tuning_manifest.json");
        String audioDirOverride = InstrumentationRegistry.getArguments().getString("asr.audioDir", null);

        // Load manifest JSON from the device file system if accessible; otherwise rely on placeholder
        JSONArray samples = new JSONArray();
        try {
            File maybeManifest = resolveInputPath(manifestPath);
            if (maybeManifest.exists()) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(maybeManifest))) {
                    String line;
                    while ((line = br.readLine()) != null) { sb.append(line).append('\n'); }
                }
                JSONObject manifest = new JSONObject(sb.toString());
                samples = manifest.optJSONArray("samples");
                if (samples == null) samples = new JSONArray();
            }
        } catch (Throwable t) {
            // Ignore parsing errors; we'll continue with empty samples
        }

        // TODO: Replace with real evaluation loop calling the app's ASR pipeline and scoring WER.
        // For now, emit a deterministic placeholder so wiring works end-to-end.
        JSONObject best = new JSONObject();
        best.put("version", 1);
        best.put("strategy", "placeholder");
        best.put("notes", "Replace AsrEvaluationTuningTest with real scoring; this file proves the path works.");
        best.put("searched_candidates", 1);
        best.put("samples_evaluated", samples.length());
        JSONObject candidate = new JSONObject();
        candidate.put("beam_size", 4);
        candidate.put("language", "auto");
        candidate.put("temperature", 0.0);
        best.put("best_config", candidate);

        // Write to the app's external files dir so Gradle task can adb pull it without root.
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File outDir = new File(ctx.getExternalFilesDir(null), "asr_tune");
        // Fallback if external not available
        if (outDir == null) {
            outDir = new File(ctx.getFilesDir(), "asr_tune");
        }
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        File outFile = new File(outDir, "best_config_asr.json");
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(best.toString(2).getBytes(StandardCharsets.UTF_8));
        }

        // Sanity check
        assertTrue(outFile.exists());
    }

    private static File resolveInputPath(String path) {
        // Try absolute path first
        File f = new File(path);
        if (f.isAbsolute()) return f;

        // Try external storage root
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) {
            File g = new File(ext, path);
            if (g.exists()) return g;
        }

        // Try app external files dir
        try {
            Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
            File appExt = ctx.getExternalFilesDir(null);
            if (appExt != null) {
                File h = new File(appExt, path);
                if (h.exists()) return h;
            }
        } catch (Throwable ignored) {}

        // Last resort: relative to working dir
        return new File(path);
    }
}
