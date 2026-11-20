import java.util.*;

// A seeded run of every change a PriorityQueue takes, its text after each: run under a
// JDK and under the subset, the two transcripts are compared.
public class PQSeeded {
    record K(int key, int id) implements Comparable<K> {
        public int compareTo(K o) {
            return Integer.compare(key, o.key);
        }

        public String toString() {
            return key + "." + id;
        }
    }

    public static void main(String[] args) {
        StringBuilder out = new StringBuilder();
        for (int seed = 0; seed < 400; seed++) {
            Random r = new Random(seed);
            int kind = seed % 4;
            Comparator<K> rev = Comparator.comparingInt(K::key).reversed();
            PriorityQueue<K> pq = kind == 0 ? new PriorityQueue<>(rev) : kind == 1 ? new PriorityQueue<>(3) : new PriorityQueue<>();
            if (kind == 3) {
                List<K> start = new ArrayList<>();
                for (int i = 0, n = r.nextInt(30); i < n; i++) {
                    start.add(new K(r.nextInt(9), 1000 + i));
                }
                pq = new PriorityQueue<>(start);
            }
            int id = 0;
            out.append("seed ").append(seed).append(' ').append(pq).append('\n');
            for (int step = 0; step < 120; step++) {
                int op = r.nextInt(12);
                if (op < 5) {
                    pq.offer(new K(r.nextInt(9), id++));
                    out.append("+ ");
                }
                else if (op < 7) {
                    out.append("poll ").append(pq.poll()).append(' ');
                }
                else if (op < 8) {
                    out.append("remove ").append(pq.remove(new K(r.nextInt(9), -1))).append(' ');
                }
                else if (op < 9) {
                    int key = r.nextInt(9);
                    out.append("removeIf ").append(pq.removeIf(x -> x.key() == key)).append(' ');
                }
                else if (op < 10) {
                    List<K> some = new ArrayList<>();
                    for (int i = 0, n = r.nextInt(5); i < n; i++) {
                        some.add(new K(r.nextInt(9), id++));
                    }
                    out.append("addAll ").append(pq.addAll(some)).append(' ');
                }
                else if (op < 11) {
                    long removals = r.nextLong();
                    Iterator<K> it = pq.iterator();
                    int i = 0;
                    out.append("walk");
                    while (it.hasNext()) {
                        K k = it.next();
                        out.append(' ').append(k);
                        if (((removals >>> (i++ % 64)) & 1) != 0 && r.nextInt(3) == 0) {
                            it.remove();
                            out.append('x');
                        }
                    }
                    out.append(' ');
                }
                else {
                    PriorityQueue<K> copy = new PriorityQueue<>(pq);
                    out.append("copy ").append(copy).append(' ').append(copy.poll()).append(' ');
                }
                out.append(pq).append(' ').append(pq.size()).append(' ').append(pq.peek()).append('\n');
            }
        }
        System.out.print(out);
    }
}
