import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Formatter;
import java.util.Scanner;

// What a File, the file streams and a RandomAccessFile do in a directory of their own
// (the run's), and the words of what they refuse. Run where locked.txt (mode 000) and
// ro/ (mode 555) were made beforehand.
public class FileOps {
    interface Io {
        Object run() throws Exception;
    }

    static void show(String label, Io io) {
        try {
            Object value = io.run();
            System.out.println(label + " = " + (value instanceof String[] names ? Arrays.toString(names) : value));
        }
        catch (Exception e) {
            System.out.println(label + " ! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    static String text(String name) throws IOException {
        try (InputStream in = new FileInputStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String sorted(String[] names) {
        if (names == null) {
            return "null";
        }
        String[] copy = names.clone();
        Arrays.sort(copy);
        return Arrays.toString(copy);
    }

    static String sortedFiles(File[] files) {
        if (files == null) {
            return "null";
        }
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getPath();
        }
        return sorted(names);
    }

    public static void main(String[] args) throws Exception {
        File t = new File("t");
        show("exists before", t::exists);
        show("mkdir", t::mkdir);
        show("mkdir again", t::mkdir);
        show("exists isDirectory isFile", () -> t.exists() + " " + t.isDirectory() + " " + t.isFile());
        show("mkdir a/b", () -> new File(t, "a/b").mkdir());
        show("mkdirs a/b", () -> new File(t, "a/b").mkdirs());
        show("mkdirs a/b again", () -> new File(t, "a/b").mkdirs());
        show("mkdirs of a file's child", () -> new File("FileOps.java", "x").mkdirs());
        File f = new File(t, "f.txt");
        show("createNewFile", f::createNewFile);
        show("createNewFile again", f::createNewFile);
        show("createNewFile in missing", () -> new File(t, "missing/x.txt").createNewFile());
        show("createNewFile under a file", () -> new File(f, "x.txt").createNewFile());
        show("length empty", f::length);
        show("exists isDirectory isFile", () -> f.exists() + " " + f.isDirectory() + " " + f.isFile());
        show("length missing", () -> new File(t, "nothing").length());
        show("lastModified missing", () -> new File(t, "nothing").lastModified());
        show("lastModified positive", () -> f.lastModified() > 1_600_000_000_000L);
        // the streams
        show("write", () -> {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write("hello".getBytes());
                out.write('!');
                out.write(new byte[] { 'a', 'b', 'c', 'd' }, 1, 2);
            }
            return f.length();
        });
        show("read", () -> text("t/f.txt"));
        show("append", () -> {
            try (FileOutputStream out = new FileOutputStream("t/f.txt", true)) {
                out.write(" more".getBytes());
            }
            return text("t/f.txt");
        });
        show("truncate", () -> {
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write("xyz".getBytes());
            }
            return text("t/f.txt");
        });
        show("read bytes", () -> {
            try (FileInputStream in = new FileInputStream(f)) {
                StringBuilder seen = new StringBuilder();
                seen.append(in.available()).append(' ');
                seen.append(in.read()).append(' ');
                seen.append(in.available()).append(' ');
                byte[] buf = new byte[10];
                seen.append(in.read(buf, 0, 0)).append(' ');
                seen.append(in.read(buf, 2, 8)).append(' ');
                seen.append(Arrays.toString(buf)).append(' ');
                seen.append(in.read()).append(' ');
                seen.append(in.read(buf)).append(' ');
                seen.append(in.available());
                return seen;
            }
        });
        show("skip", () -> {
            try (FileInputStream in = new FileInputStream(f)) {
                return in.skip(1) + " " + in.read() + " " + in.skip(10) + " " + in.read() + " " + in.skip(-1) + " "
                        + in.skip(0);
            }
        });
        show("read after close", () -> {
            FileInputStream in = new FileInputStream(f);
            in.close();
            in.close();
            return in.read();
        });
        show("read array after close", () -> {
            FileInputStream in = new FileInputStream(f);
            in.close();
            return in.read(new byte[4]);
        });
        show("available after close", () -> {
            FileInputStream in = new FileInputStream(f);
            in.close();
            return in.available();
        });
        show("write after close", () -> {
            FileOutputStream out = new FileOutputStream(new File(t, "g.txt"));
            out.close();
            out.write(1);
            return "written";
        });
        show("write array after close", () -> {
            FileOutputStream out = new FileOutputStream(new File(t, "g.txt"));
            out.close();
            out.write(new byte[2]);
            return "written";
        });
        show("read range", () -> {
            try (FileInputStream in = new FileInputStream(f)) {
                return in.read(new byte[4], 3, 2);
            }
        });
        show("write range", () -> {
            try (FileOutputStream out = new FileOutputStream(new File(t, "g.txt"))) {
                out.write(new byte[4], -1, 2);
            }
            return "written";
        });
        show("null read", () -> {
            try (FileInputStream in = new FileInputStream(f)) {
                return in.read(null, 0, 1);
            }
        });
        // the refusals
        show("in missing", () -> new FileInputStream("t/missing.txt"));
        show("in directory", () -> new FileInputStream("t"));
        show("in under a file", () -> new FileInputStream("t/f.txt/x"));
        show("in empty name", () -> new FileInputStream(""));
        show("in locked", () -> new FileInputStream("locked.txt"));
        show("in null name", () -> new FileInputStream((String) null));
        show("in null file", () -> new FileInputStream((File) null));
        show("out directory", () -> new FileOutputStream("t"));
        show("out in missing", () -> new FileOutputStream("t/nope/x.txt"));
        show("out under a file", () -> new FileOutputStream("t/f.txt/x"));
        show("out empty name", () -> new FileOutputStream(""));
        show("out locked", () -> new FileOutputStream("locked.txt"));
        show("out in read-only directory", () -> new FileOutputStream("ro/x.txt"));
        show("reader missing", () -> new FileReader("t/missing.txt"));
        show("reader file missing", () -> new FileReader(new File(t, "missing.txt")));
        show("writer in missing", () -> new FileWriter("t/nope/x.txt"));
        show("writer file in missing", () -> new FileWriter(new File(t, "nope/x.txt"), true));
        show("print writer missing", () -> new PrintWriter("t/nope/x.txt"));
        show("print writer file missing", () -> new PrintWriter(new File("t/nope/x.txt")));
        show("print stream missing", () -> new PrintStream("t/nope/x.txt"));
        show("print stream file missing", () -> new PrintStream(new File("t/nope/x.txt")));
        show("formatter missing", () -> new Formatter("t/nope/x.txt"));
        show("formatter file missing", () -> new Formatter(new File("t/nope/x.txt")));
        show("scanner file missing", () -> new Scanner(new File("t/missing.txt")));
        show("scanner path missing", () -> new Scanner(new File("t/missing.txt").toPath()));
        show("scanner directory", () -> new Scanner(new File("t")));
        show("scanner path directory", () -> new Scanner(new File("t").toPath()));
        show("print writer charset", () -> new PrintWriter("t/p.txt", "NOPE"));
        show("print writer charset missing", () -> new PrintWriter("t/nope/p.txt", "NOPE"));
        // characters through a reader and a writer
        show("writer", () -> {
            try (FileWriter w = new FileWriter("t/w.txt")) {
                w.write("héllo €😀\n");
                w.append("line 2");
            }
            return text("t/w.txt");
        });
        show("writer append latin", () -> {
            try (FileWriter w = new FileWriter(new File("t/w.txt"), StandardCharsets.ISO_8859_1, true)) {
                w.write("\né€");
            }
            try (FileInputStream in = new FileInputStream("t/w.txt")) {
                byte[] all = in.readAllBytes();
                return all.length + " " + all[all.length - 3] + " " + all[all.length - 2] + " " + all[all.length - 1];
            }
        });
        show("reader", () -> {
            try (Reader r = new FileReader("t/w.txt")) {
                StringBuilder seen = new StringBuilder();
                int c;
                while ((c = r.read()) >= 0) {
                    seen.append(c < 128 ? String.valueOf((char) c) : "<" + c + ">");
                }
                return seen;
            }
        });
        show("reader latin", () -> {
            try (Reader r = new FileReader(new File("t/w.txt"), StandardCharsets.ISO_8859_1)) {
                char[] buf = new char[64];
                int n = r.read(buf);
                return n;
            }
        });
        show("print writer", () -> {
            try (PrintWriter w = new PrintWriter("t/pw.txt")) {
                w.println("one");
                w.printf("%d-%s%n", 2, "two");
                w.print(3.5);
            }
            return text("t/pw.txt");
        });
        show("print writer file", () -> {
            try (PrintWriter w = new PrintWriter(new File("t/pw.txt"), "UTF-16BE")) {
                w.print("ab");
            }
            return new File("t/pw.txt").length();
        });
        show("print stream", () -> {
            try (PrintStream w = new PrintStream("t/ps.txt")) {
                w.println("one");
                w.write(65);
                w.print('é');
            }
            return text("t/ps.txt");
        });
        show("print stream file", () -> {
            try (PrintStream w = new PrintStream(new File("t/ps.txt"), "UTF-8")) {
                w.print("é");
            }
            return new File("t/ps.txt").length();
        });
        show("formatter", () -> {
            try (Formatter w = new Formatter("t/fm.txt")) {
                w.format("%s=%d", "x", 42);
            }
            return text("t/fm.txt");
        });
        show("formatter file", () -> {
            try (Formatter w = new Formatter(new File("t/fm.txt"))) {
                w.format("%05d", 7);
                return w.locale();
            }
        });
        show("formatter text", () -> text("t/fm.txt"));
        show("scanner", () -> {
            try (Scanner s = new Scanner(new File("t/pw.txt"))) {
                StringBuilder seen = new StringBuilder();
                while (s.hasNext()) {
                    seen.append('[').append(s.next()).append(']');
                }
                return seen;
            }
        });
        show("scanner lines", () -> {
            try (PrintWriter w = new PrintWriter("t/lines.txt")) {
                w.print("first\nsecond\r\nthird");
            }
            try (Scanner s = new Scanner(new File("t/lines.txt").toPath())) {
                StringBuilder seen = new StringBuilder();
                while (s.hasNextLine()) {
                    seen.append('[').append(s.nextLine()).append(']');
                }
                return seen;
            }
        });
        show("scanner charset", () -> {
            try (Scanner s = new Scanner(new File("t/w.txt"), "UTF-8")) {
                return s.nextLine();
            }
        });
        // listing
        File l = new File(t, "list");
        l.mkdir();
        for (String name : new String[] { "b", "a", "c", "10", "9", "B", ".dot", "dir" }) {
            if (name.equals("dir")) {
                new File(l, name).mkdir();
            }
            else {
                new File(l, name).createNewFile();
            }
        }
        show("list sorted", () -> sorted(l.list()));
        show("list", l::list);
        show("listFiles", () -> sortedFiles(l.listFiles()));
        show("listFiles filter", () -> sortedFiles(l.listFiles((FileFilter) File::isDirectory)));
        show("listFiles name filter", () -> sortedFiles(l.listFiles((dir, name) -> name.length() == 1)));
        show("list name filter", () -> sorted(l.list((FilenameFilter) (dir, name) -> dir.equals(l) && name.startsWith("1"))));
        show("list of a file", () -> sorted(f.list()));
        show("list of missing", () -> sorted(new File("t/none").list()));
        show("listFiles of missing", () -> sortedFiles(new File("t/none").listFiles()));
        show("listFiles relative", () -> sortedFiles(new File("t/list/").listFiles()));
        show("listFiles empty dir", () -> sortedFiles(new File(l, "dir").listFiles()));
        // renaming and deleting
        show("renameTo", () -> new File(l, "a").renameTo(new File(l, "a2")) + " " + new File(l, "a2").exists() + " "
                + new File(l, "a").exists());
        show("renameTo existing", () -> new File(l, "a2").renameTo(new File(l, "b")) + " " + sorted(l.list()));
        show("renameTo missing", () -> new File(l, "zz").renameTo(new File(l, "yy")));
        show("renameTo into missing", () -> new File(l, "c").renameTo(new File(l, "nope/c")));
        show("renameTo dir", () -> new File(l, "dir").renameTo(new File(l, "dir2")) + " " + sorted(l.list()));
        show("delete file", () -> new File(l, "c").delete() + " " + new File(l, "c").exists());
        show("delete missing", () -> new File(l, "c").delete());
        show("delete non-empty", () -> l.delete());
        show("delete empty dir", () -> new File(l, "dir2").delete());
        show("can", () -> f.canRead() + " " + f.canWrite() + " " + new File("locked.txt").canRead()
                + " " + new File("locked.txt").canWrite() + " " + new File("ro").canRead() + " "
                + new File("missing").canRead());
        show("hidden", () -> new File(l, ".dot").isHidden() + " " + f.isHidden() + " " + new File(".").isHidden() + " "
                + new File("..").isHidden());
        // the random access file
        show("raf missing r", () -> new RandomAccessFile("t/none.bin", "r"));
        show("raf mode", () -> new RandomAccessFile("t/r.bin", "w"));
        show("raf directory", () -> new RandomAccessFile("t", "rw"));
        show("raf", () -> {
            try (RandomAccessFile r = new RandomAccessFile("t/r.bin", "rw")) {
                StringBuilder seen = new StringBuilder();
                seen.append(r.length()).append(' ').append(r.getFilePointer()).append(' ');
                r.writeInt(0x01020304);
                r.write("abc".getBytes());
                r.writeUTF("hé");
                r.writeLong(-2);
                r.writeDouble(1.5);
                seen.append(r.length()).append(' ').append(r.getFilePointer()).append(' ');
                r.seek(0);
                seen.append(r.readInt()).append(' ').append(r.read()).append(' ');
                r.seek(4 + 3);
                seen.append(r.readUTF()).append(' ').append(r.readLong()).append(' ').append(r.readDouble()).append(' ');
                seen.append(r.read()).append(' ').append(r.getFilePointer()).append(' ');
                r.seek(100);
                seen.append(r.read()).append(' ').append(r.length()).append(' ');
                r.write(9);
                seen.append(r.length()).append(' ');
                r.setLength(5);
                seen.append(r.length()).append(' ').append(r.getFilePointer()).append(' ');
                r.seek(0);
                byte[] all = new byte[10];
                seen.append(r.read(all)).append(' ').append(Arrays.toString(all)).append(' ');
                r.setLength(8);
                r.seek(0);
                seen.append(r.read(all)).append(' ').append(Arrays.toString(all)).append(' ');
                r.seek(0);
                r.readFully(all, 0, 8);
                seen.append(r.skipBytes(3)).append(' ');
                return seen;
            }
        });
        show("raf eof", () -> {
            try (RandomAccessFile r = new RandomAccessFile("t/r.bin", "r")) {
                r.seek(6);
                return r.readInt();
            }
        });
        show("raf seek negative", () -> {
            try (RandomAccessFile r = new RandomAccessFile("t/r.bin", "r")) {
                r.seek(-1);
                return r.getFilePointer();
            }
        });
        show("raf write read-only", () -> {
            try (RandomAccessFile r = new RandomAccessFile("t/r.bin", "r")) {
                r.write(1);
                return r.length();
            }
        });
        show("raf lines", () -> {
            try (RandomAccessFile r = new RandomAccessFile("t/lines.txt", "rw")) {
                return r.readLine() + "|" + r.readLine() + "|" + r.readLine() + "|" + r.readLine();
            }
        });
        show("raf closed", () -> {
            RandomAccessFile r = new RandomAccessFile("t/r.bin", "r");
            r.close();
            return r.read();
        });
        show("raf rws", () -> {
            try (RandomAccessFile r = new RandomAccessFile(new File("t/new.bin"), "rws")) {
                r.write(new byte[] { 1, 2, 3 });
                return r.length();
            }
        });
    }
}
