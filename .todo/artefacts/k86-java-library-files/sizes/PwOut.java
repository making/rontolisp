import java.io.*;

// A PrintWriter over System.out: a program that writes no file.
public class PwOut {
    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)));
        out.printf("%d %s%n", 42, "x");
        out.flush();
    }
}
