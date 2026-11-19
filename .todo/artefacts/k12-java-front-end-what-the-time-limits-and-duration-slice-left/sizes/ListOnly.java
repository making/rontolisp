import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ListOnly {
    public static void main(String[] args) {
        List<String> words = new ArrayList<>();
        words.add("b");
        words.add("a");
        Map<String, Integer> count = new HashMap<>();
        for (String w : words) {
            count.merge(w, 1, Integer::sum);
        }
        System.out.println(words + " " + count + " " + "abc".subSequence(0, 2));
    }
}
