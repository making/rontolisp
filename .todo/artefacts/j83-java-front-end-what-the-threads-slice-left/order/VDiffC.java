import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class VDiffC {
    // a map walked in the order its keys were put, the same under a JDK and here
    static class Listed extends AbstractMap<Integer, Integer> {
        final List<Map.Entry<Integer, Integer>> pairs = new ArrayList<>();

        public Integer put(Integer key, Integer value) {
            for (Map.Entry<Integer, Integer> pair : pairs) {
                if (pair.getKey().equals(key)) {
                    return pair.getValue();
                }
            }
            pairs.add(Map.entry(key, value));
            return null;
        }

        public Set<Map.Entry<Integer, Integer>> entrySet() {
            return new AbstractSet<>() {
                public int size() {
                    return pairs.size();
                }

                public Iterator<Map.Entry<Integer, Integer>> iterator() {
                    return pairs.iterator();
                }
            };
        }
    }

    static long walk(Map<Integer, Integer> map) {
        long h = 7;
        for (int k : map.keySet()) {
            h = h * 1000003 + k;
        }
        return h;
    }

    public static void main(String[] args) {
        // random operations
        for (int seed = 0; seed < 40; seed++) {
            Random r = new Random(seed);
            ConcurrentHashMap<Integer, Integer> map = seed % 3 == 2 ? new ConcurrentHashMap<>(r.nextInt(40)) : new ConcurrentHashMap<>();
            int range = new int[] { 40, 60, 100, 25 }[seed % 4];
            int stride = new int[] { 16, 8, 4, 32 }[seed % 4];
            long h = 0;
            StringBuilder steps = new StringBuilder();
            for (int n = 0; n < 300; n++) {
                int k = r.nextInt(range) * stride ;
                switch (r.nextInt(10)) {
                    case 0, 1, 2, 3 -> map.put(k, n);
                    case 4 -> map.remove(k);
                    case 5 -> map.computeIfAbsent(k, key -> 1);
                    case 6 -> map.merge(k, 1, Integer::sum);
                    case 7 -> map.compute(k, (key, v) -> v == null ? 1 : null);
                    case 8 -> map.putIfAbsent(k, n);
                    default -> {
                        if (r.nextInt(6) == 0) {
                            Listed more = new Listed();
                            int count = r.nextInt(30);
                            for (int i = 0; i < count; i++) {
                                more.put(r.nextInt(range) * stride, i);
                            }
                            if (r.nextBoolean()) {
                                map.putAll(more);
                            }
                            else {
                                map = new ConcurrentHashMap<>(more);
                            }
                        }
                    }
                }
                System.out.println(seed + " " + n + " " + map.keySet());
                h = h * 31 + walk(map);
                if (n % 30 == 29) {
                    steps.append(h % 9973).append(' ');
                }
            }

        }
    }
}
