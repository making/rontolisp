import java.util.*;

// Seeded random operations on a TreeMap, a TreeSet and their views; a digest of the
// answers is printed every so many operations.
public class Stress {
    static long digest = 17;

    static void mix(Object o) {
        digest = digest * 31 + (o == null ? 7 : o.hashCode());
    }

    public static void main(String[] args) {
        Random r = new Random(42);
        for (int round = 0; round < 6; round++) {
            TreeMap<Integer, Integer> m = round % 2 == 0 ? new TreeMap<>() : new TreeMap<>(Comparator.reverseOrder());
            TreeSet<Integer> s = new TreeSet<>();
            int range = round < 3 ? 50 : 2000;
            for (int op = 0; op < 4000; op++) {
                int k = r.nextInt(range);
                int k2 = r.nextInt(range);
                int lo = Math.min(k, k2);
                int hi = Math.max(k, k2);
                NavigableMap<Integer, Integer> v = m;
                int choice = r.nextInt(8);
                if (choice == 1) {
                    v = m.comparator() == null ? m.subMap(lo, r.nextBoolean(), hi, r.nextBoolean()) : m.subMap(hi, r.nextBoolean(), lo, r.nextBoolean());
                } else if (choice == 2) {
                    v = m.headMap(k, r.nextBoolean());
                } else if (choice == 3) {
                    v = m.tailMap(k, r.nextBoolean()).descendingMap();
                } else if (choice == 4) {
                    v = m.descendingMap();
                }
                int what = r.nextInt(16);
                try {
                    switch (what) {
                        case 0, 1, 2, 3 -> mix(v.put(k2, op));
                        case 4 -> mix(v.remove(k2));
                        case 5 -> mix(v.get(k2));
                        case 6 -> mix(v.floorKey(k2) + "/" + v.ceilingKey(k2) + "/" + v.lowerKey(k2) + "/" + v.higherKey(k2));
                        case 7 -> mix(v.firstEntry() + "/" + v.lastEntry() + "/" + v.size());
                        case 8 -> mix(v.pollFirstEntry());
                        case 9 -> mix(v.pollLastEntry());
                        case 10 -> {
                            Iterator<Map.Entry<Integer, Integer>> it = v.entrySet().iterator();
                            int n = 0;
                            while (it.hasNext()) {
                                Map.Entry<Integer, Integer> e = it.next();
                                if ((e.getKey() + op) % 3 == 0) {
                                    it.remove();
                                } else {
                                    e.setValue(e.getValue() + 1);
                                }
                                n++;
                            }
                            mix(n);
                        }
                        case 11 -> mix(v.toString());
                        case 12 -> {
                            Iterator<Integer> it = v.descendingKeySet().iterator();
                            int n = 0;
                            while (it.hasNext() && n < 5) {
                                mix(it.next());
                                if (n == 2) {
                                    it.remove();
                                }
                                n++;
                            }
                        }
                        case 13 -> mix(v.hashCode() + v.keySet().hashCode() + new ArrayList<>(v.values()).hashCode());
                        case 14 -> {
                            mix(s.add(k) + "/" + s.remove(k2) + "/" + s.headSet(k).size() + "/" + s.tailSet(k2, false).size());
                            mix(s.descendingSet().ceiling(k));
                        }
                        default -> mix(v.containsKey(k) + "/" + v.containsValue(op - 1) + "/" + v.isEmpty());
                    }
                } catch (IllegalArgumentException | NoSuchElementException e) {
                    mix(e.getClass().getSimpleName() + e.getMessage());
                }
                if (op % 1000 == 999) {
                    System.out.println(round + " " + op + " " + m.size() + " " + s.size() + " " + digest);
                }
            }
            if (m.size() < 40) {
                System.out.println(m + " " + s.headSet(10));
            }
        }
        // a large map: order, sizes and removal of every other key
        TreeMap<Integer, Integer> big = new TreeMap<>();
        for (int i = 0; i < 200000; i++) {
            big.put((int) ((i * 2654435761L) % 1000003), i);
        }
        long sum = 0;
        int prev = -1;
        boolean ordered = true;
        for (int key : big.keySet()) {
            ordered &= key > prev;
            prev = key;
            sum += key;
        }
        Iterator<Integer> it = big.keySet().iterator();
        while (it.hasNext()) {
            if (it.next() % 2 == 0) {
                it.remove();
            }
        }
        System.out.println(big.size() + " " + sum + " " + ordered + " " + big.firstKey() + " " + big.lastKey() + " " + big.subMap(1000, 5000).size() + " " + big.headMap(500000).size());
    }
}
