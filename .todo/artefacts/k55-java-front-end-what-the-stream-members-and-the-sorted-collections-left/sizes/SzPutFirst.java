import java.util.*;

public class SzPutFirst {
    public static void main(String[] args) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", 2);
        m.putFirst("b", 3);
        System.out.println(m + " " + m.firstEntry() + " " + m.reversed());
    }
}
