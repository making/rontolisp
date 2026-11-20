import java.util.*;

public class SzRemoveAll {
    public static void main(String[] args) {
        Set<String> s = new HashSet<>(List.of("a", "b", "c"));
        s.removeAll(List.of("a"));
        System.out.println(s);
    }
}
