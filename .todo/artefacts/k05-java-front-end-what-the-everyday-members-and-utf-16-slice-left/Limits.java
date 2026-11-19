import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

public class Limits {

    static final String ORIGIN = "http://127.0.0.1:18431";

    static final HttpClient CLIENT = HttpClient.newHttpClient();

    static HttpRequest.Builder to(String path) {
        return HttpRequest.newBuilder(URI.create(ORIGIN + path));
    }

    static String text(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + " [" + response.body() + "]";
    }

    interface Refused {
        Object run() throws Exception;
    }

    static void refused(String what, Refused attempt) {
        try {
            System.out.println(what + ": " + attempt.run());
        }
        catch (Exception thrown) {
            System.out.println(what + ": " + thrown);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== a-request-and-a-connection-have-time-limits");
        HttpRequest slow = to("/slow").timeout(Duration.ofMillis(200)).build();
        System.out.println(slow.timeout() + " " + to("/slow").build().timeout() + " " + CLIENT.connectTimeout());
        try {
            System.out.println(text(slow));
        }
        catch (HttpTimeoutException e) {
            System.out.println(e + " / " + e.getCause() + " " + (e instanceof HttpConnectTimeoutException));
        }
        HttpClient patient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest.Builder waiting = to("/slow").timeout(Duration.ofSeconds(30));
        System.out.println(patient.connectTimeout() + " " + waiting.copy().build().timeout() + " "
                + patient.send(waiting.build(), HttpResponse.BodyHandlers.ofString()).body());
        System.out.println(to("/hello").timeout(Duration.ofNanos(1)).build().timeout() + " " + to("/hello").timeout(Duration.ofSeconds(Long.MAX_VALUE, 999999999)).build().timeout());
        refused("zero", () -> to("/hello").timeout(Duration.ZERO));
        refused("negative", () -> to("/hello").timeout(Duration.ofMillis(-1)));
        refused("null", () -> to("/hello").timeout(null));
        refused("connect zero", () -> HttpClient.newBuilder().connectTimeout(Duration.ZERO));
        refused("connect negative", () -> HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(-3)));
        refused("connect null", () -> HttpClient.newBuilder().connectTimeout(null));
        refused("async", () -> CLIENT.sendAsync(slow, HttpResponse.BodyHandlers.ofString()).handle((r, t) -> r + " " + t + " / " + t.getCause().getCause()).join());
        HttpClient hasty = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
        try {
            hasty.send(HttpRequest.newBuilder(URI.create("http://10.255.255.1:81/")).build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (HttpTimeoutException e) {
            System.out.println(e + " / " + e.getCause() + " / " + e.getCause().getCause() + " / " + e.getCause().getCause().getCause() + " " + (e instanceof HttpConnectTimeoutException));
        }
        refused("async connect", () -> hasty.sendAsync(HttpRequest.newBuilder(URI.create("http://10.255.255.1:81/")).build(), HttpResponse.BodyHandlers.ofString()).handle((r, t) -> r + " " + t + " / " + t.getCause().getCause()).join());
        HttpClient following = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> moved = following.send(to("/redirect").timeout(Duration.ofSeconds(9)).build(), HttpResponse.BodyHandlers.ofString());
        System.out.println(moved.statusCode() + " " + moved.request().timeout());
    }
}
