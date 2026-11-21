import java.util.Formatter;

public class SzFormatter {
    public static void main(String[] args) {
        Formatter f = new Formatter();
        f.format("%5d|%-4s", 42, "ab");
        System.out.println(f);
    }
}
