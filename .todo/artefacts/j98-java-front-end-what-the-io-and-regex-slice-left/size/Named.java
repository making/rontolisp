import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class Named {
    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        long sum = 0;
        String line;
        while ((line = in.readLine()) != null) {
            sum += Long.parseLong(line);
        }
        System.out.println(sum + " " + "hé".getBytes("UTF-8").length + " " + new String(new byte[] {104, 105}, "utf-8"));
    }
}
