// A miniature httpbin (https://httpbin.org) on the JDK's own server API,
// com.sun.net.httpserver: the Java companion of examples/net/httpbin.lisp. Five
// endpoints answer a JSON document describing the request they got:
//
//   GET    /get     -> {"args": {..}, "headers": {..}, "method": "GET", "path": "/get"}
//   POST   /post    -> the same plus "data" (the body's text) and "json" (the body
//   PUT    /put        when it is one JSON value, else null)
//   PATCH  /patch
//   DELETE /delete
//
// A wrong method answers 405, an unknown path 404. Nothing is reshaped on its way back:
// "data" is the body as it came, "json" the body itself (checked to be JSON, not parsed
// and written again), "args" the query's pairs decoded, "headers" the request's fields as
// the server hands them (com.sun.net.httpserver keeps a name's first letter upper and the
// rest lower). The fields the program writes are in alphabetical order, as httpbin's are.
// There is no JSON library: the JDK has no JSON API either, so the same source runs on a
// JDK (`java examples/java/httpbin/Httpbin.java`).
//
// One source for every transport; the port is the PORT environment variable's, 8080
// when it is not set:
//
//   java -jar $JAR examples/java/httpbin/Httpbin.java                                    # interpreter
//   java -jar $JAR examples/java/httpbin/Httpbin.java -o Httpbin.class && java Httpbin   # JVM
//   java -jar $JAR examples/java/httpbin/Httpbin.java -o httpbin --native && ./httpbin   # native executable
//
// and a wasi:http component under `wasmtime serve`, where the host owns the port and
// starts the program for the requests it likes:
//
//   java -jar $JAR examples/java/httpbin/Httpbin.java -o httpbin.wasm --component
//   wasmtime serve -S cli=y --addr 127.0.0.1:8080 httpbin.wasm
//
// Talk to it with:
//
//   curl 'http://localhost:8080/get?a=1&b=two'
//   curl --json '{"name": "rontolisp"}' http://localhost:8080/post
package httpbin;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Httpbin {

    // --- routing -------------------------------------------------------------

    /** The method a path answers, or null for a path of no endpoint. */
    static String methodOf(String path) {
        return switch (path) {
            case "/get" -> "GET";
            case "/post" -> "POST";
            case "/put" -> "PUT";
            case "/patch" -> "PATCH";
            case "/delete" -> "DELETE";
            default -> null;
        };
    }

    static void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = methodOf(path);
        if (method == null) {
            send(exchange, 404, new Json().put("error", "not found").put("path", path));
        }
        else if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            send(exchange, 405, new Json().put("allowed", method).put("error", "method not allowed"));
        }
        else {
            send(exchange, 200, describe(exchange, path, !method.equals("GET")));
        }
    }

    // --- the request, described ----------------------------------------------

    static Json describe(HttpExchange exchange, String path, boolean withBody) throws IOException {
        Json info = new Json().put("args", args(exchange.getRequestURI().getRawQuery()));
        String data = null;
        if (withBody) {
            data = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            info.put("data", data);
        }
        info.put("headers", headers(exchange.getRequestHeaders()));
        if (withBody) {
            info.put("json", JsonCheck.value(data));
        }
        return info.put("method", exchange.getRequestMethod()).put("path", path);
    }

    /** The query's pairs by name; a name given more than once has an array of its values. */
    static Json args(String rawQuery) {
        Map<String, List<String>> values = new HashMap<>();
        List<String> names = new ArrayList<>();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String name = decode(equals < 0 ? pair : pair.substring(0, equals));
                if (!values.containsKey(name)) {
                    names.add(name);
                    values.put(name, new ArrayList<>());
                }
                values.get(name).add(equals < 0 ? "" : decode(pair.substring(equals + 1)));
            }
        }
        Collections.sort(names);
        Json args = new Json();
        for (String name : names) {
            List<String> given = values.get(name);
            args.put(name, given.size() == 1 ? given.get(0) : given);
        }
        return args;
    }

    /** A part of a query decoded: '+' is a space, and %XX an octet of UTF-8 text. */
    static String decode(String part) {
        byte[] raw = part.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream octets = new ByteArrayOutputStream();
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '+') {
                octets.write(' ');
            }
            else if (raw[i] == '%' && i + 2 < raw.length && hex(raw[i + 1]) >= 0 && hex(raw[i + 2]) >= 0) {
                octets.write(hex(raw[i + 1]) * 16 + hex(raw[i + 2]));
                i += 2;
            }
            else {
                octets.write(raw[i]);
            }
        }
        return octets.toString(StandardCharsets.UTF_8);
    }

    static int hex(int octet) {
        return "0123456789abcdef".indexOf(Character.toLowerCase((char) octet));
    }

    /** The request's fields by name; a field sent more than once has its values joined. */
    static Json headers(Headers fields) {
        List<String> names = new ArrayList<>(fields.keySet());
        Collections.sort(names);
        Json headers = new Json();
        for (String name : names) {
            headers.put(name, String.join(", ", fields.get(name)));
        }
        return headers;
    }

    static void send(HttpExchange exchange, int status, Json json) throws IOException {
        StringBuilder text = new StringBuilder();
        json.write(text, "");
        byte[] body = text.append('\n').toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // --- JSON, written ---------------------------------------------------------

    /** JSON text kept as it came. */
    record Verbatim(String text) {
    }

    /**
     * A JSON object, written two spaces a level with its fields in the order they were
     * put. A value is a string, a list of strings, another object, a {@link Verbatim}, or
     * null.
     */
    static final class Json {

        private final List<String> names = new ArrayList<>();

        private final List<Object> values = new ArrayList<>();

        Json put(String name, Object value) {
            names.add(name);
            values.add(value);
            return this;
        }

        void write(StringBuilder out, String indent) {
            if (names.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            for (int i = 0; i < names.size(); i++) {
                out.append(indent).append("  ");
                quote(out, names.get(i));
                out.append(": ");
                Object value = values.get(i);
                if (value == null) {
                    out.append("null");
                }
                else if (value instanceof Json object) {
                    object.write(out, indent + "  ");
                }
                else if (value instanceof Verbatim json) {
                    out.append(json.text());
                }
                else if (value instanceof List<?> list) {
                    out.append('[');
                    for (int j = 0; j < list.size(); j++) {
                        out.append(j == 0 ? "" : ", ");
                        quote(out, (String) list.get(j));
                    }
                    out.append(']');
                }
                else {
                    quote(out, (String) value);
                }
                out.append(i + 1 < names.size() ? ",\n" : "\n");
            }
            out.append(indent).append('}');
        }

        static void quote(StringBuilder out, String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        }
                        else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }

    }

    // --- JSON, read ------------------------------------------------------------

    /** Reads a text as one JSON value (RFC 8259) without building it: only where it ends. */
    static final class JsonCheck {

        private final String text;

        private int at;

        private JsonCheck(String text) {
            this.text = text;
        }

        /** The text's one JSON value as it came, the blanks around it aside, or null. */
        static Verbatim value(String text) {
            JsonCheck check = new JsonCheck(text);
            check.blanks();
            int start = check.at;
            if (!check.value()) {
                return null;
            }
            int end = check.at;
            check.blanks();
            return (check.at == text.length()) ? new Verbatim(text.substring(start, end)) : null;
        }

        boolean value() {
            if (at == text.length()) {
                return false;
            }
            return switch (text.charAt(at)) {
                case '{' -> members('}', true);
                case '[' -> members(']', false);
                case '"' -> string();
                case 't' -> word("true");
                case 'f' -> word("false");
                case 'n' -> word("null");
                default -> number();
            };
        }

        /** An object's members or an array's elements, up to the closing character. */
        boolean members(char close, boolean named) {
            at++;
            blanks();
            if (next(close)) {
                return true;
            }
            do {
                blanks();
                if (named) {
                    if (!(at < text.length() && text.charAt(at) == '"' && string())) {
                        return false;
                    }
                    blanks();
                    if (!next(':')) {
                        return false;
                    }
                    blanks();
                }
                if (!value()) {
                    return false;
                }
                blanks();
            }
            while (next(','));
            return next(close);
        }

        boolean string() {
            at++;
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return true;
                }
                if (c < 0x20) {
                    return false;
                }
                if (c == '\\') {
                    if (at == text.length()) {
                        return false;
                    }
                    char escaped = text.charAt(at++);
                    if (escaped == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (at == text.length() || hex(text.charAt(at++)) < 0) {
                                return false;
                            }
                        }
                    }
                    else if ("\"\\/bfnrt".indexOf(escaped) < 0) {
                        return false;
                    }
                }
            }
            return false;
        }

        boolean number() {
            next('-');
            if (!next('0') && !digits()) {
                return false;
            }
            if (next('.') && !digits()) {
                return false;
            }
            if (next('e') || next('E')) {
                if (!next('+')) {
                    next('-');
                }
                return digits();
            }
            return true;
        }

        boolean digits() {
            int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
                at++;
            }
            return at > start;
        }

        boolean word(String word) {
            if (!text.startsWith(word, at)) {
                return false;
            }
            at += word.length();
            return true;
        }

        boolean next(char c) {
            if (at < text.length() && text.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        void blanks() {
            while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
        }

    }

    public static void main(String[] args) throws IOException {
        String port = System.getenv("PORT");
        HttpServer server = HttpServer.create(new InetSocketAddress(port == null ? 8080 : Integer.parseInt(port)), 0);
        server.createContext("/", Httpbin::route);
        server.start();
        System.out.println("Listening on http://localhost:" + server.getAddress().getPort() + "/");
    }
}
