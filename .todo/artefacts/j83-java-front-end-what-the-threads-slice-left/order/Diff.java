import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class Diff {
    static long walk(Map<Integer, Integer> map) {
        long h = 7;
        for (int k : map.keySet()) {
            h = h * 1000003 + k;
        }
        return h;
    }

    public static void main(String[] args) {
        // sized constructors: where the order first departs from the first-put order tells the table
        StringBuilder sized = new StringBuilder();
        for (int cap = 0; cap <= 70; cap++) {
            ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>(cap);
            Random r = new Random(cap);
            long h = 0;
            for (int n = 0; n < 150; n++) {
                map.put(r.nextInt(5000), n);
                h = h * 31 + walk(map);
            }
            sized.append(cap).append(':').append(h % 9973).append(' ');
        }
        System.out.println(sized);
        // random operations
        for (int seed = 0; seed < 40; seed++) {
            Random r = new Random(seed);
            ConcurrentHashMap<Integer, Integer> map = seed % 4 == 3 ? new ConcurrentHashMap<>(r.nextInt(40)) : new ConcurrentHashMap<>();
            int range = seed % 2 == 0 ? 300 : 3000;
            int stride = seed % 3 == 0 ? 16 : seed % 3 == 1 ? 1 : 64;
            long h = 0;
            StringBuilder steps = new StringBuilder();
            for (int n = 0; n < 300; n++) {
                int k = r.nextInt(range) * stride + (seed % 5 == 4 ? r.nextInt(2) << 20 : 0);
                switch (r.nextInt(10)) {
                    case 0, 1, 2, 3 -> map.put(k, n);
                    case 4 -> map.remove(k);
                    case 5 -> map.computeIfAbsent(k, key -> 1);
                    case 6 -> map.merge(k, 1, Integer::sum);
                    case 7 -> map.compute(k, (key, v) -> v == null ? 1 : null);
                    case 8 -> map.putIfAbsent(k, n);
                    default -> {
                        if (r.nextInt(6) == 0) {
                            Map<Integer, Integer> more = new HashMap<>();
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
                h = h * 31 + walk(map);
                if (n % 30 == 29) {
                    steps.append(h % 9973).append(' ');
                }
            }
            System.out.println(seed + " " + map.size() + " " + steps);
        }
    }
}
