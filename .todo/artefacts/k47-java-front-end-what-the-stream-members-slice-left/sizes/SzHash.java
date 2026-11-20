import java.util.*;

public class SzHash {
    public static void main(String[] args) {
        Map<String, Integer> counts = new HashMap<>();
        for (String w : "the quick brown fox jumps over the lazy dog the end".split(" ")) {
            counts.merge(w, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            System.out.println(e.getKey() + " " + e.getValue());
        }
    }
}
