import java.io.*;
import java.util.StringTokenizer;

public class SumTok {
    public static void main(String[] args) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        StringTokenizer t = new StringTokenizer(r.readLine());
        int n = Integer.parseInt(t.nextToken());
        long sum = 0;
        for (int i = 0; i < n; i++) {
            while (!t.hasMoreTokens()) {
                t = new StringTokenizer(r.readLine());
            }
            sum += Integer.parseInt(t.nextToken());
        }
        System.out.println(sum);
    }
}
