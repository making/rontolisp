import java.nio.charset.StandardCharsets;

// The UTF-16 codec over a string: a program that reads no file.
public class Utf16Bytes {
    public static void main(String[] args) {
        byte[] b = "aé".getBytes(StandardCharsets.UTF_16);
        System.out.println(b.length + " " + new String(b, StandardCharsets.UTF_16));
    }
}
