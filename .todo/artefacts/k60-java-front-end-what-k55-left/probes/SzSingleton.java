import java.util.*;

public class SzSingleton {
    public static void main(String[] args) {
        List<String> one = Collections.singletonList("a");
        List<String> none = Collections.emptyList();
        Map<String, Integer> m = Collections.singletonMap("k", 1);
        System.out.println(one + " " + none + " " + m + " " + one.contains("a") + " " + m.get("k"));
    }
}
