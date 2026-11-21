import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

// How much of a file a Scanner of a File delivers before bytes that are no UTF-8, by
// where they are; and what each strict encoder says of each kind of character it lacks.
public class Odds2 {
    public static void main(String[] args) throws Exception {
        for (int at : new int[] { 0, 1, 10, 500, 1023, 1024, 1025, 2000, 4095, 4096, 4097, 8000, 8191, 8192, 8193, 9000, 20000 }) {
            byte[] all = new byte[at + 20];
            for (int i = 0; i < all.length; i++) {
                all[i] = (byte) (i % 10 == 9 ? ' ' : 'a' + i % 10);
            }
            all[at] = (byte) 0xff;
            Files.write(Path.of("m.txt"), all);
            Scanner s = new Scanner(new File("m.txt"));
            int tokens = 0;
            int chars = 0;
            String last = null;
            while (s.hasNext()) {
                last = s.next();
                tokens++;
                chars += last.length();
            }
            System.out.println("at " + at + ": " + tokens + " tokens, " + chars + " chars, last " + last + " "
                    + s.ioException());
        }
        String[] texts = { "a\ud800b", "a\udc00b", "a\ud800", "\ud800𐀀", "aéb", "a€b", "a😀b" };
        String[] labels = { "lone high", "lone low", "high at end", "two highs", "e-acute", "euro", "emoji" };
        for (Charset cs : new Charset[] { StandardCharsets.UTF_8, StandardCharsets.US_ASCII, StandardCharsets.ISO_8859_1,
                StandardCharsets.UTF_16 }) {
            for (int i = 0; i < texts.length; i++) {
                String whole;
                try {
                    Files.writeString(Path.of("w.txt"), texts[i], cs);
                    whole = "ok";
                }
                catch (Exception e) {
                    whole = e.getClass().getSimpleName() + " " + e.getMessage();
                }
                String lines;
                try {
                    Files.write(Path.of("w.txt"), List.of(texts[i]), cs);
                    lines = "ok";
                }
                catch (Exception e) {
                    lines = e.getClass().getSimpleName() + " " + e.getMessage();
                }
                System.out.println(cs + " " + labels[i] + ": " + whole + " | " + lines);
            }
        }
    }
}
