import java.util.*;
import java.util.function.*;

public class Fixes {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        List<Integer> l = new ArrayList<>(List.of(1, 2, 3));
        attempt("list subList(2, 1)", () -> l.subList(2, 1));
        attempt("list subList(-1, 1)", () -> l.subList(-1, 1));
        attempt("list subList(0, 99)", () -> new LinkedList<>(l).subList(0, 99));
        attempt("unmodifiableList removeIf", () -> Collections.unmodifiableList(l).removeIf(x -> false));
        java.util.concurrent.ConcurrentLinkedDeque<Integer> cd = new java.util.concurrent.ConcurrentLinkedDeque<>(List.of(1, 2, 3, 2, 4));
        Iterator<Integer> taken = cd.descendingIterator();
        taken.next();
        taken.next();
        taken.remove();
        System.out.print(cd + " " + cd.removeIf(x -> x == 3) + " " + cd);
        Queue<Integer> q = new java.util.concurrent.LinkedBlockingQueue<>(List.of(1, 2, 3));
        java.util.concurrent.CopyOnWriteArrayList<Integer> cow = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(1, 2, 3, 2));
        System.out.println(" " + q.retainAll(List.of(2)) + " " + q + " " + cow.removeAll(List.of(2)) + " " + cow + " " + cow.retainAll(List.of(3)) + " " + cow);
        attempt("reverseOrder(null)", () -> Collections.reverseOrder(null).compare(1, 2));
        attempt("firstEntry setValue", () -> Collections.synchronizedSortedMap(new TreeMap<>(Map.of("a", 1))).firstEntry().setValue(0));
        attempt("unmodifiable compute", () -> Collections.unmodifiableMap(new HashMap<>()).compute("q", (k, v) -> null));
        attempt("unmodifiable equals", () -> Collections.unmodifiableCollection(l).equals(l) + " " + Map.of("a", 1).entrySet().equals(Set.of(Map.entry("a", 1))));
    }
}
