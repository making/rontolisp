import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class SeqDeque {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    static String marks(Spliterator<?> s) {
        int c = s.characteristics();
        StringBuilder b = new StringBuilder();
        String[] names = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] bits = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        for (int i = 0; i < bits.length; i++) {
            if ((c & bits[i]) != 0) {
                b.append(b.length() == 0 ? "" : "|").append(names[i]);
            }
        }
        return b.toString();
    }

    static String split(Spliterator<?> s) {
        StringBuilder out = new StringBuilder(marks(s) + " " + s.estimateSize());
        s.tryAdvance(x -> out.append(" first=").append(x));
        out.append(" then ").append(s.estimateSize());
        return out.toString();
    }

    static void deque(String label, Deque<Integer> d) {
        Deque<Integer> r = d.reversed();
        System.out.println(label + " " + d + " " + r + " " + (r.reversed() == d) + " " + r.size() + " " + r.isEmpty() + " " + r.equals(d) + " " + r.contains(3));
        attempt(label + " peek", () -> r.peek() + " " + r.peekFirst() + " " + r.peekLast() + " " + r.element() + " " + r.getFirst() + " " + r.getLast());
        attempt(label + " add(8)", () -> r.add(8) + " " + d + " " + r);
        attempt(label + " offer(9)", () -> r.offer(9) + " " + d + " " + r);
        attempt(label + " push(10)", () -> { r.push(10); return d + " " + r; });
        attempt(label + " addFirst(11)", () -> { r.addFirst(11); return d + " " + r; });
        attempt(label + " addLast(12)", () -> { r.addLast(12); return d + " " + r; });
        attempt(label + " offerFirst(13)", () -> r.offerFirst(13) + " " + d + " " + r);
        attempt(label + " offerLast(14)", () -> r.offerLast(14) + " " + d + " " + r);
        attempt(label + " pop", () -> r.pop() + " " + d + " " + r);
        attempt(label + " poll", () -> r.poll() + " " + d + " " + r);
        attempt(label + " remove()", () -> r.remove() + " " + d + " " + r);
        attempt(label + " pollFirst", () -> r.pollFirst() + " " + d + " " + r);
        attempt(label + " pollLast", () -> r.pollLast() + " " + d + " " + r);
        attempt(label + " removeFirst", () -> r.removeFirst() + " " + d + " " + r);
        attempt(label + " removeLast", () -> r.removeLast() + " " + d + " " + r);
        attempt(label + " addAll", () -> r.addAll(List.of(2, 20, 2, 21)) + " " + d + " " + r);
        attempt(label + " remove(2)", () -> r.remove(Integer.valueOf(2)) + " " + d + " " + r);
        attempt(label + " removeFirstOccurrence(2)", () -> r.removeFirstOccurrence(2) + " " + d + " " + r);
        attempt(label + " removeLastOccurrence(20)", () -> r.removeLastOccurrence(20) + " " + d + " " + r);
        attempt(label + " iterator", () -> { StringBuilder b = new StringBuilder(); for (Integer x : r) b.append(x).append(','); return b; });
        attempt(label + " descendingIterator", () -> { StringBuilder b = new StringBuilder(); Iterator<Integer> it = r.descendingIterator(); while (it.hasNext()) b.append(it.next()).append(','); return b; });
        attempt(label + " iterator remove", () -> { Iterator<Integer> it = r.iterator(); it.next(); it.remove(); return d + " " + r; });
        attempt(label + " descendingIterator remove", () -> { Iterator<Integer> it = r.descendingIterator(); it.next(); it.remove(); return d + " " + r; });
        attempt(label + " removeIf", () -> r.removeIf(x -> x > 12) + " " + d + " " + r);
        attempt(label + " retainAll", () -> r.retainAll(List.of(1, 2, 3, 4, 5, 8, 9, 10, 11, 12)) + " " + d + " " + r);
        attempt(label + " removeAll", () -> r.removeAll(List.of(1)) + " " + d + " " + r);
        attempt(label + " stream", () -> r.stream().map(x -> x * 2).collect(Collectors.toList()));
        attempt(label + " toArray", () -> Arrays.toString(r.toArray()) + Arrays.toString(r.toArray(new Integer[0])));
        attempt(label + " spliterator", () -> split(r.spliterator()));
        attempt(label + " forEach", () -> { StringBuilder b = new StringBuilder(); r.forEach(x -> b.append(x).append(';')); return b; });
        attempt(label + " hashCode", () -> (r.hashCode() == System.identityHashCode(r)) + " " + r.equals(r) + " " + r.equals(d.reversed()));
        attempt(label + " walk while adding", () -> { int n = 0; for (Integer x : r) { if (n++ < 3) d.addFirst(x); } return d; });
        attempt(label + " clear", () -> { r.clear(); return d + " " + r + " " + r.size(); });
        attempt(label + " empty getFirst", () -> r.getFirst());
        attempt(label + " empty removeLast", () -> r.removeLast());
        attempt(label + " empty element", () -> r.element());
        attempt(label + " empty pop", () -> r.pop());
        attempt(label + " empty poll", () -> r.poll() + " " + r.pollLast() + " " + r.peek() + " " + r.peekLast());
        attempt(label + " add null", () -> r.add(null) + " " + d);
    }

    public static void main(String[] args) {
        deque("ArrayDeque", new ArrayDeque<>(List.of(1, 2, 3, 4, 5)));
        deque("LinkedList", new LinkedList<>(List.of(1, 2, 3, 4, 5)));
        deque("ConcurrentLinkedDeque", new ConcurrentLinkedDeque<>(List.of(1, 2, 3, 4, 5)));
        deque("LinkedBlockingDeque", new LinkedBlockingDeque<>(List.of(1, 2, 3, 4, 5)));
        LinkedBlockingDeque<Integer> bounded = new LinkedBlockingDeque<>(3);
        bounded.add(1);
        bounded.add(2);
        Deque<Integer> br = bounded.reversed();
        attempt("bounded", () -> br.offer(3) + " " + br.offer(4) + " " + bounded);
        attempt("bounded add", () -> br.add(5));
        attempt("bounded push", () -> { br.push(5); return bounded; });
        // a deque of the program's own
        Deque<String> mine = new ArrayDeque<>(List.of("a", "b", "c")) {
            public String peekFirst() { return "mine:" + super.peekFirst(); }
        };
        attempt("mine", () -> mine.reversed() + " " + mine.reversed().peekLast() + " " + mine.reversed().peekFirst());
    }
}
