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
// The document is built, and a body checked, with tiny-json
// (https://github.com/making/tiny-json), which also writes it, two spaces a level and an array on one line.
//
// One source for every transport; the port is the PORT environment variable's, 8080
// when it is not set. A Maven project (examples/java/httpbin/pom.xml):
//
//   mvn package    # target/httpbin.wasm, a wasi:http component for `wasmtime serve`,
//                  # where the host owns the port and starts the program for the requests it likes
//   mvn exec:exec  # on a JDK
//
// and on the command line, from the repository's root:
//
//   LIBS="--java-source-dep am.ik.json:tiny-json:0.4.1"
//   APP=examples/java/httpbin/src/main/java/httpbin/Httpbin.java
//   java -jar $JAR $APP $LIBS                                    # interpreter
//   java -jar $JAR $APP $LIBS -o Httpbin.class && java Httpbin   # JVM
//   java -jar $JAR $APP $LIBS -o httpbin --native && ./httpbin   # native executable
//
// Talk to it with:
//
//   curl 'http://localhost:8080/get?a=1&b=two'
//   curl --json '{"name": "rontolisp"}' http://localhost:8080/post
package httpbin;

import am.ik.json.Json;
import am.ik.json.JsonArray;
import am.ik.json.JsonLexerException;
import am.ik.json.JsonNode;
import am.ik.json.JsonObject;
import am.ik.json.JsonParseException;
import am.ik.json.JsonWriter;
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
            send(exchange, 404, new JsonObject().put("error", "not found").put("path", path));
        }
        else if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            send(exchange, 405, new JsonObject().put("allowed", method).put("error", "method not allowed"));
        }
        else {
            send(exchange, 200, describe(exchange, path, !method.equals("GET")));
        }
    }

    // --- the request, described ----------------------------------------------

    static JsonObject describe(HttpExchange exchange, String path, boolean withBody) throws IOException {
        JsonObject info = new JsonObject().put("args", args(exchange.getRequestURI().getRawQuery()));
        String data = null;
        if (withBody) {
            data = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            info.put("data", data);
        }
        info.put("headers", headers(exchange.getRequestHeaders()));
        if (withBody) {
            info.put("json", verbatim(data));
        }
        return info.put("method", exchange.getRequestMethod()).put("path", path);
    }

    /** The query's pairs by name; a name given more than once has an array of its values. */
    static JsonObject args(String rawQuery) {
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
        JsonObject args = new JsonObject();
        for (String name : names) {
            List<String> given = values.get(name);
            if (given.size() == 1) {
                args.put(name, given.get(0));
            }
            else {
                JsonArray all = new JsonArray();
                for (String value : given) {
                    all.add(new JsonNode(value));
                }
                args.put(name, all);
            }
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
    static JsonObject headers(Headers fields) {
        List<String> names = new ArrayList<>(fields.keySet());
        Collections.sort(names);
        JsonObject headers = new JsonObject();
        for (String name : names) {
            headers.put(name, String.join(", ", fields.get(name)));
        }
        return headers;
    }

    static void send(HttpExchange exchange, int status, JsonObject json) throws IOException {
        byte[] body = (Json.stringify(json, JsonWriter.pretty().inlineArrays()) + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // --- JSON, as it came ------------------------------------------------------

    /** JSON text kept as it came: tiny-json writes a value of a class of its own as its text. */
    record Verbatim(String text) {

        @Override
        public String toString() {
            return this.text;
        }

    }

    /** The body as it came, the blanks around it aside, when it is one JSON value; else null. */
    static JsonNode verbatim(String data) {
        try {
            Json.parse(data);
        }
        catch (JsonParseException | JsonLexerException | NumberFormatException ex) {
            return new JsonNode(null);
        }
        return new JsonNode(new Verbatim(data.strip()));
    }

    public static void main(String[] args) throws IOException {
        String port = System.getenv("PORT");
        HttpServer server = HttpServer.create(new InetSocketAddress(port == null ? 8080 : Integer.parseInt(port)), 0);
        server.createContext("/", Httpbin::route);
        server.start();
        System.out.println("Listening on http://localhost:" + server.getAddress().getPort() + "/");
    }
}
