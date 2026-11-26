import java.util.*;
public class Coll {
    public static void main(String[] args) {
        Map<String, Integer> m = new HashMap<>();
        for (String w : "a b c a b a".split(" ")) m.merge(w, 1, Integer::sum);
        List<String> keys = new ArrayList<>(m.keySet());
        Collections.sort(keys);
        System.out.println(keys + " " + m + " " + Optional.of(3).map(x -> x * 2).get());
    }
}
