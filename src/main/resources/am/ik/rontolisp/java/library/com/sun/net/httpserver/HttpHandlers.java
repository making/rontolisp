// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package com.sun.net.httpserver;

import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

public final class HttpHandlers {

    private HttpHandlers() {
    }

    public static HttpHandler handleOrElse(Predicate<Request> handlerTest, HttpHandler handler,
            HttpHandler fallbackHandler) {
        if (handlerTest == null || handler == null || fallbackHandler == null) {
            throw new NullPointerException();
        }
        return exchange -> {
            if (handlerTest.test(exchange)) {
                handler.handle(exchange);
            }
            else {
                fallbackHandler.handle(exchange);
            }
        };
    }

    public static HttpHandler of(int statusCode, Headers headers, String body) {
        if (statusCode < 100 || statusCode > 999) {
            throw new IllegalArgumentException("statusCode must be 3-digit: " + statusCode);
        }
        if (headers == null || body == null) {
            throw new NullPointerException();
        }
        Headers fields = Headers.of(headers);
        byte[] octets = body.getBytes(StandardCharsets.UTF_8);
        return exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().putAll(fields);
            if (octets.length == 0) {
                exchange.sendResponseHeaders(statusCode, -1);
            }
            else {
                exchange.sendResponseHeaders(statusCode, octets.length);
                if (!exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseBody().write(octets);
                }
            }
            exchange.close();
        };
    }
}
