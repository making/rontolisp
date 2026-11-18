import java.nio.charset.StandardCharsets;

public class Bytes {
    public static void main(String[] args) {
        System.out.println("hé".getBytes(StandardCharsets.UTF_8).length + " " + new String(new byte[] {104, 105}, StandardCharsets.ISO_8859_1));
    }
}
