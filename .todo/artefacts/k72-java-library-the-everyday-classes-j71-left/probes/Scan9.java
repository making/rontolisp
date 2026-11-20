import java.nio.CharBuffer;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

// Scanner: the words of a refused float, its streams, what a Readable is handed.
public class Scan9 {
    static void tryIt(String what, java.util.concurrent.Callable<Object> r) {
        try {
            System.out.println(what + ": " + r.call());
        } catch (Exception e) {
            System.out.println(what + ": " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        for (String t : new String[] {"∞", "-∞", "0x.8", "0x1.8", "0x1p3", "NaN", "1e", "1e400", "1.5f", "nan", "Infinityx"}) {
            tryIt("double " + t, () -> new Scanner(t).nextDouble());
            tryIt("float " + t, () -> new Scanner(t).nextFloat());
            tryIt("dec " + t, () -> new Scanner(t).nextBigDecimal());
        }
        Scanner a = new Scanner("a b c d");
        Stream<String> s = a.tokens();
        tryIt("next during", () -> { Iterator<String> it = s.iterator(); it.next(); a.next(); return it.next(); });
        Scanner b = new Scanner("a b c d");
        Stream<String> s2 = b.tokens();
        s2.close();
        tryIt("after stream close", () -> b.hasNext());
        Scanner c = new Scanner("a b");
        c.close();
        tryIt("tokens of closed", () -> c.tokens());
        tryIt("findAll of closed", () -> c.findAll("a"));
        Scanner d = new Scanner("a b");
        Stream<String> s3 = d.tokens();
        d.close();
        tryIt("stream of closed", () -> s3.count());
        Scanner e = new Scanner("x1 x2 x3");
        Stream<MatchResult> s4 = e.findAll("x(\\d)");
        tryIt("findAll then next", () -> { Iterator<MatchResult> it = s4.iterator(); MatchResult m = it.next(); return m.group(1) + " " + e.next() + " " + it.hasNext(); });
        Scanner f = new Scanner("aaa");
        tryIt("findAll empty matches", () -> f.findAll("a*").map(m -> m.start() + "-" + m.end()).collect(Collectors.toList()));
        Scanner g = new Scanner("abc");
        tryIt("findAll empty pattern", () -> g.findAll("").map(m -> m.start() + "-" + m.end()).collect(Collectors.toList()));
        Scanner h = new Scanner("one two");
        h.next();
        tryIt("tokens rest", () -> h.tokens().collect(Collectors.toList()) + " " + h.hasNext());
        tryIt("tokens characteristics", () -> new Scanner("a").tokens().spliterator().characteristics());
        tryIt("findAll characteristics", () -> new Scanner("a").findAll("a").spliterator().characteristics());
        tryIt("tokens count", () -> new Scanner("a b c").tokens().count());
        tryIt("findAll null", () -> new Scanner("a").findAll((String) null));
        tryIt("findAll match is snapshot", () -> {
            Scanner k = new Scanner("p1 p2");
            List<MatchResult> l = k.findAll("p\\d").collect(Collectors.toList());
            return l.get(0).group() + l.get(1).group();
        });
        tryIt("match is snapshot", () -> {
            Scanner k = new Scanner("p1 p2");
            k.next();
            MatchResult m = k.match();
            k.next();
            return m.group() + " " + m.start();
        });
        Readable r = new Readable() {
            int calls;
            public int read(CharBuffer cb) {
                calls++;
                System.out.println("read " + calls + ": position " + cb.position() + " limit " + cb.limit() + " capacity " + cb.capacity() + " remaining " + cb.remaining());
                if (calls > 3) {
                    return -1;
                }
                cb.put("word" + calls + " ");
                return 6;
            }
        };
        Scanner rs = new Scanner(r);
        System.out.println(rs.next() + " " + rs.next() + " " + rs.next() + " " + rs.hasNext());
    }
}
