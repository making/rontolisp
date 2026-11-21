import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;

// readString's length for a byte past F4 or C1 followed by continuation bytes, and for
// each lead with the bytes after it all continuations, one short of its size and more.
public class Malformed3 {
    public static void main(String[] args) throws Exception {
        Path p = Path.of("m.bin");
        for (int lead = 0x80; lead <= 0xff; lead++) {
            StringBuilder line = new StringBuilder(String.format("%02X", lead));
            for (int follow = 0; follow <= 4; follow++) {
                byte[] all = new byte[1 + follow];
                all[0] = (byte) lead;
                for (int i = 1; i <= follow; i++) {
                    all[i] = (byte) 0x80;
                }
                Files.write(p, all);
                String whole;
                try {
                    Files.readString(p);
                    whole = "ok";
                }
                catch (MalformedInputException e) {
                    whole = String.valueOf(e.getInputLength());
                }
                String lines;
                try {
                    Files.readAllLines(p);
                    lines = "ok";
                }
                catch (MalformedInputException e) {
                    lines = String.valueOf(e.getInputLength());
                }
                line.append(' ').append(whole).append('/').append(lines);
            }
            System.out.println(line);
        }
    }
}
