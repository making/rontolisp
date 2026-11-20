import java.util.*;
import java.util.function.*;

// A binary heap of our own beside java.util.PriorityQueue: after every operation of a
// seeded run, the two print the same text, poll the same element, walk the same order.
public class PQOrder {
    record K(int key, int id) implements Comparable<K> {
        public int compareTo(K o) { return Integer.compare(key, o.key); }
        public String toString() { return key + "." + id; }
    }

    static final class Heap {
        Object[] q = new Object[11];
        int n;
        final Comparator<Object> cmp;
        final boolean bulkCompact;
        Heap(Comparator<Object> cmp, boolean bulkCompact) { this.cmp = cmp; this.bulkCompact = bulkCompact; }
        int c(Object a, Object b) { return cmp == null ? ((Comparable) a).compareTo(b) : cmp.compare(a, b); }
        void up(int k, Object x) {
            while (k > 0) {
                int p = (k - 1) >>> 1;
                Object e = q[p];
                if (c(x, e) >= 0) break;
                q[k] = e;
                k = p;
            }
            q[k] = x;
        }
        void down(int k, Object x) {
            int half = n >>> 1;
            while (k < half) {
                int ch = 2 * k + 1;
                Object m = q[ch];
                int r = ch + 1;
                if (r < n && c(m, q[r]) > 0) m = q[ch = r];
                if (c(x, m) <= 0) break;
                q[k] = m;
                k = ch;
            }
            q[k] = x;
        }
        void offer(Object x) {
            if (n == q.length) q = Arrays.copyOf(q, q.length * 2);
            up(n++, x);
        }
        Object poll() {
            if (n == 0) return null;
            Object r = q[0];
            int s = --n;
            Object x = q[s];
            q[s] = null;
            if (s > 0) down(0, x);
            return r;
        }
        Object removeAt(int i) {
            int s = --n;
            if (s == i) { q[i] = null; return null; }
            Object moved = q[s];
            q[s] = null;
            down(i, moved);
            if (q[i] == moved) {
                up(i, moved);
                if (q[i] != moved) return moved;
            }
            return null;
        }
        boolean remove(Object o) {
            for (int i = 0; i < n; i++) if (o.equals(q[i])) { removeAt(i); return true; }
            return false;
        }
        void heapify() { for (int i = (n >>> 1) - 1; i >= 0; i--) down(i, q[i]); }
        boolean removeIf(Predicate<Object> p) {
            if (bulkCompact) {
                int w = 0;
                for (int i = 0; i < n; i++) if (!p.test(q[i])) q[w++] = q[i];
                boolean changed = w != n;
                for (int i = w; i < n; i++) q[i] = null;
                n = w;
                if (changed) heapify();
                return changed;
            }
            boolean changed = false;
            for (int i = 0; i < n; ) {
                if (p.test(q[i])) { Object moved = removeAt(i); changed = true; if (moved != null) {} }
                else i++;
            }
            return changed;
        }
        public String toString() { StringJoiner j = new StringJoiner(", ", "[", "]"); for (int i = 0; i < n; i++) j.add(String.valueOf(q[i])); return j.toString(); }
    }

    public static void main(String[] args) {
        for (boolean compact : new boolean[] {true, false}) {
            int differ = 0, steps = 0;
            for (int seed = 0; seed < 300; seed++) {
                Random r = new Random(seed);
                boolean byCmp = seed % 3 == 0;
                Comparator<K> rev = Comparator.comparingInt(K::key).reversed();
                PriorityQueue<K> pq = byCmp ? new PriorityQueue<>(rev) : new PriorityQueue<>();
                Heap h = new Heap(byCmp ? (Comparator) rev : null, compact);
                int id = 0;
                for (int step = 0; step < 200; step++) {
                    int op = r.nextInt(10);
                    String a, b;
                    if (op < 5) { K k = new K(r.nextInt(8), id++); pq.offer(k); h.offer(k); a = pq.toString(); b = h.toString(); }
                    else if (op < 7) { a = pq.poll() + " " + pq; b = h.poll() + " " + h; }
                    else if (op < 8) { int key = r.nextInt(8); K k = new K(key, -1); a = pq.remove(k) + " " + pq; b = h.remove(k) + " " + h; }
                    else if (op < 9) { int key = r.nextInt(8); a = pq.removeIf(x -> x.key() == key) + " " + pq; b = h.removeIf(x -> ((K) x).key() == key) + " " + h; }
                    else { List<K> some = new ArrayList<>(); int m = r.nextInt(6); for (int i = 0; i < m; i++) some.add(new K(r.nextInt(8), id++)); pq.addAll(some); for (K k : some) h.offer(k); a = pq.toString(); b = h.toString(); }
                    steps++;
                    if (!a.equals(b)) { if (differ < 3) System.out.println("seed " + seed + " step " + step + " op " + op + "\n  jdk  " + a + "\n  heap " + b); differ++; break; }
                }
            }
            System.out.println("compact=" + compact + ": " + differ + " of 300 runs differ (" + steps + " steps)");
        }
        // construction from collections
        int differ = 0;
        for (int seed = 0; seed < 300; seed++) {
            Random r = new Random(seed);
            List<K> items = new ArrayList<>();
            int m = r.nextInt(40);
            for (int i = 0; i < m; i++) items.add(new K(r.nextInt(10), i));
            PriorityQueue<K> pq = new PriorityQueue<>(items);
            Heap h = new Heap(null, true);
            h.q = items.toArray(new Object[Math.max(1, items.size())]);
            h.n = items.size();
            h.heapify();
            if (!pq.toString().equals(h.toString())) { if (differ < 3) System.out.println("from list seed " + seed + "\n  jdk  " + pq + "\n  heap " + h); differ++; }
        }
        System.out.println("from a collection: " + differ + " of 300 differ");
    }
}
