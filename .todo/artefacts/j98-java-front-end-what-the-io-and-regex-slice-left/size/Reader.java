import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class Reader {
    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        long sum = 0;
        String line;
        while ((line = in.readLine()) != null) {
            sum += Long.parseLong(line);
        }
        System.out.println(sum);
    }
}
