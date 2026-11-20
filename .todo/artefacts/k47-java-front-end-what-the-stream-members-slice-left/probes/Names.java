import java.util.*;
import java.util.concurrent.*;

public class Names {
    public static void main(String[] args) {
        TreeMap<Integer, String> t = new TreeMap<>(Map.of(1, "a", 2, "b", 3, "c"));
        TreeSet<Integer> s = new TreeSet<>(t.keySet());
        ConcurrentSkipListMap<Integer, String> c = new ConcurrentSkipListMap<>(t);
        ConcurrentSkipListSet<Integer> cs = new ConcurrentSkipListSet<>(s);
        Object[] all = {t.keySet(), t.descendingKeySet(), t.navigableKeySet(), t.headMap(2), t.descendingMap(), t.headMap(2).keySet(), t.values(), t.entrySet(), t.headMap(2).entrySet(),
            s.headSet(2), s.descendingSet(), s.descendingSet().headSet(2),
            c.keySet(), c.navigableKeySet(), c.descendingKeySet(), c.headMap(2), c.descendingMap(), c.headMap(2).keySet(), c.values(), c.entrySet(),
            cs.headSet(2), cs.descendingSet(), t.firstEntry(), c.firstEntry(), t.entrySet().iterator().next(), c.entrySet().iterator().next()};
        for (Object o : all) {
            System.out.println(o.getClass().getName() + " " + o);
        }
        try { t.keySet().add(4); } catch (RuntimeException e) { System.out.println(e); }
        try { c.keySet().add(4); } catch (RuntimeException e) { System.out.println(e); }
        try { t.values().add("x"); } catch (RuntimeException e) { System.out.println(e); }
        try { t.entrySet().add(Map.entry(9, "x")); } catch (RuntimeException e) { System.out.println(e); }
        System.out.println(t.keySet().remove(1) + " " + t + " " + c.keySet().remove(1) + " " + c + " " + c.navigableKeySet().pollFirst() + " " + c);
    }
}
