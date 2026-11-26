// Shiori: what every HTML answer carries, and the messages that travel beside one.
package com.example.shiori.web;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.json.JsonObject;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

final class Html {

    private Html() {
    }

    private static final MediaType TYPE = new MediaType("text", "html", StandardCharsets.UTF_8);

    // What a page may load and run. No inline script and no eval: an injected <script>
    // or on* attribute does not run even if escaping were ever missed. frame-ancestors
    // keeps the pages (their delete buttons) out of other sites' frames.
    private static final String CONTENT_SECURITY_POLICY = String.join("; ", List.of("default-src 'self'",
            "script-src 'self' https://unpkg.com/htmx.org@4.0.1/",
            "style-src-elem 'self' https://fonts.googleapis.com",
            // a spine's cloth and size are custom properties of its style attribute
            "style-src-attr 'unsafe-inline'", "font-src https://fonts.gstatic.com",
            // app.css masks the paper grain with a data: SVG
            "img-src 'self' data:", "object-src 'none'", "base-uri 'none'", "form-action 'self'",
            "frame-ancestors 'none'"));

    private static final String FLASH = "shiori_flash";

    static ServerResponse.BodyBuilder status(int status) {
        return ServerResponse.status(status)
            .contentType(TYPE)
            .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "same-origin");
    }

    // An HX-Trigger raising the page's toast event. A header is ISO-8859-1, so the message
    // travels URL-encoded (as decodeURIComponent reads it) and the page decodes it. The
    // event is raised on the body: htmx raises it after the swap, by which time the form
    // that sent the request may have been swapped out.
    static String toastTrigger(String message) {
        return new JsonObject()
            .put("toast", new JsonObject().put("value", uriComponent(message)).put("target", "body"))
            .toString();
    }

    // A message the next page shows once, in a cookie.
    static String flashCookie(String message) {
        return FLASH + "=" + uriComponent(message) + "; Path=/; Max-Age=60; HttpOnly; SameSite=Lax";
    }

    static String flashCleared() {
        return FLASH + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax";
    }

    // The message the previous response left, if any.
    static String flash(ServerRequest request) {
        String cookies = request.headers().firstHeader("Cookie");
        if (cookies != null) {
            for (String pair : cookies.split(";")) {
                String trimmed = pair.strip();
                if (trimmed.startsWith(FLASH + "=") && trimmed.length() > FLASH.length() + 1) {
                    return URLDecoder.decode(trimmed.substring(FLASH.length() + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static String uriComponent(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

}
