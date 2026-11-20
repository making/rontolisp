import java.util.*;
import java.util.concurrent.*;

public class SzSkip {
    public static void main(String[] args) {
        ConcurrentSkipListMap<String, Integer> m = new ConcurrentSkipListMap<>();
        for (String w : "the quick brown fox jumps over the lazy dog".split(" ")) {
            m.merge(w, 1, Integer::sum);
        }
        System.out.println(m + " " + m.firstKey() + " " + m.headMap("m"));
    }
}
