import java.io.*;
import java.util.StringTokenizer;

public class Tok {
    public static void main(String[] args) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        long total = 0;
        String line;
        while ((line = r.readLine()) != null) {
            StringTokenizer t = new StringTokenizer(line);
            while (t.hasMoreTokens()) {
                total += t.nextToken().length();
            }
        }
        System.out.println(total);
    }
}
