// Shiori: the stylesheet, the page's script and the icon, served from memory.
package com.example.shiori.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public final class Assets {

    private Assets() {
    }

    private static final String STYLESHEET = text(Assets.class.getResourceAsStream("static/app.css"));

    private static final String SCRIPT = text(Assets.class.getResourceAsStream("static/app.js"));

    private static final String FAVICON = text(Assets.class.getResourceAsStream("static/favicon.svg"));

    // The URLs carry a hash of the text, so a changed file is a new URL and the old one
    // can be cached for good.
    static final String STYLESHEET_URL = "/static/app.css?v=" + Integer.toString(textHash(STYLESHEET), 36);

    static final String SCRIPT_URL = "/static/app.js?v=" + Integer.toString(textHash(SCRIPT), 36);

    private static final String FOREVER = "public, max-age=31536000, immutable";

    public static ServerResponse stylesheet(ServerRequest request) {
        return cached(new MediaType("text", "css", StandardCharsets.UTF_8), FOREVER).body(STYLESHEET);
    }

    public static ServerResponse script(ServerRequest request) {
        return cached(new MediaType("text", "javascript", StandardCharsets.UTF_8), FOREVER).body(SCRIPT);
    }

    public static ServerResponse favicon(ServerRequest request) {
        return cached(new MediaType("image", "svg+xml"), "public, max-age=86400").body(FAVICON);
    }

    private static ServerResponse.BodyBuilder cached(MediaType type, String cacheControl) {
        return ServerResponse.ok()
            .contentType(type)
            .header("Cache-Control", cacheControl)
            .header("X-Content-Type-Options", "nosniff");
    }

    // The hash app.js computes too: over the code points of the text.
    public static int textHash(String text) {
        int hash = 0;
        for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
            hash = (hash * 31 + text.codePointAt(i)) % 1000003;
        }
        return hash;
    }

    private static String text(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
