import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// What Files.readString and Files.readAllLines say of bytes that are no UTF-8: the
// "Input length" of each refusal, for a byte sequence after "a" and before "z" or the
// end; and where new String puts its U+FFFDs.
public class Malformed {
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02X", x & 0xff));
        }
        return s.toString();
    }

    static String said(Path p, boolean whole) {
        try {
            if (whole) {
                Files.readString(p);
            }
            else {
                Files.readAllLines(p);
            }
            return "ok";
        }
        catch (MalformedInputException e) {
            return String.valueOf(e.getInputLength());
        }
        catch (Exception e) {
            return e.toString();
        }
    }

    static void ask(byte[] seq) throws Exception {
        Path p = Path.of("m.bin");
        for (boolean end : new boolean[] { false, true }) {
            byte[] all = new byte[seq.length + (end ? 1 : 2)];
            all[0] = 'a';
            System.arraycopy(seq, 0, all, 1, seq.length);
            if (!end) {
                all[all.length - 1] = 'z';
            }
            Files.write(p, all);
            String s = new String(all, StandardCharsets.UTF_8);
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                marks.append(s.charAt(i) == '�' ? '?' : '.');
            }
            System.out.println(hex(all) + " " + said(p, true) + " " + said(p, false) + " " + marks);
        }
    }

    public static void main(String[] args) throws Exception {
        int[] follows = { 0x00, 0x41, 0x7f, 0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf, 0xc0, 0xc2, 0xe0, 0xf0, 0xff };
        for (int lead = 0x80; lead <= 0xff; lead++) {
            ask(new byte[] { (byte) lead });
            for (int second : follows) {
                ask(new byte[] { (byte) lead, (byte) second });
            }
        }
        // a valid second byte, then each kind of third and fourth
        int[][] starts = { { 0xe0, 0xa0 }, { 0xe1, 0x80 }, { 0xed, 0x80 }, { 0xed, 0x9f }, { 0xed, 0xa0 }, { 0xee, 0xbf },
                { 0xf0, 0x90 }, { 0xf1, 0x80 }, { 0xf4, 0x8f }, { 0xf4, 0x90 }, { 0xf0, 0x80 } };
        for (int[] start : starts) {
            for (int third : follows) {
                ask(new byte[] { (byte) start[0], (byte) start[1], (byte) third });
            }
        }
        int[][] fours = { { 0xf0, 0x90, 0x80 }, { 0xf1, 0x80, 0x80 }, { 0xf4, 0x8f, 0xbf } };
        for (int[] start : fours) {
            for (int fourth : follows) {
                ask(new byte[] { (byte) start[0], (byte) start[1], (byte) start[2], (byte) fourth });
            }
        }
        // two problems in a row
        ask(new byte[] { (byte) 0x80, (byte) 0x80 });
        ask(new byte[] { (byte) 0xc3, (byte) 0xa9, (byte) 0xff, 'b', (byte) 0xe2 });
        ask(new byte[] { 'x', 'y', '\n', (byte) 0xc3 });
    }
}
