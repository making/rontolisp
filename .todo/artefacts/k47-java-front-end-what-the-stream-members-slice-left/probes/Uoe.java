import java.util.*;
import java.util.function.*;

public class Uoe {
    static void attempt(String label, Runnable r) {
        try {
            r.run();
            System.out.println(label + " ok");
        } catch (RuntimeException e) {
            System.out.println(label + " ! " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        Map<String, Integer> h = new HashMap<>(Map.of("a", 1));
        attempt("unmodifiableMap entry", () -> Collections.unmodifiableMap(h).entrySet().iterator().next().setValue(2));
        attempt("Map.of entry", () -> Map.of("a", 1).entrySet().iterator().next().setValue(2));
        attempt("Map.entry", () -> Map.entry("a", 1).setValue(2));
        attempt("unmodifiableList iterator", () -> { Iterator<Integer> i = Collections.unmodifiableList(new ArrayList<>(List.of(1))).iterator(); i.next(); i.remove(); });
        attempt("unmodifiableSet iterator", () -> { Iterator<Integer> i = Collections.unmodifiableSet(new HashSet<>(List.of(1))).iterator(); i.next(); i.remove(); });
        attempt("List.of iterator", () -> { Iterator<Integer> i = List.of(1).iterator(); i.next(); i.remove(); });
        attempt("Set.of iterator", () -> { Iterator<Integer> i = Set.of(1).iterator(); i.next(); i.remove(); });
        attempt("unmodifiableMap keySet iterator", () -> { Iterator<String> i = Collections.unmodifiableMap(h).keySet().iterator(); i.next(); i.remove(); });
        attempt("Map.of values iterator", () -> { Iterator<Integer> i = Map.of("a", 1).values().iterator(); i.next(); i.remove(); });
        attempt("emptyList iterator", () -> { Iterator<Object> i = Collections.emptyList().iterator(); i.remove(); });
        attempt("singleton iterator", () -> { Iterator<Integer> i = Collections.singleton(1).iterator(); i.next(); i.remove(); });
        attempt("plain iterator", () -> { Iterator<Integer> i = new Iterator<Integer>() { public boolean hasNext() { return true; } public Integer next() { return 1; } }; i.next(); i.remove(); });
    }
}
