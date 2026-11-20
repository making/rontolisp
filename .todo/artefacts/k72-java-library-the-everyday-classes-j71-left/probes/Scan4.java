import java.io.*;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

// Scanner: where a refused token leaves the position, a scanner over a stream and over a
// program's own Readable, closing what it reads.
public class Scan4 {
    static class Chunks implements Readable, Closeable {
        private final String text;
        private final int step;
        private int at;
        int reads;
        boolean closed;

        Chunks(String text, int step) {
            this.text = text;
            this.step = step;
        }

        public int read(CharBuffer cb) {
            reads++;
            if (at >= text.length()) {
                return -1;
            }
            int n = Math.min(step, Math.min(cb.remaining(), text.length() - at));
            cb.put(text, at, at + n);
            at += n;
            return n;
        }

        public void close() {
            closed = true;
        }
    }

    static class Failing implements Readable {
        int reads;

        public int read(CharBuffer cb) throws IOException {
            reads++;
            if (reads == 1) {
                cb.put("12 ab");
                return 5;
            }
            throw new IOException("broken after " + reads);
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner a = new Scanner("12 x\n");
        a.nextInt();
        try {
            a.nextInt();
        } catch (InputMismatchException e) {
            System.out.println("refused");
        }
        System.out.println("[" + a.nextLine() + "]");
        Scanner b = new Scanner("12 x\n");
        b.nextInt();
        System.out.println(b.hasNextInt() + " [" + b.nextLine() + "]");
        Scanner c = new Scanner("12   \n");
        c.nextInt();
        try {
            c.next();
        } catch (NoSuchElementException e) {
            System.out.println("none");
        }
        System.out.println(c.hasNextLine() + " " + c.toString().contains("position=6"));
        Scanner d = new Scanner("12  \n  x");
        d.nextInt();
        try {
            d.nextInt();
        } catch (InputMismatchException e) {
            System.out.println("refused d");
        }
        System.out.println("[" + d.nextLine() + "]");

        Scanner s = new Scanner(new ByteArrayInputStream("héllo wörld 42\nzwei".getBytes(StandardCharsets.UTF_8)));
        System.out.println(s.next() + " " + s.next() + " " + s.nextInt() + " [" + s.nextLine() + "] [" + s.nextLine() + "] " + s.hasNextLine());
        Scanner s2 = new Scanner(new ByteArrayInputStream("café 1".getBytes(StandardCharsets.ISO_8859_1)), "ISO-8859-1");
        System.out.println(s2.next() + " " + s2.nextInt());
        Scanner s3 = new Scanner(new ByteArrayInputStream("naïve".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        System.out.println(s3.next());
        try {
            new Scanner(new ByteArrayInputStream(new byte[0]), "no-such-charset");
        } catch (Exception e) {
            System.out.println(e.getClass().getName() + " " + e.getMessage());
        }
        try {
            new Scanner(new ByteArrayInputStream(new byte[0]), (String) null);
        } catch (Exception e) {
            System.out.println(e.getClass().getName());
        }
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            big.append(i).append(i % 7 == 0 ? "\n" : " ");
        }
        Chunks ch = new Chunks(big.toString(), 7);
        Scanner r = new Scanner(ch);
        long sum = 0;
        int count = 0;
        while (r.hasNextInt()) {
            sum += r.nextInt();
            count++;
        }
        System.out.println(count + " " + sum + " " + r.hasNext() + " closed " + ch.closed + " reads>0 " + (ch.reads > 0));
        r.close();
        System.out.println("after close " + ch.closed);
        Chunks ch2 = new Chunks("line one\r\nline two\rline three\n\nlast", 3);
        Scanner r2 = new Scanner(ch2);
        StringBuilder lines = new StringBuilder();
        while (r2.hasNextLine()) {
            lines.append('[').append(r2.nextLine()).append(']');
        }
        System.out.println(lines);
        Chunks ch3 = new Chunks("a,b,,c", 1);
        Scanner r3 = new Scanner(ch3).useDelimiter(",");
        StringBuilder toks = new StringBuilder();
        while (r3.hasNext()) {
            toks.append('[').append(r3.next()).append(']');
        }
        System.out.println(toks);
        Chunks ch4 = new Chunks("abc123def", 2);
        Scanner r4 = new Scanner(ch4);
        System.out.println(r4.findInLine("\\d+") + " " + r4.next());
        Chunks ch5 = new Chunks("xx yy zz", 1);
        Scanner r5 = new Scanner(ch5);
        System.out.println(r5.findWithinHorizon("z+", 0) + " " + r5.hasNext());
        Failing fl = new Failing();
        Scanner r6 = new Scanner(fl);
        System.out.println(r6.nextInt() + " " + r6.hasNext() + " " + r6.next() + " " + r6.hasNext() + " " + r6.ioException() + " " + fl.reads);
        Scanner r7 = new Scanner(new StringReader("p q"));
        System.out.println(r7.next() + r7.next() + r7.hasNext());
        StringReader sr = new StringReader("z");
        Scanner r8 = new Scanner(sr);
        r8.close();
        try {
            sr.read();
        } catch (IOException e) {
            System.out.println("reader closed: " + e.getMessage());
        }
        Scanner r9 = new Scanner(new ByteArrayInputStream("1 2 3".getBytes()));
        r9.nextInt();
        r9.close();
        System.out.println("closed stream scanner " + r9.toString().contains("source closed=true"));
    }
}
