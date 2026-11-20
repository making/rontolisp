import java.util.*;

// A tree grown large and shrunk again by seeded keys, asked its neighbours on the way.
public class Grow {
    public static void main(String[] args) {
        Random r = new Random(7);
        TreeMap<Integer, Integer> m = new TreeMap<>();
        long digest = 1;
        for (int phase = 0; phase < 4; phase++) {
            for (int i = 0; i < 40000; i++) {
                int k = r.nextInt(200000);
                boolean grow = phase % 2 == 0 ? r.nextInt(10) < 8 : r.nextInt(10) < 2;
                Integer old = grow ? m.put(k, i) : m.remove(k);
                Integer f = m.floorKey(k);
                Integer c = m.higherKey(k);
                digest = digest * 31 + (old == null ? -1 : old) + (f == null ? -2 : f) * 7 + (c == null ? -3 : c) * 13;
            }
            NavigableMap<Integer, Integer> part = m.subMap(50000, true, 150000, false);
            int n = 0;
            long sum = 0;
            for (Map.Entry<Integer, Integer> e : part.descendingMap().entrySet()) {
                n++;
                sum += e.getKey() ^ e.getValue();
            }
            System.out.println(phase + " " + m.size() + " " + part.size() + " " + n + " " + sum + " " + digest + " " + m.firstKey() + " " + m.lastKey());
        }
        TreeSet<Integer> s = new TreeSet<>(m.keySet());
        TreeSet<Integer> t = new TreeSet<>(Comparator.reverseOrder());
        t.addAll(s);
        System.out.println(s.size() + " " + t.size() + " " + s.headSet(100000).size() + " " + t.headSet(100000).size() + " " + s.equals(t) + " " + s.hashCode());
    }
}
