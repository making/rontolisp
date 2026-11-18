import java.io.*;
import java.nio.charset.*;
import java.util.*;

public class U16Rand {
    static final int[] UNITS = {0x0061, 0x00e9, 0x20ac, 0xfeff, 0xfffe, 0xd83d, 0xde00, 0xd800, 0xdbff, 0xdc00, 0xdfff, 0xfffd, 0x0000, 0xffff, 0x3042};

    static String units(char[] cs) {
        StringBuilder s = new StringBuilder();
        for (char c : cs) {
            s.append(Integer.toHexString(c)).append(' ');
        }
        return s.toString();
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(Integer.toHexString((x & 0xff) | 0x100).substring(1));
        }
        return s.toString();
    }

    public static void main(String[] args) throws IOException {
        Charset[] sets = {StandardCharsets.UTF_16, StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE};
        Random random = new Random(20261118);
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        for (int round = 0; round < count; round++) {
            Charset cs = sets[random.nextInt(3)];
            int n = random.nextInt(9);
            // bytes: units of either order, sometimes a byte too many
            byte[] b = new byte[2 * n + (random.nextInt(4) == 0 ? 1 : 0)];
            for (int i = 0; i < n; i++) {
                int unit = UNITS[random.nextInt(UNITS.length)];
                boolean little = random.nextInt(3) == 0;
                b[2 * i] = (byte) (little ? unit : unit >> 8);
                b[2 * i + 1] = (byte) (little ? unit >> 8 : unit);
            }
            if (b.length % 2 == 1) {
                b[b.length - 1] = (byte) random.nextInt(256);
            }
            String whole = new String(b, cs);
            final int[] steps = {1 + random.nextInt(4), 1 + random.nextInt(4), 1 + random.nextInt(4)};
            final int[] turn = {0};
            InputStream slow = new ByteArrayInputStream(b) {
                public int read(byte[] to, int off, int len) {
                    return super.read(to, off, Math.min(len, steps[turn[0]++ % 3]));
                }
            };
            Reader reader = new InputStreamReader(slow, cs);
            char[] read = new char[32];
            int got = 0;
            int c;
            while ((c = reader.read()) >= 0) {
                read[got++] = (char) c;
            }
            // text: units a string is made of, written whole and in pieces
            char[] made = new char[random.nextInt(7)];
            for (int i = 0; i < made.length; i++) {
                made[i] = (char) UNITS[random.nextInt(UNITS.length)];
            }
            String text = new String(made);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Writer writer = new OutputStreamWriter(sink, cs);
            int at = 0;
            while (at < made.length) {
                int piece = 1 + random.nextInt(3);
                if (at + piece > made.length) {
                    piece = made.length - at;
                }
                writer.write(made, at, piece);
                if (random.nextInt(3) == 0) {
                    writer.flush();
                }
                at += piece;
            }
            writer.close();
            System.out.println(cs + " " + hex(b) + " -> " + units(whole.toCharArray()) + "| " + units(Arrays.copyOf(read, got)) + "| " + units(made) + "-> " + hex(text.getBytes(cs)) + " | " + hex(sink.toByteArray()));
        }
    }
}
