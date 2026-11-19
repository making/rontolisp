import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class App {
    static int entered;
    static final ScheduledExecutorService LATER = Executors.newScheduledThreadPool(1);

    static void text(HttpExchange exchange, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/fast", exchange -> text(exchange, "fast " + (++entered)));
        server.createContext("/sleep", exchange -> {
            int n = ++entered;
            try {
                Thread.sleep(1500);
            }
            catch (InterruptedException e) {
            }
            text(exchange, "slept " + n + " now " + entered);
        });
        server.createContext("/later", exchange -> {
            int n = ++entered;
            LATER.schedule(() -> System.out.println("later " + n + " ran, entered " + entered), 700, TimeUnit.MILLISECONDS);
            text(exchange, "scheduled " + n);
        });
        server.createContext("/upstream", exchange -> {
            int n = ++entered;
            String url = exchange.getRequestURI().getQuery();
            String got;
            try {
                got = HttpClient.newHttpClient()
                    .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
                    .body();
            }
            catch (Exception e) {
                got = e.toString();
            }
            text(exchange, "upstream " + n + " now " + entered + ": " + got);
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("listening");
    }
}
