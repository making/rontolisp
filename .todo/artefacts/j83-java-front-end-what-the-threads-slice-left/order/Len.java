import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Len {
    static final int[] PROBES = { 1, 17, 33, 65, 129, 257, 513 };
    // the table's length, read from where the keys 1 + 2^j walk: those of one chain with 1 come first
    static int length(Map<Integer, Integer> map) {
        List<Integer> walk = new ArrayList<>(map.keySet());
        int found = -1;
        for (int len = 4; len <= 1024; len <<= 1) {
            boolean ascending = true;
            for (int i = 1; i < walk.size() && ascending; i++) {
                ascending = (walk.get(i - 1) & (len - 1)) <= (walk.get(i) & (len - 1));
            }
            if (ascending) {
                if (found > 0) return -found;
                found = len;
            }
        }
        return found;
    }
    static void fill(Map<Integer, Integer> map, int n, int from) {
        for (int i = 0; i < n; i++) {
            if (i < PROBES.length && from == 0) map.put(PROBES[i], i); else map.put((from + 2 * i) % 8192 + (from > 0 ? 0 : 0), i);
        }
    }
    public static void main(String[] args) {
        for (int b : new int[] { 0, 9, 11, 12, 13, 20, 23, 24, 30, 47, 48 }) {
            StringBuilder row = new StringBuilder("base " + b + ":");
            int last = -9;
            for (int s = 0; s <= 100; s++) {
                ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
                fill(map, b, 0);
                Map<Integer, Integer> more = new HashMap<>();
                if (b == 0) fill(more, s, 0); else fill(more, s, 4000);
                map.putAll(more);
                int len = map.size() < 2 ? -1 : length(map);
                if (len != last) { row.append(" s>=" + s + "->" + len); last = len; }
            }
            System.out.println(row);
        }
        StringBuilder row = new StringBuilder("copy:");
        int last = -9;
        for (int s = 2; s <= 100; s++) {
            Map<Integer, Integer> more = new HashMap<>();
            fill(more, s, 0);
            int len = length(new ConcurrentHashMap<>(more));
            if (len != last) { row.append(" s>=" + s + "->" + len); last = len; }
        }
        System.out.println(row);
        row = new StringBuilder("puts:");
        last = -9;
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
        for (int s = 1; s <= 100; s++) {
            if (s <= PROBES.length) map.put(PROBES[s - 1], s); else map.put(2 * s, s);
            int len = map.size() < 2 ? -1 : length(map);
            if (len != last) { row.append(" s>=" + s + "->" + len); last = len; }
        }
        System.out.println(row);
    }
}
