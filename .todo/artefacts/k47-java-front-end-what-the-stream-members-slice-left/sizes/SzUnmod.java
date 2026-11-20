import java.util.*;

public class SzUnmod {
    public static void main(String[] args) {
        Set<String> s = Collections.unmodifiableSet(new HashSet<>(List.of("a", "b")));
        Map<String, Integer> m = Collections.unmodifiableMap(new HashMap<>(Map.of("a", 1)));
        for (String x : s) {
            System.out.println(x);
        }
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            System.out.println(e);
        }
    }
}
