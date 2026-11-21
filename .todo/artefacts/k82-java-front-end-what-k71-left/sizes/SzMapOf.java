import java.util.*;

public class SzMapOf {
    public static void main(String[] args) {
        Map<String, Integer> m = Map.of("a", 1, "b", 2);
        System.out.println(m.get("b") + " " + m.size());
    }
}
