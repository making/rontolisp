import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Formatter;
import java.util.Scanner;

// A program's files through java.io, in the directory it runs in: the paths of a File, what
// the streams, the readers and writers, a scanner and a random access file make and read, a
// directory's names in the host's order, and the words of what each refuses.
public class FilesOfJavaIo {
    interface Io {
        Object run() throws Exception;
    }

    static void show(String label, Io io) {
        try {
            Object value = io.run();
            System.out.println(label + ": " + (value instanceof String[] names ? Arrays.toString(names) : value));
        }
        catch (Exception e) {
            System.out.println(label + ": " + e);
        }
    }

    static String text(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace('\n', '|');
        }
    }

    static String sorted(File[] files) {
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getPath();
        }
        Arrays.sort(names);
        return Arrays.toString(names);
    }

    public static void main(String[] args) {
        String here = new File("").getAbsolutePath();
        File dir = new File("out//a/");
        show("paths", () -> dir + " " + dir.getName() + " " + dir.getParent() + " " + dir.isAbsolute() + " "
                + new File(dir, "/b//") + " " + new File("", "c") + " " + new File("/", "..") + " "
                + new File(".").getParentFile() + " " + new File((File) null, "d").hashCode());
        show("absolute", () -> dir.getAbsolutePath().equals(here + "/out/a") + " "
                + new File("out/../out/./a").getCanonicalPath().equals(new File(here).getCanonicalPath() + "/out/a") + " "
                + new File("/x/../y/.").getCanonicalPath() + " " + dir.equals(new File("out/a/")) + " "
                + dir.compareTo(new File("out/b")) + " " + dir.toPath().getFileName());
        show("made", () -> dir.mkdir() + " " + dir.mkdirs() + " " + dir.mkdirs() + " " + dir.mkdir() + " "
                + dir.isDirectory() + " " + dir.exists() + " " + dir.isFile() + " " + new File(dir, "x/y").mkdir());
        File f = new File(dir, "f.txt");
        show("new file", () -> f.createNewFile() + " " + f.createNewFile() + " " + f.length() + " " + f.isFile() + " "
                + (f.lastModified() > 1_600_000_000_000L) + " " + new File(dir, "none").lastModified() + " "
                + f.canRead() + " " + f.canWrite() + " " + new File(dir, "none").canRead() + " " + f.isHidden());
        show("written", () -> {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write("hello".getBytes());
                out.write('!');
                out.write(new byte[] { 'a', 'b', 'c' }, 1, 2);
            }
            return f.length() + " " + text(f);
        });
        show("appended", () -> {
            try (FileOutputStream out = new FileOutputStream(f.getPath(), true)) {
                out.write(" more".getBytes());
            }
            return text(f);
        });
        show("read", () -> {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[6];
                return in.available() + " " + in.read() + " " + in.read(b, 1, 4) + " " + Arrays.toString(b) + " "
                        + in.read(b, 0, 0) + " " + in.skip(3) + " " + (char) in.read() + " " + in.skip(100) + " "
                        + in.read() + " " + in.available() + " " + in.skip(-104) + " " + (char) in.read();
            }
        });
        show("closed", () -> {
            FileInputStream in = new FileInputStream(f);
            in.close();
            in.close();
            return in.read();
        });
        show("range", () -> {
            try (FileInputStream in = new FileInputStream(f)) {
                return in.read(new byte[4], 3, 2);
            }
        });
        show("grown", () -> {
            try (FileInputStream in = new FileInputStream(f); FileOutputStream out = new FileOutputStream(f, true)) {
                in.readAllBytes();
                int end = in.read();
                out.write('?');
                return end + " " + (char) in.read() + " " + in.read();
            }
        });
        File w = new File(dir, "w.txt");
        show("writer", () -> {
            try (Writer out = new FileWriter(w)) {
                out.write("héllo\n");
                out.append("line 2");
            }
            return w.length() + " " + text(w);
        });
        show("latin", () -> {
            try (Writer out = new FileWriter(w, StandardCharsets.ISO_8859_1, true)) {
                out.write("\né€");
            }
            return w.length();
        });
        show("reader", () -> {
            try (Reader in = new FileReader(w)) {
                StringBuilder seen = new StringBuilder();
                int c;
                while ((c = in.read()) >= 0) {
                    seen.append(c < 128 ? String.valueOf((char) c) : "<" + c + ">");
                }
                return seen.toString().replace('\n', '|');
            }
        });
        show("reader latin", () -> {
            try (Reader in = new FileReader(w.getPath(), StandardCharsets.ISO_8859_1)) {
                char[] buf = new char[64];
                int n = in.read(buf);
                return n + " " + (int) buf[n - 2] + " " + (int) buf[n - 1];
            }
        });
        File p = new File(dir, "p.txt");
        show("print writer", () -> {
            try (PrintWriter out = new PrintWriter(p)) {
                out.println("one two");
                out.printf("%d-%s%n", 2, "three");
                out.print(3.5);
            }
            return text(p);
        });
        show("print stream", () -> {
            try (PrintStream out = new PrintStream(dir + "/s.txt", "UTF-16BE")) {
                out.print("ab");
            }
            return new File(dir, "s.txt").length();
        });
        show("formatter", () -> {
            try (Formatter out = new Formatter(dir + "/m.txt")) {
                out.format("%s=%05d", "x", 42);
            }
            return text(new File(dir, "m.txt"));
        });
        show("scanner", () -> {
            try (Scanner in = new Scanner(p)) {
                StringBuilder seen = new StringBuilder();
                while (in.hasNext()) {
                    seen.append('[').append(in.next()).append(']');
                }
                return seen;
            }
        });
        // bytes that are no UTF-8: a scanner of a file stops at them, one of a path
        // replaces them
        File bad = new File(dir, "bad.txt");
        show("bad", () -> {
            try (FileOutputStream out = new FileOutputStream(bad)) {
                out.write(new byte[] { 'o', 'n', 'e', ' ', 't', (byte) 0xe9, 'o', '\n', 'x' });
            }
            return bad.length();
        });
        show("scanner bad", () -> {
            Scanner in = new Scanner(bad);
            return in.hasNext() + " " + in.ioException();
        });
        show("scanner bad path", () -> {
            Scanner in = new Scanner(bad.toPath());
            return in.nextLine().replace('�', '?') + " " + in.nextLine() + " " + in.ioException();
        });
        // a directory's names come in the host's order, which differs from one file
        // system to another: sorted here
        File list = new File("out/list");
        show("listed", () -> {
            list.mkdir();
            for (String name : new String[] { "b", "a", "c", "10", "9", "B", ".dot" }) {
                new File(list, name).createNewFile();
            }
            new File(list, "dir").mkdir();
            String[] names = list.list();
            Arrays.sort(names);
            return Arrays.toString(names);
        });
        show("files", () -> sorted(list.listFiles((FileFilter) File::isDirectory)) + " "
                + sorted(list.listFiles((d, name) -> name.length() == 1 && d.equals(list))) + " "
                + Arrays.toString(list.list((d, name) -> name.startsWith("1"))) + " " + new File(list, "dir").list().length
                + " " + f.list() + " " + new File("out/none").listFiles());
        show("renamed", () -> new File(list, "a").renameTo(new File(list, "a2")) + " "
                + new File(list, "a2").renameTo(new File(list, "b")) + " " + new File(list, "zz").renameTo(new File(list, "y"))
                + " " + new File(list, "c").renameTo(new File(list, "none/c")) + " " + list.list().length);
        show("deleted", () -> new File(list, "c").delete() + " " + new File(list, "c").delete() + " " + list.delete()
                + " " + new File(list, "dir").delete() + " " + list.list().length);
        // a file read and written where the program moves
        File r = new File(dir, "r.bin");
        show("random", () -> {
            try (RandomAccessFile raf = new RandomAccessFile(r, "rw")) {
                StringBuilder seen = new StringBuilder();
                seen.append(raf.length()).append(' ');
                raf.writeInt(0x01020304);
                raf.write("abc".getBytes());
                raf.writeUTF("hé");
                raf.writeLong(-2);
                raf.writeDouble(1.5);
                seen.append(raf.length()).append(' ').append(raf.getFilePointer()).append(' ');
                raf.seek(0);
                seen.append(raf.readInt()).append(' ').append(raf.read()).append(' ');
                raf.seek(7);
                seen.append(raf.readUTF()).append(' ').append(raf.readLong()).append(' ').append(raf.readDouble())
                        .append(' ').append(raf.read()).append(' ');
                raf.seek(40);
                seen.append(raf.read()).append(' ').append(raf.length()).append(' ');
                raf.write(9);
                seen.append(raf.length()).append(' ');
                raf.setLength(5);
                seen.append(raf.length()).append(' ').append(raf.getFilePointer()).append(' ');
                raf.setLength(8);
                raf.seek(0);
                byte[] all = new byte[10];
                seen.append(raf.read(all)).append(' ').append(Arrays.toString(all)).append(' ').append(raf.skipBytes(3));
                return seen;
            }
        });
        show("random lines", () -> {
            try (PrintWriter out = new PrintWriter(new File(dir, "l.txt"))) {
                out.print("first\nsecond\r\nthird\rfourth");
            }
            try (RandomAccessFile raf = new RandomAccessFile(dir + "/l.txt", "r")) {
                return raf.readLine() + "|" + raf.readLine() + "|" + raf.readLine() + "|" + raf.readLine() + "|"
                        + raf.readLine();
            }
        });
        show("random end", () -> {
            try (RandomAccessFile raf = new RandomAccessFile(r, "r")) {
                raf.seek(6);
                return raf.readInt();
            }
        });
        show("random read only", () -> {
            try (RandomAccessFile raf = new RandomAccessFile(r, "r")) {
                raf.write(1);
                return raf.length();
            }
        });
        show("random seek", () -> {
            try (RandomAccessFile raf = new RandomAccessFile(r, "rws")) {
                raf.seek(-1);
                return raf.getFilePointer();
            }
        });
        show("random mode", () -> new RandomAccessFile(r, "w"));
        show("random missing", () -> new RandomAccessFile("out/none.bin", "r"));
        // the words of what is refused
        show("in missing", () -> new FileInputStream("out/missing.txt"));
        show("in directory", () -> new FileInputStream(dir));
        show("in under a file", () -> new FileReader(f + "/x"));
        show("in empty", () -> new FileInputStream(""));
        show("in nul", () -> new FileInputStream("a\u0000b"));
        show("out directory", () -> new FileOutputStream("out"));
        show("out in missing", () -> new FileWriter("out/nope/x.txt"));
        show("out under a file", () -> new PrintWriter(f + "/x"));
        show("print writer charset", () -> new PrintWriter("out/nope/x.txt", "NOPE"));
        show("scanner missing", () -> new Scanner(new File("out/missing.txt")));
        show("scanner directory", () -> new Scanner(dir));
        show("new file in missing", () -> new File("out/nope/x").createNewFile());
        show("new file under a file", () -> new File(f, "x").createNewFile());
        show("in locked", () -> new FileInputStream("locked.txt"));
        show("out locked", () -> new FileOutputStream("locked.txt", true));
        show("out read-only", () -> new FileOutputStream("ro/x.txt"));
        show("locked", () -> new File("locked.txt").canRead() + " " + new File("locked.txt").canWrite() + " "
                + new File("ro").canRead());
    }
}
