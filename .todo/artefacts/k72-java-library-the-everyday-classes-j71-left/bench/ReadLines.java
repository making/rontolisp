import java.io.*;

public class ReadLines {
    public static void main(String[] args) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        long total = 0;
        String line;
        while ((line = r.readLine()) != null) {
            total += line.length() + 1;
        }
        System.out.println(total);
    }
}
