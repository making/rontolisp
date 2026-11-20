import java.util.*;

// The order a JDK's PriorityQueue iterator visits in while it removes, beside a walk of a
// binary heap that keeps the elements a removal moved behind it for after the array.
public class PQWalk {
    record K(int key, int id) implements Comparable<K> {
        public int compareTo(K o) { return Integer.compare(key, o.key); }
        public String toString() { return key + "." + id; }
    }

    static Object[] q;
    static int n;

    static int c(Object a, Object b) { return ((K) a).compareTo((K) b); }

    static void up(int k, Object x) {
        while (k > 0) {
            int p = (k - 1) >>> 1;
            Object e = q[p];
            if (c(x, e) >= 0) break;
            q[k] = e;
            k = p;
        }
        q[k] = x;
    }

    static void down(int k, Object x) {
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

    static Object removeAt(int i) {
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

    public static void main(String[] args) {
        int differ = 0;
        for (int seed = 0; seed < 2000; seed++) {
            Random r = new Random(seed);
            List<K> items = new ArrayList<>();
            int m = 1 + r.nextInt(30);
            for (int i = 0; i < m; i++) items.add(new K(r.nextInt(10), i));
            long removals = r.nextLong();
            PriorityQueue<K> pq = new PriorityQueue<>();
            for (K k : items) pq.offer(k);
            StringBuilder jdk = new StringBuilder();
            Iterator<K> it = pq.iterator();
            int step = 0;
            while (it.hasNext()) {
                K k = it.next();
                jdk.append(k);
                if (((removals >>> (step++ % 64)) & 1) != 0) { it.remove(); jdk.append('x'); }
                jdk.append(' ');
            }
            jdk.append("| ").append(pq);
            // ours
            q = new Object[64];
            n = 0;
            for (K k : items) { n++; up(n - 1, k); }
            StringBuilder ours = new StringBuilder();
            ArrayDeque<Object> later = new ArrayDeque<>();
            int cursor = 0;
            step = 0;
            Object pending = null;
            while (cursor < n || !later.isEmpty()) {
                Object k;
                int last = -1;
                if (cursor < n) { last = cursor; k = q[cursor++]; }
                else { k = later.poll(); }
                ours.append(k);
                if (((removals >>> (step++ % 64)) & 1) != 0) {
                    if (last >= 0) {
                        Object moved = removeAt(last);
                        if (moved == null) cursor--;
                        else later.add(moved);
                    } else {
                        for (int i = 0; i < n; i++) if (q[i] == k) { removeAt(i); break; }
                    }
                    ours.append('x');
                }
                ours.append(' ');
            }
            StringJoiner j = new StringJoiner(", ", "[", "]");
            for (int i = 0; i < n; i++) j.add(String.valueOf(q[i]));
            ours.append("| ").append(j);
            if (!jdk.toString().equals(ours.toString())) {
                if (differ < 4) System.out.println("seed " + seed + "\n  jdk  " + jdk + "\n  ours " + ours);
                differ++;
            }
        }
        System.out.println(differ + " of 2000 walks differ");
    }
}
