import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Hang {
    public static void main(String[] args) throws Exception {
        try {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://10.255.255.1:81/")).timeout(Duration.ofMillis(300)).build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e) {
            System.out.println(e + " / " + e.getCause());
        }
    }
}
