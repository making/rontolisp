import java.io.BufferedReader;
import java.io.FileReader;

// The lines of a file through a BufferedReader over a FileReader.
public class LinesFile {
    public static void main(String[] args) throws Exception {
        int n = 0;
        try (BufferedReader in = new BufferedReader(new FileReader("LinesFile.java"))) {
            while (in.readLine() != null) {
                n++;
            }
        }
        System.out.println(n);
    }
}
