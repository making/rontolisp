import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class SeqList {
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

    static void list(String label, List<Integer> l) {
        List<Integer> r = l.reversed();
        System.out.println(label + " " + l + " " + r + " " + (r.reversed() == l) + " " + r.size() + " " + r.isEmpty() + " " + r.equals(new ArrayList<>(r)) + " " + (r.hashCode() == new ArrayList<>(r).hashCode()));
        attempt(label + " get(0)", () -> r.get(0));
        attempt(label + " get(-1)", () -> r.get(-1));
        attempt(label + " get(size)", () -> r.get(r.size()));
        attempt(label + " getFirst", () -> r.getFirst());
        attempt(label + " getLast", () -> r.getLast());
        attempt(label + " indexOf(2)", () -> r.indexOf(2) + " " + r.lastIndexOf(2) + " " + r.contains(3) + " " + r.indexOf(99));
        attempt(label + " set(0, 7)", () -> r.set(0, 7) + " " + l + " " + r);
        attempt(label + " set(size)", () -> r.set(r.size(), 7));
        attempt(label + " add(8)", () -> r.add(8) + " " + l + " " + r);
        attempt(label + " add(1, 9)", () -> { r.add(1, 9); return l + " " + r; });
        attempt(label + " add(size+1)", () -> { r.add(r.size() + 1, 9); return l + " " + r; });
        attempt(label + " addFirst(10)", () -> { r.addFirst(10); return l + " " + r; });
        attempt(label + " addLast(11)", () -> { r.addLast(11); return l + " " + r; });
        attempt(label + " remove(0)", () -> r.remove(0) + " " + l + " " + r);
        attempt(label + " remove(Integer 2)", () -> r.remove(Integer.valueOf(2)) + " " + l + " " + r);
        attempt(label + " removeFirst", () -> r.removeFirst() + " " + l + " " + r);
        attempt(label + " removeLast", () -> r.removeLast() + " " + l + " " + r);
        attempt(label + " addAll", () -> r.addAll(List.of(20, 21)) + " " + l + " " + r);
        attempt(label + " addAll(1, ..)", () -> r.addAll(1, List.of(30, 31)) + " " + l + " " + r);
        attempt(label + " subList", () -> r.subList(1, 3) + " " + r.subList(1, 3).reversed());
        attempt(label + " subList set", () -> { r.subList(1, 3).set(0, 40); return l + " " + r; });
        attempt(label + " iterator", () -> { StringBuilder b = new StringBuilder(); for (Integer x : r) b.append(x).append(','); return b; });
        attempt(label + " listIterator(2)", () -> { ListIterator<Integer> it = r.listIterator(2); return it.nextIndex() + " " + it.previousIndex() + " " + it.next() + " " + it.previous() + " " + it.previous() + " " + it.hasPrevious(); });
        attempt(label + " listIterator set/add/remove", () -> { ListIterator<Integer> it = r.listIterator(); it.next(); it.set(50); it.add(51); it.next(); it.remove(); return l + " " + r; });
        attempt(label + " iterator remove", () -> { Iterator<Integer> it = r.iterator(); it.next(); it.remove(); return l + " " + r; });
        attempt(label + " removeIf", () -> r.removeIf(x -> x > 20) + " " + l + " " + r);
        attempt(label + " replaceAll", () -> { r.replaceAll(x -> x + 1); return l + " " + r; });
        attempt(label + " sort", () -> { r.sort(null); return l + " " + r; });
        attempt(label + " sort cmp", () -> { r.sort(Comparator.reverseOrder()); return l + " " + r; });
        attempt(label + " stream", () -> r.stream().map(x -> x * 2).collect(Collectors.toList()));
        attempt(label + " toArray", () -> Arrays.toString(r.toArray()) + Arrays.toString(r.toArray(new Integer[0])));
        attempt(label + " spliterator", () -> split(r.spliterator()));
        attempt(label + " forEach", () -> { StringBuilder b = new StringBuilder(); r.forEach(x -> b.append(x).append(';')); return b; });
        attempt(label + " retainAll", () -> r.retainAll(List.of(1, 3, 21, 22, 51)) + " " + l + " " + r);
        attempt(label + " containsAll", () -> r.containsAll(List.of(1, 3)));
        attempt(label + " clear", () -> { r.clear(); return l + " " + r + " " + r.size(); });
        attempt(label + " empty getFirst", () -> r.getFirst());
        attempt(label + " empty removeLast", () -> r.removeLast());
        // what the list does after the view was made
        attempt(label + " after", () -> { try { l.add(1); l.add(2); l.add(3); } catch (RuntimeException e) { return e.getClass().getName(); } return r + " " + r.size(); });
        attempt(label + " walk while adding", () -> { int n = 0; for (Integer x : r) { if (n++ < 3) l.add(x); } return l; });
        attempt(label + " walk original while adding to view", () -> { int n = 0; for (Integer x : l) { if (n++ < 3) r.add(x); } return l; });
    }

    public static void main(String[] args) {
        list("ArrayList", new ArrayList<>(List.of(1, 2, 3, 4, 2, 5)));
        list("LinkedList", new LinkedList<>(List.of(1, 2, 3, 4, 2, 5)));
        list("Vector", new Vector<>(List.of(1, 2, 3, 4, 2, 5)));
        list("CopyOnWriteArrayList", new CopyOnWriteArrayList<>(List.of(1, 2, 3, 4, 2, 5)));
        list("List.of", List.of(1, 2, 3, 4, 2, 5));
        list("asList", Arrays.asList(1, 2, 3, 4, 2, 5));
        list("unmodifiableList", Collections.unmodifiableList(new ArrayList<>(List.of(1, 2, 3, 4, 2, 5))));
        list("synchronizedList", Collections.synchronizedList(new ArrayList<>(List.of(1, 2, 3, 4, 2, 5))));
        list("subList", new ArrayList<>(List.of(0, 1, 2, 3, 4, 2, 5, 6)).subList(1, 7));
        list("emptyList", Collections.emptyList());
        list("singletonList", Collections.singletonList(1));
        list("nCopies", Collections.nCopies(3, 1));
        list("stream toList", Stream.of(1, 2, 3).toList());
        list("Collectors.toList", Stream.of(1, 2, 3).collect(Collectors.toList()));
        list("unmodifiable collect", Stream.of(1, 2, 3).collect(Collectors.toUnmodifiableList()));
        list("Stack", new Stack<Integer>() {{ add(1); add(2); add(3); }});
        LinkedList<Integer> ll = new LinkedList<>(List.of(1, 2, 3));
        LinkedList<Integer> lr = ll.reversed();
        System.out.println(lr + " " + (lr instanceof Deque) + " " + lr.peekFirst() + " " + lr.peekLast() + " " + lr.pollFirst() + " " + ll + " " + lr.descendingIterator().next() + " " + (lr.reversed() == ll));
        lr.push(9);
        lr.offer(8);
        lr.offerFirst(7);
        lr.offerLast(6);
        System.out.println(ll + " " + lr + " " + lr.pop() + " " + lr.poll() + " " + lr.pollLast() + " " + lr.element() + " " + lr.peek() + " " + lr.remove() + " " + ll);
        attempt("lr removeFirstOccurrence", () -> { ll.addAll(List.of(1, 2, 1, 3)); return lr.removeFirstOccurrence(1) + " " + ll + " " + lr.removeLastOccurrence(1) + " " + ll; });
        // reversed of reversed of subList
        List<Integer> base = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        System.out.println(base.reversed().subList(1, 4).reversed() + " " + base.reversed().reversed() + " " + (base.reversed().reversed() == base) + " " + base.reversed().equals(List.of(5, 4, 3, 2, 1)) + " " + List.of(5, 4, 3, 2, 1).equals(base.reversed()) + " " + base.reversed().hashCode() + " " + List.of(5, 4, 3, 2, 1).hashCode());
        // a list of the program
        List<String> mine = new AbstractList<String>() {
            String[] held = {"a", "b", "c"};
            public String get(int i) { return held[i]; }
            public int size() { return held.length; }
        };
        System.out.println(mine.reversed() + " " + mine.reversed().get(0) + " " + mine.reversed().getFirst() + " " + mine.reversed().indexOf("a"));
        attempt("mine set", () -> mine.reversed().set(0, "z"));
        attempt("mine add", () -> mine.reversed().add("z"));
        // Collections.reverse and the view
        List<Integer> c = new ArrayList<>(List.of(1, 2, 3));
        Collections.reverse(c.reversed());
        System.out.println(c + " " + Collections.max(c.reversed()) + " " + c.reversed().stream().findFirst().get());
        attempt("toString of a view with itself", () -> { List<Object> self = new ArrayList<>(); self.add(1); self.add(self.reversed()); return self.reversed(); });
    }
}
