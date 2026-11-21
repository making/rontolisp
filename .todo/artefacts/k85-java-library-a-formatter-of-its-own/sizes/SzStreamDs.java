import java.io.PrintStream;

public class SzStreamDs {
    public static void main(String[] args) {
        PrintStream out = System.out;
        out.printf("%5d|%-4s%n", 42, "ab");
    }
}
