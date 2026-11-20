import java.nio.CharBuffer;
import java.util.*;
import java.util.function.*;

// Scanner over a Readable handing scripted pieces: when the scanner asks for more input,
// as each operation goes.
public class ScanReads {
    static class Pieces implements Readable {
        final String[] pieces;
        int next;
        final StringBuilder log;

        Pieces(StringBuilder log, String... pieces) {
            this.pieces = pieces;
            this.log = log;
        }

        public int read(CharBuffer cb) {
            if (next >= pieces.length) {
                log.append("{eof}");
                return -1;
            }
            String p = pieces[next++];
            log.append("{read ").append(p.replace("\n", "\\n").replace("\r", "\\r")).append("}");
            cb.put(p);
            return p.length();
        }
    }

    static void run(String what, String[] pieces, Function<Scanner, Object>... ops) {
        StringBuilder log = new StringBuilder(what + ":");
        Scanner sc = new Scanner(new Pieces(log, pieces));
        for (Function<Scanner, Object> op : ops) {
            try {
                Object v = op.apply(sc);
                log.append(" =").append(String.valueOf(v).replace("\n", "\\n").replace("\r", "\\r"));
            } catch (Exception e) {
                log.append(" !").append(e.getClass().getSimpleName());
            }
        }
        System.out.println(log);
    }

    public static void main(String[] args) {
        run("next", new String[] {"ab", "  ", "cd", " e"}, s -> s.next(), s -> s.next(), s -> s.next(), s -> s.hasNext());
        run("next nl", new String[] {"ab\n", "cd\n"}, s -> s.next(), s -> s.next(), s -> s.hasNext());
        run("hasNext", new String[] {"ab", " ", " cd"}, s -> s.hasNext(), s -> s.next(), s -> s.hasNext(), s -> s.next());
        run("nextInt nextLine", new String[] {"5\n", "x y\n"}, s -> s.nextInt(), s -> s.nextLine(), s -> s.nextLine(), s -> s.hasNextLine());
        run("line cr", new String[] {"x\r", "\ny\r", "z"}, s -> s.nextLine(), s -> s.nextLine(), s -> s.nextLine(), s -> s.hasNextLine());
        run("line cr end", new String[] {"x\r"}, s -> s.nextLine(), s -> s.hasNextLine());
        run("delim s+", new String[] {"ab  ", "  cd"}, s -> s.useDelimiter("\\s+").next(), s -> s.next());
        run("delim s+ nl", new String[] {"ab\n", "cd\n"}, s -> s.useDelimiter("\\s+").next(), s -> s.next(), s -> s.hasNext());
        run("delim comma", new String[] {"a,", ",b"}, s -> s.useDelimiter(",").next(), s -> s.next(), s -> s.next(), s -> s.hasNext());
        run("delim ab", new String[] {"xa", "by"}, s -> s.useDelimiter("ab").next(), s -> s.next());
        run("delim alt", new String[] {"xa..b", "..z"}, s -> s.useDelimiter("a.*z|b").next(), s -> s.next());
        run("delim lit end", new String[] {"x,", "y"}, s -> s.useDelimiter(",").next(), s -> s.next());
        run("skip a+", new String[] {"aa", "ab"}, s -> s.skip("a+").next());
        run("skip lit", new String[] {"ab", "cd"}, s -> s.skip("ab").next());
        run("skip fail", new String[] {"ab", "cd"}, s -> s.skip("x"), s -> s.next());
        run("horizon a+", new String[] {"xaa", "ab"}, s -> s.findWithinHorizon("a+", 0), s -> s.next());
        run("horizon lit", new String[] {"xab", "cd"}, s -> s.findWithinHorizon("ab", 0), s -> s.next());
        run("horizon fail", new String[] {"xab", "cd"}, s -> s.findWithinHorizon("q", 0), s -> s.next());
        run("horizon 2", new String[] {"x", "ab", "cd"}, s -> s.findWithinHorizon("a", 2), s -> s.next());
        run("findInLine", new String[] {"ab", "c\nd"}, s -> s.findInLine("b"), s -> s.next());
        run("findInLine 2", new String[] {"ab\n", "cd"}, s -> s.findInLine("b"), s -> s.next());
        run("hasNextInt", new String[] {"12", "3 4"}, s -> s.hasNextInt(), s -> s.nextInt(), s -> s.nextInt());
        run("nextLine long", new String[] {"abc", "def", "\n"}, s -> s.nextLine());
        run("tokens", new String[] {"a b", " c"}, s -> s.tokens().count());
    }
}
