package com.whispertflite;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class AssetUtils {
    private static final String TAG = "AssetUtils";
    private final Context context;
    
    public AssetUtils(Context context) {
        this.context = context;
    }
    
    public String copyAssetToCache(String assetName) throws IOException {
        File cacheFile = new File(context.getCacheDir(), assetName);
        
        // If the file already exists in cache, return its path
        if (cacheFile.exists()) {
            return cacheFile.getAbsolutePath();
        }
        
        // Copy the asset to the cache
        try (InputStream in = context.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(cacheFile)) {
                 
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            
            return cacheFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy asset to cache: " + assetName, e);
            throw e;
        }
    }
}
