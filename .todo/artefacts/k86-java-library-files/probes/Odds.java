import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

// What k86's first probes left open: a path holding a NUL, a scanner over bytes that are
// no text, a lone surrogate written in a charset of one byte, a hidden name that is not
// there, a skip before the start.
public class Odds {
    interface Io {
        Object run() throws Exception;
    }

    static void show(String label, Io io) {
        try {
            System.out.println(label + " = " + io.run());
        }
        catch (Exception e) {
            System.out.println(label + " ! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        show("in nul", () -> new FileInputStream("a\u0000b"));
        show("out nul", () -> new FileOutputStream("a\u0000b"));
        show("createNewFile nul", () -> new File("a\u0000b").createNewFile());
        show("mkdir nul", () -> new File("a\u0000b").mkdir());
        Files.write(Path.of("bad.txt"), new byte[] { 'o', 'n', 'e', ' ', 't', 'w', 'o', '\n', 't', 'h', (byte) 0xe9, 'r', 'e', 'e', '\n', 'f', 'o', 'u', 'r' });
        show("scanner file", () -> {
            Scanner s = new Scanner(new File("bad.txt"));
            StringBuilder seen = new StringBuilder();
            while (s.hasNext()) {
                seen.append('[').append(s.next()).append(']');
            }
            return seen + " " + s.ioException();
        });
        show("scanner path", () -> {
            Scanner s = new Scanner(Path.of("bad.txt"));
            StringBuilder seen = new StringBuilder();
            while (s.hasNextLine()) {
                seen.append('[').append(s.nextLine()).append(']');
            }
            return seen + " " + s.ioException();
        });
        show("scanner latin", () -> {
            Scanner s = new Scanner(new File("bad.txt"), "ISO-8859-1");
            StringBuilder seen = new StringBuilder();
            while (s.hasNextLine()) {
                seen.append('[').append(s.nextLine()).append(']');
            }
            return seen + " " + s.ioException();
        });
        show("scanner directory", () -> {
            Files.createDirectories(Path.of("d"));
            Scanner s = new Scanner(Path.of("d"));
            return s.hasNext() + " " + s.ioException();
        });
        show("ascii lone", () -> Files.writeString(Path.of("l1.txt"), "a\ud800b", StandardCharsets.US_ASCII));
        show("latin lone", () -> Files.writeString(Path.of("l2.txt"), "a\udc00b", StandardCharsets.ISO_8859_1));
        show("ascii lone writer", () -> Files.write(Path.of("l3.txt"), List.of("a\ud800b"), StandardCharsets.US_ASCII));
        show("latin lone writer", () -> Files.write(Path.of("l4.txt"), List.of("a\udc00b"), StandardCharsets.ISO_8859_1));
        show("hidden missing", () -> new File(".nothing").isHidden() + " " + new File("x/.nothing").isHidden());
        show("skip before start", () -> {
            try (FileInputStream in = new FileInputStream("bad.txt")) {
                return in.skip(-5);
            }
        });
        show("skip far", () -> {
            try (FileInputStream in = new FileInputStream("bad.txt")) {
                return in.skip(100) + " " + in.available() + " " + in.read() + " " + in.skip(-90) + " " + in.read();
            }
        });
        show("grown", () -> {
            try (FileInputStream in = new FileInputStream("bad.txt"); FileOutputStream out = new FileOutputStream("bad.txt", true)) {
                in.readAllBytes();
                int first = in.read();
                out.write('!');
                return first + " " + in.read() + " " + in.read();
            }
        });
        show("canWrite dir", () -> new File("d").canWrite() + " " + new File("d").canRead());
        show("length dir", () -> new File("d").length() > 0);
        show("toPath nul", () -> new File("a\u0000b").toPath());
        show("readAllBytes", () -> new FileInputStream("bad.txt").readAllBytes().length);
    }
}
