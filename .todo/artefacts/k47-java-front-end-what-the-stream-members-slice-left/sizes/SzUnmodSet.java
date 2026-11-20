import java.util.*;

public class SzUnmodSet {
    public static void main(String[] args) {
        Set<String> s = Collections.unmodifiableSet(new HashSet<>(List.of("a", "b")));
        for (String x : s) {
            System.out.println(x);
        }
    }
}
