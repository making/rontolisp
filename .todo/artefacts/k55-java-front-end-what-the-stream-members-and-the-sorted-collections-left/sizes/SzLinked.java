import java.util.*;

public class SzLinked {
    public static void main(String[] args) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", 2);
        System.out.println(m + " " + m.keySet());
    }
}
