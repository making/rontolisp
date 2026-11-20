import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Rev {
    static void attempt(String label, Supplier<Object> s) {
        try {
            System.out.println(label + " = " + s.get());
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getName() + " " + e.getMessage());
        }
    }

    static void rev(String label, Supplier<List<Integer>> make) {
        attempt(label + " retainAll(all)", () -> { List<Integer> l = make.get(); return l.reversed().retainAll(List.of(1, 2, 3)) + " " + l; });
        attempt(label + " retainAll(some)", () -> { List<Integer> l = make.get(); return l.reversed().retainAll(List.of(1, 3)) + " " + l; });
        attempt(label + " removeAll(none)", () -> { List<Integer> l = make.get(); return l.reversed().removeAll(List.of(9)) + " " + l; });
        attempt(label + " removeAll(some)", () -> { List<Integer> l = make.get(); return l.reversed().removeAll(List.of(2)) + " " + l; });
        attempt(label + " removeIf(none)", () -> { List<Integer> l = make.get(); return l.reversed().removeIf(x -> x > 5) + " " + l; });
        attempt(label + " removeIf(some)", () -> { List<Integer> l = make.get(); return l.reversed().removeIf(x -> x == 2) + " " + l; });
        attempt(label + " remove(o)", () -> { List<Integer> l = make.get(); return l.reversed().remove(Integer.valueOf(2)) + " " + l; });
        attempt(label + " clear", () -> { List<Integer> l = make.get(); l.reversed().clear(); return l; });
        attempt(label + " replaceAll", () -> { List<Integer> l = make.get(); l.reversed().replaceAll(x -> x + 1); return l; });
        attempt(label + " sort", () -> { List<Integer> l = make.get(); l.reversed().sort(null); return l; });
    }

    public static void main(String[] args) {
        rev("ArrayList", () -> new ArrayList<>(List.of(1, 2, 3)));
        rev("LinkedList", () -> new LinkedList<>(List.of(1, 2, 3)));
        rev("asList", () -> Arrays.asList(1, 2, 3));
        rev("unmodifiableList", () -> Collections.unmodifiableList(new ArrayList<>(List.of(1, 2, 3))));
        rev("List.of", () -> List.of(1, 2, 3));
        rev("COW", () -> new CopyOnWriteArrayList<>(List.of(1, 2, 3)));
        rev("nCopies", () -> Collections.nCopies(3, 2));
        rev("singletonList", () -> Collections.singletonList(2));
        rev("emptyList", Collections::emptyList);
        rev("synchronizedList", () -> Collections.synchronizedList(new ArrayList<>(List.of(1, 2, 3))));
        rev("subList", () -> new ArrayList<>(List.of(0, 1, 2, 3, 4)).subList(1, 4));
        rev("Vector", () -> new Vector<>(List.of(1, 2, 3)));
    }
}
