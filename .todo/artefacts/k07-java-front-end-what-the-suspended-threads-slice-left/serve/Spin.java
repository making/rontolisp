import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

public class Spin {
    static final AtomicLong turns = new AtomicLong();
    public static void main(String[] args) throws Exception {
        Thread spinner = new Thread(() -> {
            while (true) {
                turns.incrementAndGet();
            }
        });
        spinner.setDaemon(true);
        spinner.start();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("turns " + (turns.get() > 0)).getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }
}
