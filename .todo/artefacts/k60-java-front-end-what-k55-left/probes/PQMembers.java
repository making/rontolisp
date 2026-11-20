import java.util.*;
import java.util.function.*;

public class PQMembers {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + (e instanceof ClassCastException || e instanceof NullPointerException ? "" : " " + e.getMessage()));
        }
    }

    static String split(Spliterator<?> s) {
        StringBuilder out = new StringBuilder(s.characteristics() + " " + s.estimateSize() + " " + s.getExactSizeIfKnown());
        s.tryAdvance(x -> out.append(" first=").append(x));
        out.append(" then ").append(s.estimateSize()).append(" split=").append(s.trySplit());
        return out.toString();
    }

    public static void main(String[] args) {
        attempt("new(0)", () -> new PriorityQueue<Integer>(0));
        attempt("new(-1)", () -> new PriorityQueue<Integer>(-1));
        attempt("new(1)", () -> new PriorityQueue<Integer>(1));
        attempt("new(0, cmp)", () -> new PriorityQueue<Integer>(0, Comparator.reverseOrder()));
        attempt("new(5, null)", () -> new PriorityQueue<Integer>(5, null).comparator());
        attempt("new(null cmp)", () -> new PriorityQueue<Integer>((Comparator<Integer>) null).comparator());
        attempt("new(coll null)", () -> new PriorityQueue<Integer>((Collection<Integer>) null));
        attempt("new(coll with null)", () -> new PriorityQueue<Integer>(Arrays.asList(1, null, 2)));
        attempt("new(coll mixed)", () -> new PriorityQueue<Object>(Arrays.asList(1, "a")));
        attempt("new(coll one object)", () -> new PriorityQueue<Object>(List.of(new Object())).size());
        attempt("new(coll two objects)", () -> new PriorityQueue<Object>(List.of(new Object(), new Object())).size());
        PriorityQueue<Integer> pq = new PriorityQueue<>();
        attempt("empty", () -> pq + " " + pq.size() + " " + pq.isEmpty() + " " + pq.peek() + " " + pq.poll() + " " + pq.comparator());
        attempt("element", () -> pq.element());
        attempt("remove()", () -> pq.remove());
        attempt("offer null", () -> pq.offer(null));
        attempt("add null", () -> pq.add(null));
        attempt("contains null", () -> pq.contains(null));
        attempt("remove null", () -> pq.remove(null));
        for (int x : new int[] {5, 3, 8, 1, 9, 2, 7, 3, 6, 1}) pq.add(x);
        attempt("filled", () -> pq + " " + pq.size() + " " + pq.peek() + " " + pq.element() + " " + pq.contains(7) + " " + pq.contains(4));
        attempt("toArray", () -> Arrays.toString(pq.toArray()) + Arrays.toString(pq.toArray(new Integer[0])) + Arrays.toString(pq.toArray(new Integer[12])));
        attempt("toArray(n -> )", () -> Arrays.toString(pq.toArray(Integer[]::new)));
        attempt("remove(3)", () -> pq.remove(Integer.valueOf(3)) + " " + pq);
        attempt("remove(4)", () -> pq.remove(Integer.valueOf(4)) + " " + pq);
        attempt("remove(\"x\")", () -> pq.remove("x") + " " + pq);
        attempt("contains(\"x\")", () -> pq.contains("x"));
        attempt("poll all", () -> { StringBuilder b = new StringBuilder(); PriorityQueue<Integer> c = new PriorityQueue<>(pq); while (!c.isEmpty()) b.append(c.poll()).append(' '); return b + "" + c + " " + pq; });
        attempt("forEach", () -> { StringBuilder b = new StringBuilder(); pq.forEach(x -> b.append(x)); return b; });
        attempt("forEach(null)", () -> { pq.forEach(null); return "ok"; });
        attempt("removeIf(null)", () -> pq.removeIf(null));
        attempt("removeAll(null)", () -> pq.removeAll(null));
        attempt("retainAll(null)", () -> pq.retainAll(null));
        attempt("removeIf(odd)", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); return c.removeIf(x -> x % 2 == 1) + " " + c; });
        attempt("removeAll", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); return c.removeAll(List.of(1, 9)) + " " + c; });
        attempt("retainAll", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); return c.retainAll(List.of(1, 9, 3)) + " " + c; });
        attempt("addAll self", () -> pq.addAll(pq));
        attempt("addAll null", () -> pq.addAll(null));
        attempt("addAll with null", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); try { c.addAll(Arrays.asList(4, null, 0)); } catch (NullPointerException e) { return "NPE " + c; } return c; });
        attempt("addAll", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(); return c.addAll(List.of(4, 2, 6)) + " " + c + " " + c.addAll(List.of()); });
        attempt("clear", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); c.clear(); return c + " " + c.size() + " " + c.poll(); });
        attempt("iterator", () -> { StringBuilder b = new StringBuilder(); for (Integer x : pq) b.append(x).append(','); return b; });
        attempt("iterator past", () -> { Iterator<Integer> it = new PriorityQueue<>(List.of(1)).iterator(); it.next(); return it.next(); });
        attempt("iterator remove first", () -> { Iterator<Integer> it = pq.iterator(); it.remove(); return "ok"; });
        attempt("iterator remove twice", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); Iterator<Integer> it = c.iterator(); it.next(); it.remove(); it.remove(); return "ok"; });
        attempt("iterator cme offer", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); Iterator<Integer> it = c.iterator(); it.next(); c.offer(0); return it.hasNext() + " " + it.next(); });
        attempt("iterator cme poll", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); Iterator<Integer> it = c.iterator(); it.next(); c.poll(); return it.next(); });
        attempt("iterator cme last", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(List.of(1, 2)); Iterator<Integer> it = c.iterator(); it.next(); it.next(); c.offer(0); return it.hasNext(); });
        attempt("iterator forEachRemaining", () -> { StringBuilder b = new StringBuilder(); Iterator<Integer> it = pq.iterator(); it.next(); it.forEachRemaining(b::append); return b; });
        attempt("iterator forEachRemaining cme", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); StringBuilder b = new StringBuilder(); Iterator<Integer> it = c.iterator(); it.forEachRemaining(x -> { b.append(x); if (x == 1) c.offer(0); }); return b; });
        attempt("forEach cme", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); StringBuilder b = new StringBuilder(); c.forEach(x -> { b.append(x); if (x == 5) c.offer(0); }); return b; });
        attempt("removeIf cme", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(pq); return c.removeIf(x -> { if (x == 5) c.offer(0); return false; }) + " " + c; });
        attempt("spliterator", () -> split(pq.spliterator()));
        attempt("empty spliterator", () -> split(new PriorityQueue<Integer>().spliterator()));
        attempt("stream", () -> pq.stream().map(x -> x * 10).toList() + " " + pq.stream().sorted().toList());
        attempt("equals", () -> pq.equals(new PriorityQueue<>(pq)) + " " + pq.equals(pq) + " " + (pq.hashCode() == System.identityHashCode(pq)));
        attempt("class", () -> pq.getClass().getName() + " " + pq.getClass().getSuperclass().getName() + " " + (pq instanceof Queue) + " " + (pq instanceof AbstractQueue) + " " + (pq instanceof java.io.Serializable));
        PriorityQueue<String> words = new PriorityQueue<>(String.CASE_INSENSITIVE_ORDER);
        words.addAll(List.of("pear", "Apple", "fig", "apple", "Fig", "banana"));
        attempt("words", () -> words + " " + words.comparator().compare("a", "B") + " " + words.poll() + words.poll() + words.poll() + " " + words);
        PriorityQueue<Integer> rev = new PriorityQueue<>(4, Comparator.reverseOrder());
        rev.addAll(List.of(3, 1, 4, 1, 5, 9, 2, 6));
        attempt("rev", () -> rev + " " + rev.peek() + " " + new PriorityQueue<>(rev) + " " + new PriorityQueue<>(rev).comparator() + " " + (new PriorityQueue<>(rev).comparator() == rev.comparator()));
        attempt("from sorted set", () -> { TreeSet<Integer> t = new TreeSet<>(Comparator.reverseOrder()); t.addAll(List.of(3, 1, 4, 5)); PriorityQueue<Integer> p = new PriorityQueue<>(t); return p + " " + (p.comparator() == t.comparator()) + " " + new PriorityQueue<Integer>((Collection<Integer>) t) + " " + new PriorityQueue<Integer>((Collection<Integer>) t).comparator(); });
        attempt("from pq as collection", () -> { PriorityQueue<Integer> p = new PriorityQueue<Integer>((Collection<Integer>) rev); return p + " " + (p.comparator() == rev.comparator()); });
        attempt("from list", () -> new PriorityQueue<>(List.of(9, 8, 7, 6, 5, 4, 3, 2, 1)) + " " + new PriorityQueue<>(new ArrayDeque<>(List.of(5, 1, 4))));
        attempt("mixed add", () -> { PriorityQueue<Object> m = new PriorityQueue<>(); m.add(1); m.add("a"); return m; });
        attempt("first object add", () -> { PriorityQueue<Object> m = new PriorityQueue<>(); m.add(new Object()); return m.size(); });
        attempt("second object add", () -> { PriorityQueue<Object> m = new PriorityQueue<>(); m.add(new Object()); m.add(new Object()); return m.size(); });
        attempt("comparator throws", () -> { PriorityQueue<Integer> m = new PriorityQueue<>((a, b) -> { throw new IllegalStateException("cmp " + a + " " + b); }); m.add(1); m.add(2); return m; });
        attempt("comparator calls", () -> { StringBuilder b = new StringBuilder(); PriorityQueue<Integer> m = new PriorityQueue<>((x, y) -> { b.append(x).append(y).append(' '); return x - y; }); for (int x : new int[] {5, 3, 8, 1}) m.add(x); b.append("| "); m.poll(); b.append("| "); m.remove(8); return b + "" + m; });
        attempt("toString self", () -> { PriorityQueue<Object> s = new PriorityQueue<>(Comparator.comparing(Object::hashCode)); s.add(s); return s.toString(); });
    }
}
