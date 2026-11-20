import java.util.*;
import java.util.function.*;

public class Calls {
    static StringBuilder log = new StringBuilder();

    static Comparator<Integer> told = (a, b) -> {
        log.append(a).append('?').append(b).append(' ');
        return Integer.compare(a, b);
    };

    static void show(String label, Supplier<Object> s) {
        log.setLength(0);
        Object r;
        try {
            r = s.get();
        } catch (RuntimeException e) {
            r = e.getClass().getSimpleName();
        }
        System.out.println(label + " -> " + r + " : " + log);
    }

    public static void main(String[] args) {
        TreeMap<Integer, String> m = new TreeMap<>(told);
        show("put 5 empty", () -> m.put(5, "a"));
        show("put 3", () -> m.put(3, "b"));
        show("put 8", () -> m.put(8, "c"));
        show("put 5 again", () -> m.put(5, "d"));
        show("get 8", () -> m.get(8));
        show("headMap 4", () -> m.headMap(4));
        show("tailMap 4", () -> m.tailMap(4, false));
        show("subMap 2 6", () -> m.subMap(2, 6));
        show("subMap 2 6 put 4", () -> m.subMap(2, 6).put(4, "e"));
        show("subMap 2 6 get 4", () -> m.subMap(2, 6).get(4));
        show("subMap 2 6 toString", () -> m.subMap(2, 6).toString());
        show("subMap 2 6 size", () -> m.subMap(2, 6).size());
        show("subMap 2 6 firstKey", () -> m.subMap(2, 6).firstKey());
        show("descendingMap", () -> m.descendingMap());
        show("descendingMap firstKey", () -> m.descendingMap().firstKey());
        show("floorKey 6", () -> m.floorKey(6));
        TreeMap<Integer, String> e = new TreeMap<>(told);
        show("get empty", () -> e.get(1));
        show("remove empty", () -> e.remove(1));
        show("ceilingKey empty", () -> e.ceilingKey(1));
        show("headMap empty", () -> e.headMap(1));
        show("putAll into empty", () -> {
            e.putAll(new TreeMap<>(Map.of(1, "x", 2, "y", 3, "z")));
            return e;
        });
        show("copy of sorted", () -> new TreeMap<>((SortedMap<Integer, String>) m));
        TreeSet<Integer> s = new TreeSet<>(told);
        show("set add 1 empty", () -> s.add(1));
        show("set addAll", () -> s.addAll(List.of(4, 2)));
        TreeSet<Integer> s2 = new TreeSet<>(told);
        show("set addAll sorted into empty", () -> s2.addAll(s));
        show("new TreeSet(sorted)", () -> new TreeSet<>((SortedSet<Integer>) s));
        for (int i = 0; i < 20; i++) {
            m.put(i * 7 % 23, "v");
        }
        show("get 11 of 22", () -> m.get(11));
        show("remove 11", () -> m.remove(11));
    }
}
