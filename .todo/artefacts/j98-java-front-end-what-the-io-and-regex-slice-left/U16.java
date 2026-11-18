import java.io.*;
import java.nio.charset.*;
import java.util.*;

public class U16 {
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02x", x & 0xff));
        }
        return s.toString();
    }

    static String units(String t) {
        StringBuilder s = new StringBuilder();
        for (char c : t.toCharArray()) {
            s.append(String.format("%04x ", (int) c));
        }
        return s.toString().trim();
    }

    static byte[] bytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    public static void main(String[] args) throws Exception {
        Charset[] sets = {StandardCharsets.UTF_16, StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE};
        for (Charset cs : sets) {
            System.out.println(cs.name() + " " + cs + " " + cs.displayName() + " " + new InputStreamReader(new ByteArrayInputStream(new byte[0]), cs).getEncoding() + " " + new OutputStreamWriter(new ByteArrayOutputStream(), cs).getEncoding() + " " + new PrintStream(new ByteArrayOutputStream(), true, cs).charset());
            int[][] texts = {{}, {'a'}, {'a', 'b', 0xe9, 0x20ac}, {0xd83d, 0xde00}, {'x', 0xd83d, 'y'}, {'x', 0xde00, 'y'}, {0xd83d}, {0xde00, 0xd83d}, {0xfeff, 'a'}, {0xfffe}, {0xd83d, 0xd83d, 0xde00}, {0xde00, 0xde00}};
            for (int[] codes : texts) {
                char[] made = new char[codes.length];
                for (int i = 0; i < codes.length; i++) {
                    made[i] = (char) codes[i];
                }
                String t = new String(made);
                System.out.println("  enc [" + units(t) + "] -> " + hex(t.getBytes(cs)));
            }
            String[] inputs = {"", "00", "0061", "6100", "feff0061", "fffe6100", "feff", "fffe", "feff00", "00610062ff", "d83dde00", "3dd800de", "d83d0061", "3dd86100", "de000061", "00de6100", "d83d", "3dd8", "d83dd83dde00", "d83dde", "0061feff0062", "0061fffe0062", "6100fffe6200", "6100feff6200", "feffd83dde00", "fffe3dd800de", "fefffeff0061", "fffefffe6100", "fefffffe", "fffefeff", "d83dde00de00", "0061d83d", "0061d83d00", "dc00d800", "ffff", "0000", "feff0061d83d", "d83dfeff", "d83dfffe"};
            for (String in : inputs) {
                byte[] b = bytes(in);
                String whole = new String(b, cs);
                // through a reader a few bytes at a time
                StringBuilder parts = new StringBuilder();
                for (int step = 1; step <= 3; step++) {
                    final int k = step;
                    InputStream slow = new ByteArrayInputStream(b) {
                        public int read(byte[] to, int off, int len) {
                            return super.read(to, off, Math.min(len, k));
                        }
                    };
                    Reader r = new InputStreamReader(slow, cs);
                    StringBuilder got = new StringBuilder();
                    int c;
                    while ((c = r.read()) >= 0) {
                        got.append(String.format("%04x ", c));
                    }
                    parts.append(got.toString().trim().equals(units(whole)) ? "=" : "[" + got.toString().trim() + "]");
                }
                System.out.println("  dec " + in + " -> [" + units(whole) + "] " + parts);
            }
            // a writer: the mark once, pieces, a pair split across writes, flush and close
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Writer w = new OutputStreamWriter(sink, cs);
            w.flush();
            System.out.print("  w " + hex(sink.toByteArray()));
            w.write("a");
            w.flush();
            System.out.print(" " + hex(sink.toByteArray()));
            w.write(0xd83d);
            w.flush();
            System.out.print(" " + hex(sink.toByteArray()));
            w.write(new char[] {0xde00, 'b'});
            w.flush();
            System.out.print(" " + hex(sink.toByteArray()));
            w.write(0xd83d);
            w.write("c");
            w.write(0xde00);
            w.write(0xd83d);
            w.close();
            System.out.println(" " + hex(sink.toByteArray()));
            sink = new ByteArrayOutputStream();
            w = new OutputStreamWriter(sink, cs);
            w.close();
            System.out.print("  empty " + hex(sink.toByteArray()));
            sink = new ByteArrayOutputStream();
            w = new OutputStreamWriter(sink, cs);
            w.write("");
            w.close();
            System.out.print(" " + hex(sink.toByteArray()));
            sink = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(sink, true, cs);
            ps.print("a");
            ps.print("b");
            ps.println();
            ps.print("");
            ps.write('z');
            ps.close();
            System.out.println(" ps " + hex(sink.toByteArray()) + " " + hex("".getBytes(cs)) + " " + sink.toString(cs).length());
            ByteArrayOutputStream held = new ByteArrayOutputStream();
            held.writeBytes("hi".getBytes(cs));
            System.out.println("  toString " + held.toString(cs) + " " + held.toString(cs.name()) + " " + new String(held.toByteArray(), cs.name()) + " " + hex("hi".getBytes(cs.name())) + " " + new String(bytes("00680069"), 1, 2, cs).length());
        }
        for (String name : new String[] {"UTF-16", "utf-16", "UTF_16", "utf16", "UTF16", "unicode", "UnicodeBig", "UTF-16BE", "UTF_16BE", "X-UTF-16BE", "UnicodeBigUnmarked", "ISO-10646-UCS-2", "UTF-16LE", "UTF_16LE", "X-UTF-16LE", "UnicodeLittleUnmarked", "UTF16BE", "UTF16LE", "UCS-2", "utf-16be", "UTF-64"}) {
            try {
                System.out.println(name + " -> " + Charset.forName(name).name() + " " + Charset.isSupported(name));
            } catch (Exception e) {
                System.out.println(name + " ! " + e);
            }
        }
    }
}
