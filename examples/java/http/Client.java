// The HTTP client of java.net.http against the application of Server.java, which this
// program serves itself on a free local port and stops when it is done: a GET, a query,
// a POST body, a body arriving in chunks, a status that is no success, and a request
// sent without waiting for it. The server and the client share the process, so this
// runs where the target has threads -- the interpreter and the JVM; on its own the
// client runs on every target (doc/en/java/index.md).
package http;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class Client {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Server.routes(server);
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> hello = client.send(
                HttpRequest.newBuilder(URI.create(origin + "/")).header("User-Agent", "examples/java").build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.print(hello.statusCode() + " " + hello.body());
        System.out.println("content-type: " + hello.headers().firstValue("content-type").orElse("none"));

        HttpResponse<String> greeting = client.send(
                HttpRequest.newBuilder(URI.create(origin + "/greet?name=Ann")).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.print(greeting.statusCode() + " " + greeting.body());

        HttpResponse<String> echoed = client.send(
                HttpRequest.newBuilder(URI.create(origin + "/echo"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"foo\": 100}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println(echoed.statusCode() + " " + echoed.headers().firstValue("content-type").orElse("none")
                + " " + echoed.body());

        HttpResponse<String> refused = client.send(HttpRequest.newBuilder(URI.create(origin + "/echo")).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.print(refused.statusCode() + " allow " + refused.headers().firstValue("allow").orElse("none")
                + ": " + refused.body());

        HttpResponse<String> counted = client.send(HttpRequest.newBuilder(URI.create(origin + "/countdown")).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println(counted.statusCode() + " " + String.join(" ", counted.body().split("\n")));

        CompletableFuture<String> missing = client
            .sendAsync(HttpRequest.newBuilder(URI.create(origin + "/missing")).build(),
                    HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> response.statusCode() + " " + response.body());
        System.out.print(missing.get());

        server.stop(0);
    }
}
