package com.whispertflite.ui;

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal in-app Markdown viewer for docs/DIAGNOSTICS.md.
 * Note: We render as simple preformatted HTML to avoid bundling a full markdown parser.
 */
public class MarkdownViewerActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView wv = new WebView(this);
        setContentView(wv);
        wv.setWebViewClient(new WebViewClient());

        String title = getIntent().getStringExtra("title");
        if (title != null) setTitle(title);

        String path = getIntent().getStringExtra("assetPath");
    String html = loadMarkdownAssetAsPre(path);
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private String loadMarkdownAssetAsPre(String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>body{font-family:sans-serif;padding:12px;white-space:pre-wrap;} pre{white-space:pre-wrap;}</style>" 
                + "</head><body>");
        sb.append("<pre>");
        try {
        InputStream is = getAssets().open(path);
            if (is != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(escape(line)).append("\n");
                }
            }
        } catch (Throwable t) {
            sb.append("Error: ").append(escape(t.getMessage()));
        }
        sb.append("</pre>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
