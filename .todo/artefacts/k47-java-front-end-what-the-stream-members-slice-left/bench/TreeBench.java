import java.util.*;

public class TreeBench {
    public static void main(String[] args) {
        int n = 200000;
        int[] keys = new int[n];
        long x = 12345;
        for (int i = 0; i < n; i++) {
            x = (x * 6364136223846793005L + 1442695040888963407L);
            keys[i] = (int) (x >>> 33);
        }
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            Map<Integer, Integer> tree = new TreeMap<>();
            for (int i = 0; i < n; i++) {
                tree.put(keys[i], i);
            }
            long hits = 0;
            for (int i = 0; i < n; i++) {
                if (tree.get(keys[(i * 7) % n]) != null) {
                    hits++;
                }
            }
            long sum = 0;
            for (int k : tree.keySet()) {
                sum += k;
            }
            for (int i = 0; i < n; i += 2) {
                tree.remove(keys[i]);
            }
            long t1 = System.nanoTime();
            Map<Integer, Integer> hash = new HashMap<>();
            for (int i = 0; i < n; i++) {
                hash.put(keys[i], i);
            }
            for (int i = 0; i < n; i++) {
                if (hash.get(keys[(i * 7) % n]) != null) {
                    hits++;
                }
            }
            for (int k : hash.keySet()) {
                sum += k;
            }
            for (int i = 0; i < n; i += 2) {
                hash.remove(keys[i]);
            }
            long t2 = System.nanoTime();
            System.out.println("round " + round + ": tree " + (t1 - t0) / 1000000 + " ms, hash " + (t2 - t1) / 1000000 + " ms " + hits + " " + sum + " " + tree.size() + " " + hash.size());
        }
    }
}
