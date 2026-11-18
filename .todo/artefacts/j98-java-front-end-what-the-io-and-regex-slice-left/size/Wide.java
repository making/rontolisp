import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class Wide {
    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_16));
        long sum = 0;
        String line;
        while ((line = in.readLine()) != null) {
            sum += Long.parseLong(line);
        }
        System.out.println(sum);
    }
}
