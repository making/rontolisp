import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Chain {
    public static void main(String[] args) {
        String[] ways = { "put new", "put last", "put first", "putIfAbsent new", "putIfAbsent last", "computeIfAbsent new",
                "computeIfAbsent last", "compute new", "compute last", "merge new", "merge last", "replace last", "computeIfPresent last", "remove+put", "get last", "put 7th", "merge 7th", "compute first" };
        for (int w = 0; w < ways.length; w++) {
            StringBuilder row = new StringBuilder(ways[w] + ":");
            for (int chain = 6; chain <= 9; chain++) {
                ConcurrentHashMap<Integer, Integer> m = new ConcurrentHashMap<>();
                m.put(1, 0);
                int n = (w % 2 == 1 && w < 11) || w == 5 || w == 7 || w == 9 || w == 0 || w == 3 ? chain : chain;
                boolean adds = w == 0 || w == 3 || w == 5 || w == 7 || w == 9;
                int have = adds ? chain - 1 : chain;
                if (have > 8) { row.append(" -"); continue; }
                for (int i = 0; i < have; i++) m.put(i * 16, i);
                int fresh = have * 16, last = (have - 1) * 16;
                switch (w) {
                    case 0 -> m.put(fresh, 1);
                    case 1 -> m.put(last, 99);
                    case 2 -> m.put(0, 99);
                    case 3 -> m.putIfAbsent(fresh, 1);
                    case 4 -> m.putIfAbsent(last, 99);
                    case 5 -> m.computeIfAbsent(fresh, k -> 1);
                    case 6 -> m.computeIfAbsent(last, k -> 1);
                    case 7 -> m.compute(fresh, (k, v) -> 1);
                    case 8 -> m.compute(last, (k, v) -> 1);
                    case 9 -> m.merge(fresh, 1, Integer::sum);
                    case 10 -> m.merge(last, 1, Integer::sum);
                    case 11 -> m.replace(last, 5);
                    case 12 -> m.computeIfPresent(last, (k, v) -> 1);
                    case 13 -> { m.remove(0); m.put(0, 1); }
                    case 14 -> m.get(last);
                    case 15 -> m.put(6 * 16, 1);
                    case 16 -> m.merge(6 * 16, 1, Integer::sum);
                    default -> m.compute(0, (k, v) -> 1);
                }
                m.put(17, 0);
                m.put(33, 0);
                m.put(65, 0);
                m.put(129, 0);
                List<Integer> walk = new ArrayList<>(m.keySet());
                // the table's length: the first of 16, 32, 64, 128 whose key 1 + length walks right after 1
                int at = walk.indexOf(1);
                int next = walk.get(at + 1);
                String grew = next == 17 ? "=" : next == 33 ? "32" : next == 65 ? "64" : next == 129 ? "128" : ">";
                row.append(" ").append(m.size() - 5).append(grew);
            }
            System.out.println(row);
        }
    }
}
