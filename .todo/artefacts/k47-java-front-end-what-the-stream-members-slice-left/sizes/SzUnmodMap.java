import java.util.*;

public class SzUnmodMap {
    public static void main(String[] args) {
        Map<String, Integer> m = Collections.unmodifiableMap(new HashMap<>(Map.of("a", 1)));
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            System.out.println(e);
        }
    }
}
