import java.io.BufferedWriter;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// The rest of what the strict codecs of Files say: the overlong and out-of-range
// sequences of UTF-8 through readString and readAllLines, what UTF-16 and US-ASCII
// refuse in, and what the encoders of writeString, write and newBufferedWriter refuse.
public class Malformed2 {
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02X", x & 0xff));
        }
        return s.toString();
    }

    static String said(Path p, boolean whole, Charset cs) {
        try {
            if (whole) {
                return "[" + Files.readString(p, cs).replace("�", "?") + "]";
            }
            return Files.readAllLines(p, cs).toString().replace("�", "?");
        }
        catch (CharacterCodingException e) {
            return e.getClass().getSimpleName() + " " + e.getMessage();
        }
        catch (Exception e) {
            return e.toString();
        }
    }

    static void ask(Charset cs, int... seq) throws Exception {
        Path p = Path.of("m.bin");
        byte[] all = new byte[seq.length];
        for (int i = 0; i < seq.length; i++) {
            all[i] = (byte) seq[i];
        }
        Files.write(p, all);
        System.out.println(cs + " " + hex(all) + " " + said(p, true, cs) + " " + said(p, false, cs));
    }

    static void write(String label, String text, Charset cs) {
        Path p = Path.of("w.txt");
        try {
            Files.deleteIfExists(p);
            Files.writeString(p, text, cs);
            System.out.println(label + " writeString ok " + hex(Files.readAllBytes(p)));
        }
        catch (Exception e) {
            System.out.println(label + " writeString " + e + " exists=" + Files.exists(p));
        }
        try {
            Files.deleteIfExists(p);
            Files.write(p, List.of("ok", text, "after"), cs);
            System.out.println(label + " write ok " + hex(readAll(p)));
        }
        catch (Exception e) {
            System.out.println(label + " write " + e + " exists=" + Files.exists(p) + " " + hex(readAll(p)));
        }
        try {
            Files.deleteIfExists(p);
            try (BufferedWriter w = Files.newBufferedWriter(p, cs)) {
                w.write("ok");
                w.write(text);
                w.write("after");
            }
            System.out.println(label + " writer ok " + hex(readAll(p)));
        }
        catch (Exception e) {
            System.out.println(label + " writer " + e + " exists=" + Files.exists(p) + " " + hex(readAll(p)));
        }
    }

    static byte[] readAll(Path p) {
        try {
            return Files.readAllBytes(p);
        }
        catch (Exception e) {
            return new byte[0];
        }
    }

    public static void main(String[] args) throws Exception {
        Charset u8 = StandardCharsets.UTF_8;
        int[][] seqs = { { 0x61, 0xe0, 0x80, 0x80 }, { 0x61, 0xe0, 0x9f, 0xbf }, { 0x61, 0xe0, 0x80, 0x80, 0x7a },
                { 0x61, 0xf0, 0x80, 0x80, 0x80 }, { 0x61, 0xf0, 0x8f, 0xbf, 0xbf }, { 0x61, 0xf4, 0x90, 0x80, 0x80 },
                { 0x61, 0xf4, 0xbf, 0xbf, 0xbf }, { 0x61, 0xf0, 0x80, 0x80, 0x80, 0x7a }, { 0x61, 0xc0, 0x80 },
                { 0x61, 0xc1, 0xbf }, { 0x61, 0xf5, 0x80, 0x80, 0x80 }, { 0x61, 0xed, 0xa0, 0x80, 0xed, 0xb0, 0x80 },
                { 0x61, 0xed, 0xbf, 0xbf }, { 0x61, 0xe0, 0x80 }, { 0x61, 0xf0, 0x80 }, { 0x61, 0xf0, 0x80, 0x80 },
                { 0x61, 0xf4, 0x90 }, { 0x61, 0xf4, 0x90, 0x80 }, { 0x61, 0xe1, 0x80, 0x80, 0xe1, 0x41, 0x41 },
                { 0xef, 0xbb, 0xbf, 0x61 }, { 0x61, 0x0d, 0x0a, 0xff }, { 0xc3 }, { 0xe2, 0x82 }, { 0xf0, 0x9f, 0x98 } };
        for (int[] s : seqs) {
            ask(u8, s);
        }
        Charset u16 = StandardCharsets.UTF_16;
        int[][] wide = { { 0x00, 0x61, 0xd8, 0x00 }, { 0x00, 0x61, 0xd8, 0x00, 0x00, 0x62 }, { 0x00, 0x61, 0xdc, 0x00 },
                { 0x00, 0x61, 0xdc, 0x00, 0x00, 0x62 }, { 0x00, 0x61, 0x00 }, { 0xfe, 0xff, 0x00, 0x61 },
                { 0xff, 0xfe, 0x61, 0x00 }, { 0xff, 0xfe, 0x61 }, { 0xd8, 0x3d, 0xde, 0x00 }, { 0xd8, 0x3d } };
        for (int[] s : wide) {
            ask(u16, s);
            ask(StandardCharsets.UTF_16BE, s);
            ask(StandardCharsets.UTF_16LE, s);
        }
        ask(StandardCharsets.US_ASCII, 0x61, 0x80, 0x62);
        ask(StandardCharsets.US_ASCII, 0x61, 0xff);
        ask(StandardCharsets.ISO_8859_1, 0x61, 0xff);
        write("ascii e-acute", "aéb", StandardCharsets.US_ASCII);
        write("ascii emoji", "a😀b", StandardCharsets.US_ASCII);
        write("latin euro", "a€b", StandardCharsets.ISO_8859_1);
        write("latin emoji", "😀", StandardCharsets.ISO_8859_1);
        write("utf8 lone high", "a\ud83db", u8);
        write("utf8 lone low", "a\ude00b", u8);
        write("utf8 high at end", "a\ud83d", u8);
        write("utf8 two highs", "\ud83d😀", u8);
        write("utf16 lone", "a\ud83db", u16);
        write("utf16 ok", "ab", u16);
        write("utf16be ok", "ab", StandardCharsets.UTF_16BE);
    }
}
