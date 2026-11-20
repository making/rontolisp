import java.io.*;

public class ReadChars {
    public static void main(String[] args) throws IOException {
        Reader r = new InputStreamReader(System.in);
        char[] buf = new char[1024];
        long total = 0;
        int n;
        while ((n = r.read(buf, 0, buf.length)) >= 0) {
            total += n;
        }
        System.out.println(total);
    }
}
