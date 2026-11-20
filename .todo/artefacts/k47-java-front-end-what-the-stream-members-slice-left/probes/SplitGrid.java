import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class SplitGrid {
    static String marks(int c) {
        StringBuilder b = new StringBuilder();
        String[] names = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] bits = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        for (int i = 0; i < bits.length; i++) {
            if ((c & bits[i]) != 0) {
                b.append(b.length() == 0 ? "" : "|").append(names[i]);
            }
        }
        return b.length() == 0 ? "-" : b.toString();
    }

    static Comparator<?> order;

    static String split(Spliterator<?> s) {
        StringBuilder out = new StringBuilder(marks(s.characteristics()) + " " + s.estimateSize());
        try {
            Comparator<?> c = s.getComparator();
            out.append(" cmp=").append(c == null ? "null" : c == order ? "same" : "other");
            if (c != null && c != order) {
                Comparator<Object> co = (Comparator<Object>) c;
                out.append(co.compare(probe(1), probe(2)) < 0 ? "<" : ">");
            }
        } catch (IllegalStateException e) {
            out.append(" cmp!ISE");
        }
        StringBuilder seen = new StringBuilder();
        s.tryAdvance(x -> seen.append(x));
        out.append(" ").append(seen).append(" ").append(s.estimateSize());
        s.tryAdvance(x -> seen.append(x));
        out.append(" ").append(s.estimateSize());
        return out.toString();
    }

    static boolean entries;

    static String entrySplit(Spliterator<?> s) {
        entries = true;
        String r = split(s);
        entries = false;
        return r;
    }

    static Object probe(int i) {
        return entries ? Map.entry(i, "x") : i;
    }

    static void maps(String label, NavigableMap<Integer, String> m) {
        entries = false;
        System.out.println(label + " keySet " + split(m.keySet().spliterator()));
        System.out.println(label + " navigableKeySet " + split(m.navigableKeySet().spliterator()));
        System.out.println(label + " descendingKeySet " + split(m.descendingKeySet().spliterator()));
        System.out.println(label + " values " + split(m.values().spliterator()));
        entries = true;
        System.out.println(label + " entrySet " + split(m.entrySet().spliterator()));
        entries = false;
    }

    static void views(String label, NavigableMap<Integer, String> m) {
        boolean up = m.comparator() == null;
        maps(label, m);
        maps(label + ".head", m.headMap(7, true));
        maps(label + ".tail", m.tailMap(2, false));
        maps(label + ".sub", up ? m.subMap(2, true, 6, false) : m.subMap(6, true, 2, false));
        maps(label + ".desc.desc.desc", m.descendingMap().descendingMap().descendingMap());
        maps(label + ".desc", m.descendingMap());
        maps(label + ".head.desc", m.headMap(7, true).descendingMap());
        maps(label + ".desc.head", m.descendingMap().headMap(3, true));
        maps(label + ".desc.desc", m.descendingMap().descendingMap());
    }

    static void sets(String label, NavigableSet<Integer> s) {
        System.out.println(label + " " + split(s.spliterator()));
        System.out.println(label + ".head " + split(s.headSet(7, true).spliterator()));
        System.out.println(label + ".tail " + split(s.tailSet(2, false).spliterator()));
        System.out.println(label + ".sub " + split(s.comparator() == null ? s.subSet(2, true, 6, false).spliterator() : s.subSet(6, true, 2, false).spliterator()));
        System.out.println(label + ".desc " + split(s.descendingSet().spliterator()));
        System.out.println(label + ".head.desc " + split(s.headSet(7, true).descendingSet().spliterator()));
        System.out.println(label + ".desc.head " + split(s.descendingSet().headSet(3, true).spliterator()));
        System.out.println(label + ".desc.desc " + split(s.descendingSet().descendingSet().spliterator()));
        System.out.println(label + ".reversed " + split(s.reversed().spliterator()));
    }

    public static void main(String[] args) {
        TreeMap<Integer, String> t = new TreeMap<>();
        TreeMap<Integer, String> tc = new TreeMap<>(Comparator.reverseOrder());
        ConcurrentSkipListMap<Integer, String> c = new ConcurrentSkipListMap<>();
        ConcurrentSkipListMap<Integer, String> cc = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        for (int i = 1; i <= 8; i++) {
            t.put(i, "v" + i);
            tc.put(i, "v" + i);
            c.put(i, "v" + i);
            cc.put(i, "v" + i);
        }
        order = null;
        views("tree", t);
        order = tc.comparator();
        views("treecmp", tc);
        order = null;
        views("skip", c);
        order = cc.comparator();
        views("skipcmp", cc);
        TreeSet<Integer> ts = new TreeSet<>(t.keySet());
        ConcurrentSkipListSet<Integer> cs = new ConcurrentSkipListSet<>(t.keySet());
        order = null;
        sets("treeset", ts);
        sets("skipset", cs);
        TreeSet<Integer> tsc = new TreeSet<>(Comparator.reverseOrder());
        tsc.addAll(ts);
        order = tsc.comparator();
        sets("treesetcmp", tsc);
        order = null;
        sets("treeset.keyset", t.navigableKeySet());
        sets("unmod", Collections.unmodifiableNavigableSet(ts));
        sets("sync", Collections.synchronizedNavigableSet(ts));
        maps("unmodmap", Collections.unmodifiableNavigableMap(t));
        maps("syncmap", Collections.synchronizedNavigableMap(t));
        maps("emptymap", Collections.emptyNavigableMap());
        order = null;
        System.out.println((t.descendingMap() == t.descendingMap()) + " " + (t.keySet() == t.keySet()) + " " + (t.navigableKeySet() == t.keySet()) + " " + (t.descendingKeySet() == t.descendingKeySet()) + " " + (t.entrySet() == t.entrySet()) + " " + (t.values() == t.values()) + " " + (t.descendingMap().descendingMap() == t) + " " + (t.headMap(3) == t.headMap(3)) + " " + (ts.descendingSet() == ts.descendingSet()) + " " + (t.reversed() == t.descendingMap()));
        System.out.println("reversed " + split(t.reversed().keySet().spliterator()));
    }
}
