// An HTTP server on the JDK's own API, com.sun.net.httpserver. One source for every
// transport: a socket on the interpreter and the JVM, the runner's server in a
// --native executable, and a wasi:http component under --component, which
// `wasmtime serve` runs (the host owns the port there, and starts the program for
// the requests it likes).
package http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class Server {

    static void text(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static void hello(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/")) {
            text(exchange, 404, "no route for " + exchange.getRequestMethod() + " " + path + "\n");
            return;
        }
        String agent = exchange.getRequestHeaders().getFirst("User-Agent");
        text(exchange, 200, "Hello from com.sun.net.httpserver on rontolisp, "
                + (agent == null ? "no user agent" : agent) + "\n");
    }

    // /greet?name=Ann
    static void greet(HttpExchange exchange) throws IOException {
        String name = "stranger";
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("name=")) {
                    name = pair.substring("name=".length());
                }
            }
        }
        text(exchange, 200, "Hello, " + name + "!\n");
    }

    // POST /echo answers its body as it came, under the request's Content-Type
    static void echo(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            text(exchange, 405, "POST a body to /echo\n");
            return;
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        exchange.getResponseHeaders().set("Content-Type", type == null ? "application/octet-stream" : type);
        exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // A body of a length not told ahead: each line is a chunk of its own, on its way
    // when its write returns where the transport sends a body as it is written.
    static void countdown(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 3; i > 0; i--) {
                out.write((i + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("liftoff\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The application: the contexts a server answers. */
    static void routes(HttpServer server) {
        server.createContext("/", Server::hello);
        server.createContext("/greet", Server::greet);
        server.createContext("/echo", Server::echo);
        server.createContext("/countdown", Server::countdown);
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        routes(server);
        server.start();
        System.out.println("Listening on http://localhost:8080/");
    }
}
