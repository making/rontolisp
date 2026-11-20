import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Bounds {
    static String of(Supplier<Object> s) {
        try {
            return String.valueOf(s.get());
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    static void grid(String label, NavigableMap<Integer, String> v) {
        StringBuilder line = new StringBuilder(label + " " + v + " |");
        for (int k = 1; k <= 9; k += 2) {
            int key = k;
            for (boolean inc : new boolean[] {true, false}) {
                line.append(" h").append(key).append(inc ? "i" : "x").append("=").append(of(() -> v.headMap(key, inc).keySet()));
                line.append(" t").append(key).append(inc ? "i" : "x").append("=").append(of(() -> v.tailMap(key, inc).keySet()));
            }
        }
        System.out.println(line);
        line = new StringBuilder(label + " sub |");
        for (int a = 1; a <= 9; a += 2) {
            for (int b = 1; b <= 9; b += 2) {
                int from = a;
                int to = b;
                line.append(" ").append(from).append("-").append(to).append("=").append(of(() -> v.subMap(from, true, to, false).keySet()));
                line.append("/").append(of(() -> v.subMap(from, false, to, true).keySet()));
            }
        }
        System.out.println(line);
        line = new StringBuilder(label + " at |");
        for (int k = 0; k <= 10; k++) {
            int key = k;
            line.append(" ").append(key).append(":").append(of(() -> v.get(key))).append(",").append(of(() -> v.containsKey(key)))
                    .append(",").append(of(() -> v.lowerKey(key))).append(",").append(of(() -> v.floorKey(key)))
                    .append(",").append(of(() -> v.ceilingKey(key))).append(",").append(of(() -> v.higherKey(key)));
        }
        System.out.println(line);
    }

    static void views(String label, NavigableMap<Integer, String> m) {
        grid(label, m);
        grid(label + "[3,7]", m.subMap(3, true, 7, true));
        grid(label + "(3,7)", m.subMap(3, false, 7, false));
        grid(label + "[3,7)", m.subMap(3, true, 7, false));
        grid(label + "(3,7]", m.subMap(3, false, 7, true));
        grid(label + "<5", m.headMap(5, false));
        grid(label + ">=5", m.tailMap(5, true));
        grid(label + "desc", m.descendingMap());
        grid(label + "desc[7,3]", m.descendingMap().subMap(7, true, 3, true));
        grid(label + "desc(7,3)", m.descendingMap().subMap(7, false, 3, false));
        grid(label + "desc<5", m.descendingMap().headMap(5, false));
        grid(label + "desc>=5", m.descendingMap().tailMap(5, true));
        grid(label + "[3,7]desc", m.subMap(3, true, 7, true).descendingMap());
        grid(label + "(3,7)desc", m.subMap(3, false, 7, false).descendingMap());
    }

    public static void main(String[] args) {
        TreeMap<Integer, String> t = new TreeMap<>();
        for (int i = 1; i <= 9; i++) {
            t.put(i, "v" + i);
        }
        views("tree", t);
        ConcurrentSkipListMap<Integer, String> c = new ConcurrentSkipListMap<>(t);
        views("skip", c);
        // a put at the edges of each view
        NavigableMap<Integer, String> v = t.subMap(3, false, 7, true);
        StringBuilder line = new StringBuilder("put");
        for (int k = 2; k <= 8; k++) {
            int key = k;
            line.append(" ").append(key).append("=").append(of(() -> v.put(key, "p")));
            line.append("/").append(of(() -> v.descendingMap().put(key, "q")));
            line.append("/").append(of(() -> c.subMap(3, false, 7, true).put(key, "r")));
        }
        System.out.println(line + " " + t + " " + c);
        System.out.println(of(() -> t.subMap(8, 2)) + " " + of(() -> t.subMap(3, false, 7, true).subMap(8, 2)) + " " + of(() -> t.subMap(3, false, 7, true).subMap(9, 1)) + " " + of(() -> t.subMap(3, false, 7, true).subMap(5, 1)) + " " + of(() -> t.subMap(3, false, 7, true).subMap(9, 5)));
        System.out.println(of(() -> c.subMap(8, 2)) + " " + of(() -> c.subMap(3, false, 7, true).subMap(8, 2)) + " " + of(() -> c.subMap(3, false, 7, true).subMap(9, 1)) + " " + of(() -> c.subMap(3, false, 7, true).subMap(5, 1)) + " " + of(() -> c.subMap(3, false, 7, true).subMap(9, 5)));
        System.out.println(of(() -> t.headMap(null)) + " " + of(() -> t.tailMap(null)) + " " + of(() -> t.subMap(null, 3)) + " " + of(() -> t.subMap(3, null)) + " " + of(() -> c.headMap(null)) + " " + of(() -> c.subMap(3, null)) + " " + of(() -> t.headMap(3).get(null)) + " " + of(() -> c.headMap(3).get(null)) + " " + of(() -> t.descendingMap().get(null)));
        System.out.println(of(() -> t.subMap(3, 7).firstEntry()) + " " + of(() -> t.subMap(3, 7).pollLastEntry()) + " " + of(() -> t.subMap(3, 7).lastEntry()) + " " + t);
    }
}
