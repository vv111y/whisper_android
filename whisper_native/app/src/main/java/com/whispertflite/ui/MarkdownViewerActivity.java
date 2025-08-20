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
 * Lightweight in-app Markdown viewer for docs/DIAGNOSTICS.md.
 * Implements a small subset: headings, paragraphs, unordered lists, code fences.
 * Falls back to direct HTML rendering if the asset already contains an HTML document.
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
        String html = loadMarkdownAsset(path);
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private String loadMarkdownAsset(String path) {
        String content = readAsset(path);
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html")) {
            return content; // already HTML
        }
        return renderMarkdownToHtml(content);
    }

    private String readAsset(String path) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getAssets().open(path);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Throwable t) {
            return "<html><body><pre>Unable to load asset: " + escape(t.getMessage()) + "</pre></body></html>";
        }
        return sb.toString();
    }

    private String renderMarkdownToHtml(String md) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        html.append("<style>body{font-family:sans-serif;padding:14px;line-height:1.5} h1{font-size:22px} h2{font-size:20px} h3{font-size:18px} pre{background:#f6f8fa;padding:10px;border-radius:6px;overflow:auto} code{font-family:monospace;background:#f6f8fa;padding:2px 4px;border-radius:4px} ul{padding-left:22px} p{margin:10px 0}</style>");
        html.append("</head><body>");

        boolean inCode = false;
        boolean inUl = false;
        String[] lines = md.split("\n");
        for (String raw : lines) {
            String line = raw;
            if (line.startsWith("```") ) {
                if (!inCode) {
                    // open code block
                    html.append("<pre><code>");
                    inCode = true;
                } else {
                    html.append("</code></pre>");
                    inCode = false;
                }
                continue;
            }
            if (inCode) {
                html.append(escape(line)).append("\n");
                continue;
            }
            if (line.trim().isEmpty()) {
                if (inUl) { html.append("</ul>"); inUl = false; }
                html.append("\n");
                continue;
            }
            // headings
            if (line.startsWith("#")) {
                int level = 0; while (level < line.length() && line.charAt(level) == '#') level++;
                level = Math.max(1, Math.min(6, level));
                String text = line.substring(level).trim();
                if (inUl) { html.append("</ul>"); inUl = false; }
                html.append("<h").append(level).append(">")
                        .append(escape(text)).append("</h").append(level).append(">");
                continue;
            }
            // unordered list
            String t = line.trim();
            if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) {
                if (!inUl) { html.append("<ul>"); inUl = true; }
                String item = t.substring(2).trim();
                html.append("<li>").append(escape(item)).append("</li>");
                continue;
            }
            // paragraph
            if (inUl) { html.append("</ul>"); inUl = false; }
            html.append("<p>").append(escape(line)).append("</p>");
        }
        if (inCode) html.append("</code></pre>");
        if (inUl) html.append("</ul>");
        html.append("</body></html>");
        return html.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
